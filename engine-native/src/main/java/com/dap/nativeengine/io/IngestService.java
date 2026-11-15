package com.dap.nativeengine.io;

import com.dap.nativeengine.csv.CsvTableReader;
import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.xlsx.XlsxTableReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One-time conversion of a source file into the Parquet cache, so repeated
 * analysis reads Parquet instead of re-parsing CSV/Excel/JSON.
 *
 * <p>The cache is keyed by the source's absolute path, size, modification time
 * and format: editing the file (or passing another format) produces a new entry,
 * while an unchanged source returns the existing Parquet file untouched.
 */
public final class IngestService {
    /** CSVs above this size are streamed through DuckDB instead of parsed into Arrow. */
    private static final long DEFAULT_STREAM_MB = 64;

    private final Path cacheDir;
    private final long streamThresholdMb;

    /** Production service: {@code DAP_CACHE_DIR} or {@code ~/.dap/cache}. */
    public IngestService() {
        this(defaultCacheDir(), streamThresholdMb(System.getenv()));
    }

    /** Test seam: an explicit cache directory and the default streaming threshold. */
    public IngestService(Path cacheDir) {
        this(cacheDir, streamThresholdMb(System.getenv()));
    }

    /** Test seam: an explicit cache directory and streaming threshold in megabytes (0 = always stream). */
    public IngestService(Path cacheDir, long streamThresholdMb) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
        if (streamThresholdMb < 0) {
            throw new IllegalArgumentException("DAP_INGEST_STREAM_MB must be non-negative, got "
                    + streamThresholdMb);
        }
        this.streamThresholdMb = streamThresholdMb;
    }

    /** {@code DAP_INGEST_STREAM_MB}, 64 by default; a blank or invalid value keeps the default. */
    static long streamThresholdMb(Map<String, String> env) {
        String raw = env.get("DAP_INGEST_STREAM_MB");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_STREAM_MB;
        }
        try {
            long value = Long.parseLong(raw.strip());
            return value < 0 ? DEFAULT_STREAM_MB : value;
        } catch (NumberFormatException ignored) {
            return DEFAULT_STREAM_MB;
        }
    }

    /** A source larger than the threshold is ingested by DuckDB's streaming CSV reader. */
    static boolean shouldStream(long bytes, long thresholdMb) {
        return bytes > thresholdMb * 1024L * 1024L;
    }

    /** One output column of the ingested dataset, in Arrow type names. */
    public record Column(String name, String type) {
    }

    /** {@code path} is the cached Parquet file; {@code cached} is true when it already existed. */
    public record Result(Path path, long rows, List<Column> columns, boolean cached) {
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
        Objects.requireNonNull(file, "file");
        Format parsed = Format.parse(format);
        Path source = PathGuard.shared().checkRead(file.toAbsolutePath().normalize().toString());
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("file not found: " + file);
        }

        Path target;
        try {
            target = cacheDir.resolve(key(source, parsed) + ".parquet");
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + file + ": " + failure.getMessage(), failure);
        }
        if (Files.isRegularFile(target)) {
            return describe(target, true);
        }

        Path temporary = cacheDir.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(cacheDir);
            try (DuckDbSession db = new DuckDbSession()) {
                write(db, source, parsed, temporary);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                return describe(db, target, false);
            }
        } catch (SQLException | IOException failure) {
            throw new IllegalStateException("ingest failed for " + file + ": " + failure.getMessage(), failure);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // best effort: a leftover temp file is inert and gets overwritten by a new UUID
            }
        }
    }

    private void write(DuckDbSession db, Path source, Format format, Path target) throws SQLException {
        switch (format) {
            case CSV -> {
                // Large CSVs never become an Arrow table: DuckDB streams read_csv into the
                // Parquet writer, so heap usage stays bounded by the read buffer.
                if (stream(source)) {
                    copy(db, "read_csv(" + literal(source.toString()) + ", header=true)", target);
                } else {
                    copyArrow(db, CsvTableReader.read(source,
                            new CsvTableReader.Options(',', true, List.of(), false)), target);
                }
            }
            case EXCEL -> copyArrow(db, excel(source), target);
            case JSON -> copy(db, "read_json_auto(" + literal(source.toString()) + ")", target);
            case PARQUET -> copy(db, "read_parquet(" + literal(source.toString()) + ")", target);
        }
    }

    /** Whether this source is big enough for the streaming CSV path. */
    private boolean stream(Path source) throws SQLException {
        try {
            return shouldStream(Files.size(source), streamThresholdMb);
        } catch (IOException failure) {
            throw new SQLException("cannot read " + source + ": " + failure.getMessage(), failure);
        }
    }

    private static ArrowTable excel(Path source) {
        try {
            return XlsxTableReader.read(source, new XlsxTableReader.Options(null, true, List.of(), false));
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

    private static Result describe(Path parquet, boolean cached) {
        try (DuckDbSession db = new DuckDbSession()) {
            return describe(db, parquet, cached);
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot read ingested file " + parquet + ": "
                    + failure.getMessage(), failure);
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

    private static String key(Path source, Format format) throws IOException {
        String descriptor = source + "\n" + Files.size(source) + "\n"
                + Files.getLastModifiedTime(source).to(TimeUnit.NANOSECONDS) + "\n"
                + format.name().toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(descriptor.getBytes(StandardCharsets.UTF_8)));
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
