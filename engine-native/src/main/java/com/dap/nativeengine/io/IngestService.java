package com.dap.nativeengine.io;

import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.xlsx.XlsxTableReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * One-time conversion of a source file into the Parquet cache, so repeated
 * analysis reads Parquet instead of re-parsing CSV/Excel/JSON.
 *
 * <p>The cache key is SHA-256 of the absolute path, size, modification time,
 * format, reader options (including the resolved CSV encoding and the Excel
 * sheet) and a content fingerprint (first and last 64 KiB): editing the file
 * (or changing how it is read) produces a new entry, while an unchanged source
 * returns the existing Parquet file untouched. An unreadable cache entry is
 * quarantined and rebuilt once under a per-key lock, so parallel ingests
 * converge on a single Parquet file.
 *
 * <p>CSV sources may be cp1251 and friends: {@link CsvEncoding} detects or
 * honours the requested encoding and transcodes to UTF-8 when DuckDB cannot
 * read the original bytes. Rebuilds open their own DuckDB database, so their
 * number is bounded by {@link #REBUILDS} and their memory/threads come from the
 * engine settings.
 */
public final class IngestService {
    private static final int FINGERPRINT_BYTES = 64 * 1024;
    private static final String PARALLELISM_ENV = "DAP_INGEST_PARALLELISM";
    private static final int DEFAULT_PARALLELISM = 2;
    /** Ingest threads when the engine did not configure DuckDB threads. */
    private static final int DEFAULT_THREADS = 4;

    /** Bounds concurrent rebuild sessions: each one opens its own DuckDB database. */
    private static final Semaphore REBUILDS =
            new Semaphore(parallelism(), true);

    /** Static so several IngestService instances sharing a cache directory cannot race. */
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path cacheDir;
    private final String memoryLimit;
    private final Integer threads;
    private final String tempDirectory;

    /** Production service: {@code DAP_CACHE_DIR} or {@code ~/.dap/cache}. */
    public IngestService() {
        this(defaultCacheDir(), null, null, null);
    }

    /** Test seam: an explicit cache directory, DuckDB resources left at their defaults. */
    public IngestService(Path cacheDir) {
        this(cacheDir, null, null, null);
    }

    /** Production wiring: ingest sessions inherit the engine's DuckDB resource settings. */
    public IngestService(String memoryLimit, Integer threads, String tempDirectory) {
        this(defaultCacheDir(), memoryLimit, threads, tempDirectory);
    }

    /** Test seam: an explicit cache directory and explicit DuckDB resources. */
    public IngestService(Path cacheDir, String memoryLimit, Integer threads, String tempDirectory) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
        this.memoryLimit = memoryLimit;
        this.threads = threads == null
                ? Math.min(Runtime.getRuntime().availableProcessors(), DEFAULT_THREADS)
                : threads;
        this.tempDirectory = tempDirectory;
    }

    /** One output column of the ingested dataset, in Arrow type names. */
    public record Column(String name, String type) {
    }

    /**
     * {@code path} is the cached Parquet file; {@code cached} is true when it already
     * existed. {@code encoding} is the CSV encoding actually read (null for other
     * formats); {@code sheet} and {@code sheets} describe an Excel workbook (null and
     * empty for other formats).
     */
    public record Result(Path path, long rows, List<Column> columns, boolean cached,
                         String encoding, String sheet, List<String> sheets) {
        public Result {
            sheets = sheets == null ? List.of() : List.copyOf(sheets);
        }

        /** Result without source metadata; kept for callers that only need the table. */
        public Result(Path path, long rows, List<Column> columns, boolean cached) {
            this(path, rows, columns, cached, null, null, List.of());
        }
    }

    /**
     * Reader options for one ingest. {@code delimiter == null} lets DuckDB sniff the
     * dialect (, ; TAB |), {@code encoding == null} or "auto" detects the CSV charset,
     * and {@code sheet} selects an Excel worksheet (null means the first one).
     */
    public record IngestOptions(boolean header, String delimiter, String quote, String escape,
                                boolean ignoreErrors, boolean nullPadding, boolean strictMode,
                                List<String> nullStrings, String encoding, boolean allVarchar,
                                boolean decimalComma, String sheet) {
        public IngestOptions {
            nullStrings = nullStrings == null ? List.of() : List.copyOf(nullStrings);
        }

        public IngestOptions(boolean header, String delimiter, String quote, String escape,
                             boolean ignoreErrors, boolean nullPadding, boolean strictMode,
                             List<String> nullStrings) {
            this(header, delimiter, quote, escape, ignoreErrors, nullPadding, strictMode,
                    nullStrings, null, false, false, null);
        }

        public IngestOptions(boolean header, String delimiter, String quote, String escape,
                             boolean ignoreErrors, boolean nullPadding, boolean strictMode) {
            this(header, delimiter, quote, escape, ignoreErrors, nullPadding, strictMode,
                    List.of(), null, false, false, null);
        }

        public static IngestOptions defaults() {
            return new IngestOptions(true, null, "\"", "\"", false, false, true,
                    List.of(), null, false, false, null);
        }
    }

    private enum Format {
        CSV, EXCEL, JSON, PARQUET;

        static Format parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("unknown ingest format: " + raw);
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "csv" -> CSV;
                case "excel", "xlsx", "xls", "xlsb" -> EXCEL;
                case "json" -> JSON;
                case "parquet" -> PARQUET;
                default -> throw new IllegalArgumentException("unknown ingest format: " + raw);
            };
        }
    }

    /** Excel sheet actually read plus the workbook's sheet names. */
    private record ExcelMeta(String sheet, List<String> sheets) {
    }

    public Result ingest(Path file, String format) {
        return ingest(file, format, IngestOptions.defaults());
    }

    public Result ingest(Path file, String format, IngestOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        Format parsed = Format.parse(format);
        Path source = PathGuard.shared().checkRead(file.toAbsolutePath().normalize().toString());
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("file not found: " + file);
        }

        CsvEncoding.Plan plan = csvPlan(parsed, source, options.encoding());
        ExcelMeta excel = parsed == Format.EXCEL ? excelMeta(source, options.sheet()) : null;

        String key;
        try {
            key = key(source, parsed, options, plan);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + file + ": " + failure.getMessage(), failure);
        }
        Path target = cacheDir.resolve(key + ".parquet");
        Object lock = LOCKS.computeIfAbsent(
                target.toAbsolutePath().normalize().toString(), ignored -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(cacheDir);
                if (Files.isRegularFile(target)) {
                    try {
                        return describe(target, true, plan, excel);
                    } catch (SQLException unreadable) {
                        quarantine(target);
                    }
                }
                return rebuild(file, source, parsed, options, plan, excel, target);
            } catch (IOException | SQLException failure) {
                throw new IllegalStateException("ingest failed for " + file + ": "
                        + describeFailure(failure), failure);
            }
        }
    }

    /** Resolve the CSV encoding before the cache lookup; null for other formats. */
    private static CsvEncoding.Plan csvPlan(Format parsed, Path source, String encoding) {
        if (parsed != Format.CSV) {
            return null;
        }
        try {
            return CsvEncoding.plan(source, encoding);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + source + ": " + failure.getMessage(), failure);
        }
    }

    /** Sheet names of an Excel workbook plus the sheet this ingest will read. */
    private static ExcelMeta excelMeta(Path source, String selector) {
        List<String> sheets = XlsxTableReader.sheetNames(source);
        return new ExcelMeta(selectSheet(sheets, selector, source), sheets);
    }

    /** Mirror of {@link XlsxTableReader}'s selector rules: name, zero-based index, or first. */
    private static String selectSheet(List<String> sheets, String selector, Path source) {
        if (selector == null || selector.isBlank()) {
            return sheets.get(0);
        }
        if (sheets.contains(selector)) {
            return selector;
        }
        if (selector.matches("\\d+")) {
            try {
                int index = Integer.parseInt(selector);
                if (index >= 0 && index < sheets.size()) {
                    return sheets.get(index);
                }
            } catch (NumberFormatException ignored) {
                // fall through to the error below
            }
        }
        throw new IllegalArgumentException("excel: unknown sheet '" + selector + "' in " + source
                + "; available sheets: " + sheets);
    }

    /** Write a fresh entry and describe it; the caller holds the per-key lock. */
    private Result rebuild(Path file, Path source, Format parsed, IngestOptions options,
                           CsvEncoding.Plan plan, ExcelMeta excel, Path target)
            throws IOException, SQLException {
        Path temporary = cacheDir.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Path decoded = null;
        REBUILDS.acquireUninterruptibly();
        try (DuckDbSession db = new DuckDbSession(memoryLimit, threads, tempDirectory)) {
            try {
                Path readSource = source;
                if (plan != null && plan.transcode()) {
                    decoded = cacheDir.resolve("." + target.getFileName() + "."
                            + UUID.randomUUID() + ".utf8.tmp");
                    try {
                        // Detected encodings may meet stray bytes; explicit ones must not
                        // silently turn them into U+FFFD.
                        CsvEncoding.transcode(source, decoded, plan.charset(), plan.auto());
                    } catch (java.nio.charset.CharacterCodingException invalid) {
                        throw new IllegalArgumentException("cannot decode " + source + " as "
                                + plan.resolved() + ": the bytes are not valid for this encoding; "
                                + "check the 'encoding' option or use auto", invalid);
                    }
                    readSource = decoded;
                }
                write(db, readSource, parsed, temporary, options, plan);
            } catch (SQLException failure) {
                throw new IllegalStateException("ingest failed for " + file + ": "
                        + describeFailure(failure), failure);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try {
                return describe(db, target, false, plan, excel);
            } catch (SQLException unreadable) {
                quarantine(target);
                throw new IllegalStateException("cannot read ingested file " + target
                        + " even after regenerating it from " + source + ": "
                        + describeFailure(unreadable)
                        + ". Remove the cache file and retry, or use a fresh DAP_CACHE_DIR", unreadable);
            }
        } finally {
            REBUILDS.release();
            deleteQuietly(temporary);
            deleteQuietly(decoded);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort: a leftover temp file is inert and gets a new UUID next time
        }
    }

    /** Keep a damaged entry for inspection; fall back to deletion if it cannot be renamed. */
    private static void quarantine(Path target) throws IOException {
        Path damaged = target.resolveSibling(target.getFileName() + ".corrupt-" + UUID.randomUUID());
        try {
            Files.move(target, damaged);
        } catch (IOException moveFailure) {
            Files.deleteIfExists(target);
        }
    }

    private void write(DuckDbSession db, Path source, Format format, Path target,
                       IngestOptions options, CsvEncoding.Plan plan) throws SQLException {
        switch (format) {
            // All CSVs go through DuckDB read_csv, so ingest shares the csv_reader NULL
            // semantics and heap usage stays bounded by DuckDB's read buffer.
            case CSV -> {
                try (Connection connection = db.newConnection()) {
                    String relation = csvRelation(source, options, plan);
                    if (options.decimalComma()) {
                        relation = normalizeDecimalComma(connection, relation);
                    }
                    copy(connection, relation, target);
                }
            }
            case EXCEL -> writeExcel(db, source, target, options);
            case JSON -> copy(db, "read_json_auto(" + literal(source.toString()) + ")", target);
            case PARQUET -> copy(db, "read_parquet(" + literal(source.toString()) + ")", target);
        }
    }

    /** DuckDB read_csv call with explicit options; every value is a SQL literal. */
    private static String csvRelation(Path source, IngestOptions options, CsvEncoding.Plan plan) {
        StringBuilder sql = new StringBuilder("read_csv(").append(literal(source.toString()))
                .append(", header=").append(options.header());
        if (options.delimiter() != null && !options.delimiter().isEmpty()) {
            // An explicit delimiter disables DuckDB's dialect search; omit it to let
            // the sniffer try , ; TAB | on the (possibly transcoded) bytes.
            sql.append(", delim=").append(literal(options.delimiter()));
        }
        sql.append(", quote=").append(literal(options.quote()))
                .append(", escape=").append(literal(options.escape()))
                .append(", ignore_errors=").append(options.ignoreErrors())
                .append(", null_padding=").append(options.nullPadding())
                .append(", strict_mode=").append(options.strictMode());
        if (plan != null) {
            sql.append(", encoding=").append(literal(plan.duckdbEncoding()));
        }
        if (options.allVarchar()) {
            sql.append(", all_varchar=true");
        }
        if (!options.nullStrings().isEmpty()) {
            sql.append(", nullstr=").append(stringList(options.nullStrings()));
        }
        return sql.append(')').toString();
    }

    /**
     * Convert Russian-style decimal strings ("5 449,72") to DOUBLE. A VARCHAR column is
     * converted only when every non-NULL value parses after stripping spaces, NBSP and
     * replacing the decimal comma; otherwise the original text is kept untouched.
     */
    private static String normalizeDecimalComma(Connection connection, String relation)
            throws SQLException {
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (result.next()) {
                names.add(result.getString("column_name"));
                types.add(result.getString("column_type"));
            }
        }
        List<Integer> varchar = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            if ("VARCHAR".equalsIgnoreCase(types.get(index))) {
                varchar.add(index);
            }
        }
        if (varchar.isEmpty()) {
            return relation;
        }

        StringBuilder check = new StringBuilder("SELECT ");
        for (int slot = 0; slot < varchar.size(); slot++) {
            if (slot > 0) {
                check.append(", ");
            }
            String column = quote(names.get(varchar.get(slot)));
            check.append("count(*) FILTER (WHERE ").append(column)
                    .append(" IS NOT NULL AND NOT regexp_full_match(")
                    .append(compactExpression(names.get(varchar.get(slot))))
                    .append(", '^-?(0|[1-9][0-9]{0,14})(,[0-9]+)?$')) AS b").append(slot)
                    .append(", count(*) FILTER (WHERE ").append(column)
                    .append(" IS NOT NULL AND contains(").append(column)
                    .append(", ',')) AS m").append(slot);
        }
        check.append(" FROM ").append(relation);
        boolean[] numeric = new boolean[varchar.size()];
        boolean any = false;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(check.toString())) {
            result.next();
            for (int slot = 0; slot < numeric.length; slot++) {
                // A decimal comma must actually occur, and every value must be a plain
                // decimal number: identifiers such as "00123" or 18-digit keys stay text.
                numeric[slot] = result.getLong("b" + slot) == 0
                        && result.getLong("m" + slot) > 0;
                any |= numeric[slot];
            }
        }
        if (!any) {
            return relation;
        }

        StringBuilder select = new StringBuilder("SELECT ");
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                select.append(", ");
            }
            int slot = varchar.indexOf(index);
            if (slot >= 0 && numeric[slot]) {
                select.append("CAST(").append(decimalExpression(names.get(index)))
                        .append(" AS DOUBLE) AS ").append(quote(names.get(index)));
            } else {
                select.append(quote(names.get(index)));
            }
        }
        // Parenthesized so the caller can wrap it in DESCRIBE/COPY SELECT * FROM <relation>.
        return "(" + select.append(" FROM ").append(relation) + ")";
    }

    /** Text form with space/NBSP thousands separators removed and the comma decimal point. */
    private static String decimalExpression(String column) {
        return "replace(" + compactExpression(column) + ", ',', '.')";
    }

    /** Trimmed text with thousands spaces (space, NBSP, narrow NBSP) removed. */
    private static String compactExpression(String column) {
        String value = quote(column);
        return "replace(replace(replace(trim(" + value + "), ' ', ''), chr(160), ''), "
                + "chr(8239), '')";
    }

    private static String quote(String identifier) {
        return DuckDbSession.quote(identifier);
    }

    private static String stringList(List<String> values) {
        if (values.isEmpty()) {
            return "[]";
        }
        StringBuilder list = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                list.append(", ");
            }
            list.append(literal(values.get(index)));
        }
        return list.append(']').toString();
    }

    /**
     * Stream the worksheet into a scratch DuckDB table and copy that to Parquet. The sheet
     * never becomes an Arrow table, so JVM heap does not grow with the row count.
     */
    private static void writeExcel(DuckDbSession db, Path source, Path target, IngestOptions options)
            throws SQLException {
        String table = db.nextTableName("ingest_xlsx");
        try (Connection connection = db.newConnection()) {
            try {
                loadExcel(connection, table, source, XlsxTableReader.DEFAULT_CHUNK_ROWS, options);
                copy(connection, DuckDbSession.quote(table), target);
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS " + DuckDbSession.quote(table));
                } catch (SQLException | RuntimeException ignored) {
                    // best effort: a failed ingest only leaks a scratch table in this session
                }
            }
        }
    }

    /** Package-visible so tests can assert the chunk bound of the ingest path itself. */
    static XlsxTableReader.LoadResult loadExcel(Connection connection, String table, Path source, int chunkRows)
            throws SQLException {
        return loadExcel(connection, table, source, chunkRows, IngestOptions.defaults());
    }

    static XlsxTableReader.LoadResult loadExcel(Connection connection, String table, Path source,
                                                int chunkRows, IngestOptions options)
            throws SQLException {
        XlsxTableReader.Options reader = new XlsxTableReader.Options(
                options.sheet(), true, List.of(), false, false);
        try {
            return XlsxTableReader.streamInto(connection, table, source, reader, chunkRows);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ingest interrupted", interrupted);
        }
    }

    private static void copy(DuckDbSession db, String source, Path target) throws SQLException {
        try (Connection connection = db.newConnection()) {
            copy(connection, source, target);
        }
    }

    private static void copy(Connection connection, String source, Path target) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            validateTypes(statement, source);
            statement.execute("COPY (SELECT * FROM " + source + ") TO " + literal(target.toString())
                    + " (FORMAT PARQUET)");
        }
    }

    /** Reject nested/exotic types before writing, so ingest and readers share one contract. */
    private static void validateTypes(Statement statement, String source) throws SQLException {
        try (ResultSet result = statement.executeQuery("DESCRIBE SELECT * FROM " + source)) {
            while (result.next()) {
                String type = result.getString("column_type");
                try {
                    TypeMap.fromDuckDb(type);
                } catch (IllegalArgumentException unsupported) {
                    throw new IllegalArgumentException("ingest: column '"
                            + result.getString("column_name") + "' has unsupported type '" + type
                            + "'; flatten nested data first");
                }
            }
        }
    }

    /** Cached-entry metadata read; uses the same DuckDB resources as a rebuild. */
    private Result describe(Path parquet, boolean cached, CsvEncoding.Plan plan,
                            ExcelMeta excel) throws SQLException {
        try (DuckDbSession db = new DuckDbSession(memoryLimit, threads, tempDirectory)) {
            return describe(db, parquet, cached, plan, excel);
        }
    }

    private static Result describe(DuckDbSession db, Path parquet, boolean cached,
                                   CsvEncoding.Plan plan, ExcelMeta excel) throws SQLException {
        String relation = "read_parquet(" + literal(parquet.toString()) + ")";
        try (Connection connection = db.newConnection()) {
            long rows;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + relation)) {
                result.next();
                rows = result.getLong(1);
            }
            List<Column> columns = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
                while (result.next()) {
                    columns.add(new Column(result.getString("column_name"),
                            arrowType(result.getString("column_type"))));
                }
            }
            return new Result(parquet, rows, columns, cached,
                    plan == null ? null : plan.resolved(),
                    excel == null ? null : excel.sheet(),
                    excel == null ? List.of() : excel.sheets());
        }
    }

    /** Arrow type name where the engine knows one; exotic DuckDB types pass through as reported. */
    private static String arrowType(String duckDbType) {
        try {
            return TypeMap.fromDuckDb(duckDbType);
        } catch (IllegalArgumentException unsupported) {
            return duckDbType;
        }
    }

    private static String key(Path source, Format format, IngestOptions options,
                              CsvEncoding.Plan plan) throws IOException {
        long size = Files.size(source);
        String descriptor = source + "\n" + size + "\n"
                + Files.getLastModifiedTime(source).to(TimeUnit.NANOSECONDS) + "\n"
                + format.name().toLowerCase(Locale.ROOT) + "\n" + excelReader(options) + "\n"
                + options.header() + "\n" + options.delimiter() + "\n" + options.quote() + "\n"
                + options.escape() + "\n" + options.ignoreErrors() + "\n"
                + options.nullPadding() + "\n" + options.strictMode() + "\n"
                + String.join("\u0001", options.nullStrings()) + "\n"
                + options.encoding() + "\n" + options.allVarchar() + "\n"
                + options.decimalComma() + "\n"
                + (plan == null ? "" : plan.resolved() + "\u0001" + plan.transcode()) + "\n";
        MessageDigest digest = sha256();
        digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
        // Full hashing is O(file size) on every ingest; head+tail (64 KiB each) catches
        // rewrites, truncation and ZIP/Parquet footers for ~128 KiB of I/O. mtime and
        // size above cover most changes in the middle of a large file as well.
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
            if (channel.size() != size) {
                throw new IOException("source changed while fingerprinting " + source);
            }
            digestRange(channel, digest, 0, (int) Math.min(size, FINGERPRINT_BYTES));
            if (size > FINGERPRINT_BYTES) {
                digestRange(channel, digest, size - FINGERPRINT_BYTES, FINGERPRINT_BYTES);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Excel reader contract in the cache key: changing the sheet or reader rebuilds. */
    private static String excelReader(IngestOptions options) {
        return "excel:" + options.sheet() + "\ntrue\n[]\nfalse\nfalse";
    }

    private static void digestRange(FileChannel channel, MessageDigest digest, long start, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = start;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("source changed while fingerprinting");
            }
            position += read;
        }
        digest.update(buffer.array(), 0, length);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Strip DuckDB JDBC's closed-pending-result wrapper so the reported message is the
     * real parse error (encoding, line number, reader options). The driver prefixes
     * "Attempting to execute an unsuccessful or closed pending query result" and keeps
     * the cause after a following "Error: " line.
     */
    static String describeFailure(Throwable failure) {
        String message = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        String sentinel = "Attempting to execute an unsuccessful or closed pending query result";
        int at = message.indexOf(sentinel);
        if (at >= 0) {
            int cause = message.indexOf("\nError: ", at + sentinel.length());
            if (cause >= 0) {
                message = message.substring(cause + "\nError: ".length());
            } else {
                message = message.substring(at + sentinel.length()).replaceFirst("^:?\\s*", "");
            }
        }
        String duplicate = "Invalid Input Error: Invalid Input Error: ";
        while (message.startsWith(duplicate)) {
            message = "Invalid Input Error: " + message.substring(duplicate.length());
        }
        String stripped = message.strip();
        // Never hand the client an empty reason: fall back to the raw driver text.
        return stripped.isEmpty() ? failure.getMessage().strip() : stripped;
    }

    private static int parallelism() {
        String raw = System.getenv(PARALLELISM_ENV);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PARALLELISM;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value >= 1 && value <= 8 ? value : DEFAULT_PARALLELISM;
        } catch (NumberFormatException ignored) {
            return DEFAULT_PARALLELISM;
        }
    }

    private static Path defaultCacheDir() {
        String configured = System.getenv("DAP_CACHE_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        String home = System.getenv("DAP_HOME");
        Path preferred = home != null && !home.isBlank()
                ? Path.of(home).resolve("build").resolve("cache")
                : Path.of(System.getProperty("user.home"), ".dap", "cache");
        try {
            Files.createDirectories(preferred);
            return preferred;
        } catch (IOException denied) {
            // A sandboxed client may forbid ~/.dap; the temp directory is writable.
            return Path.of(System.getProperty("java.io.tmpdir"), "dap-cache");
        }
    }
}
