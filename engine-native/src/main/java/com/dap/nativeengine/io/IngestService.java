package com.dap.nativeengine.io;

import com.dap.nativeengine.engine.ArrowTable;
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
import java.util.concurrent.TimeUnit;

/**
 * One-time conversion of a source file into the Parquet cache, so repeated
 * analysis reads Parquet instead of re-parsing CSV/Excel/JSON.
 *
 * <p>The cache key is SHA-256 of the absolute path, size, modification time,
 * format, reader options and a content fingerprint (first and last 64 KiB):
 * editing the file (or changing how it is read) produces a new entry, while an
 * unchanged source returns the existing Parquet file untouched. An unreadable
 * cache entry is quarantined and rebuilt once under a per-key lock, so parallel
 * ingests converge on a single Parquet file.
 */
public final class IngestService {
    private static final int FINGERPRINT_BYTES = 64 * 1024;

    /** Ingest currently reads every workbook with one fixed option set; keep it in the key. */
    private static final XlsxTableReader.Options EXCEL_OPTIONS =
            new XlsxTableReader.Options(null, true, List.of(), false);
    private static final String EXCEL_READER = "excel:" + EXCEL_OPTIONS.sheet() + "\n"
            + EXCEL_OPTIONS.header() + "\n" + EXCEL_OPTIONS.columns() + "\n"
            + EXCEL_OPTIONS.emptyStringIsNull();

    /** Static so several IngestService instances sharing a cache directory cannot race. */
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path cacheDir;

    /** Production service: {@code DAP_CACHE_DIR} or {@code ~/.dap/cache}. */
    public IngestService() {
        this(defaultCacheDir());
    }

    /** Test seam: an explicit cache directory. */
    public IngestService(Path cacheDir) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
    }

    /** One output column of the ingested dataset, in Arrow type names. */
    public record Column(String name, String type) {
    }

    /** {@code path} is the cached Parquet file; {@code cached} is true when it already existed. */
    public record Result(Path path, long rows, List<Column> columns, boolean cached) {
    }

    /** CSV reader options for the streaming path; defaults match the historical reader. */
    public record CsvOptions(boolean header, String delimiter, String quote, String escape,
                             boolean ignoreErrors, boolean nullPadding, boolean strictMode,
                             List<String> nullStrings) {
        public CsvOptions {
            nullStrings = nullStrings == null ? List.of() : List.copyOf(nullStrings);
        }

        public CsvOptions(boolean header, String delimiter, String quote, String escape,
                          boolean ignoreErrors, boolean nullPadding, boolean strictMode) {
            this(header, delimiter, quote, escape, ignoreErrors, nullPadding, strictMode, List.of());
        }

        public static CsvOptions defaults() {
            return new CsvOptions(true, ",", "\"", "\"", false, false, true, List.of());
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
                case "excel", "xlsx" -> EXCEL;
                case "json" -> JSON;
                case "parquet" -> PARQUET;
                default -> throw new IllegalArgumentException("unknown ingest format: " + raw);
            };
        }
    }

    public Result ingest(Path file, String format) {
        return ingest(file, format, CsvOptions.defaults());
    }

    public Result ingest(Path file, String format, CsvOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        Format parsed = Format.parse(format);
        Path source = PathGuard.shared().checkRead(file.toAbsolutePath().normalize().toString());
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("file not found: " + file);
        }

        String key;
        try {
            key = key(source, parsed, options);
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
                        return describe(target, true);
                    } catch (SQLException unreadable) {
                        quarantine(target);
                    }
                }
                return rebuild(file, source, parsed, options, target);
            } catch (IOException | SQLException failure) {
                throw new IllegalStateException("ingest failed for " + file + ": " + failure.getMessage(), failure);
            }
        }
    }

    /** Write a fresh entry and describe it; the caller holds the per-key lock. */
    private Result rebuild(Path file, Path source, Format parsed, CsvOptions options, Path target)
            throws IOException, SQLException {
        Path temporary = cacheDir.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (DuckDbSession db = new DuckDbSession()) {
            try {
                write(db, source, parsed, temporary, options);
            } catch (SQLException failure) {
                throw new IllegalStateException("ingest failed for " + file + ": " + failure.getMessage(), failure);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try {
                return describe(db, target, false);
            } catch (SQLException unreadable) {
                quarantine(target);
                throw new IllegalStateException("cannot read ingested file " + target
                        + " even after regenerating it from " + source + ": " + unreadable.getMessage()
                        + ". Remove the cache file and retry, or use a fresh DAP_CACHE_DIR", unreadable);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // best effort: a leftover temp file is inert and gets a new UUID next time
            }
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
                       CsvOptions options) throws SQLException {
        switch (format) {
            // All CSVs go through DuckDB read_csv, so ingest shares the csv_reader NULL
            // semantics and heap usage stays bounded by DuckDB's read buffer.
            case CSV -> copy(db, csvRelation(source, options), target);
            case EXCEL -> copyArrow(db, excel(source), target);
            case JSON -> copy(db, "read_json_auto(" + literal(source.toString()) + ")", target);
            case PARQUET -> copy(db, "read_parquet(" + literal(source.toString()) + ")", target);
        }
    }

    /** DuckDB read_csv call with explicit options; every value is a SQL literal. */
    private static String csvRelation(Path source, CsvOptions options) {
        return "read_csv(" + literal(source.toString())
                + ", header=" + options.header()
                + ", delim=" + literal(options.delimiter())
                + ", quote=" + literal(options.quote())
                + ", escape=" + literal(options.escape())
                + ", ignore_errors=" + options.ignoreErrors()
                + ", null_padding=" + options.nullPadding()
                + ", strict_mode=" + options.strictMode()
                + (options.nullStrings().isEmpty() ? ""
                        : ", nullstr=" + stringList(options.nullStrings()))
                + ")";
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

    private static ArrowTable excel(Path source) {
        try {
            return XlsxTableReader.read(source, EXCEL_OPTIONS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ingest interrupted", interrupted);
        }
    }

    private static void copyArrow(DuckDbSession db, ArrowTable table, Path target) throws SQLException {
        try (table) {
            String name = db.load(table, "ingest");
            copy(db, DuckDbSession.quote(name), target);
        }
    }

    private static void copy(DuckDbSession db, String source, Path target) throws SQLException {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
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

    private static Result describe(Path parquet, boolean cached) throws SQLException {
        try (DuckDbSession db = new DuckDbSession()) {
            return describe(db, parquet, cached);
        }
    }

    private static Result describe(DuckDbSession db, Path parquet, boolean cached) throws SQLException {
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
            return new Result(parquet, rows, columns, cached);
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

    private static String key(Path source, Format format, CsvOptions options) throws IOException {
        long size = Files.size(source);
        String descriptor = source + "\n" + size + "\n"
                + Files.getLastModifiedTime(source).to(TimeUnit.NANOSECONDS) + "\n"
                + format.name().toLowerCase(Locale.ROOT) + "\n" + EXCEL_READER + "\n"
                + options.header() + "\n" + options.delimiter() + "\n" + options.quote() + "\n"
                + options.escape() + "\n" + options.ignoreErrors() + "\n"
                + options.nullPadding() + "\n" + options.strictMode() + "\n"
                + String.join("\u0001", options.nullStrings()) + "\n";
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
