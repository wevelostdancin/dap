package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * dap.csv_reader: one CSV file as a lazy {@code read_csv(...)} relation.
 *
 * <p>The file is validated and passed to DuckDB unchanged; no Arrow table is built and no
 * rows are loaded into the session database. Column names and types come from DuckDB's CSV
 * sniffer, or from the explicit {@code columns} schema, which is applied as a projection of
 * casts over the same relation. The scan therefore stays inside DuckDB and scales to files
 * far larger than the heap.
 *
 * <p>NULL semantics are DuckDB's: both unquoted and quoted empty fields are NULL. The
 * {@code emptyStringIsNull} key is still accepted for compatibility but has no effect.
 */
public final class CsvReaderExecutor implements NodeExecutor {
    private static final String NODE = "csv_reader";

    @Override
    public String nodeId() {
        return "dap.csv_reader";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        JsonNode config = context.config();
        // PathGuard + existence check, exactly like the other file readers.
        Path file = IoNodeSupport.sourceFile(config, NODE);
        String delimiter = delimiter(config);
        boolean header = NodeSql.bool(config, "header", true);
        boolean gzip = compression(config, file);
        // Accepted and type-checked, but DuckDB decides: quoted empty is NULL either way.
        NodeSql.bool(config, "emptyStringIsNull", false);
        List<ColumnSpec> columns = columns(config);

        String sql = relation(file, delimiter, header, gzip, columns, context);
        List<ColumnSpec> result = IoNodeSupport.describe(context, sql, NODE);
        return new LazyPlan(context.db(), sql, result);
    }

    private static String delimiter(JsonNode config) {
        String delimiter = NodeSql.text(config, "delimiter", ",");
        if ("\\t".equals(delimiter)) {
            delimiter = "\t";
        }
        if (delimiter.isEmpty()) {
            throw new IllegalArgumentException("csv_reader: delimiter must not be empty");
        }
        if (delimiter.length() != 1) {
            throw new IllegalArgumentException("csv_reader: delimiter must be exactly one character, got '" + delimiter + "'");
        }
        if (delimiter.charAt(0) == '"' || delimiter.charAt(0) == '\r' || delimiter.charAt(0) == '\n') {
            throw new IllegalArgumentException("csv_reader: invalid delimiter '" + delimiter + "'");
        }
        return delimiter;
    }

    /**
     * Validate {@code compression} against the file's magic bytes and report whether DuckDB
     * must decompress gzip. zstd is rejected here so the error is explicit rather than a
     * cryptic sniffer failure; a mismatch is caught before any SQL runs.
     */
    private static boolean compression(JsonNode config, Path file) {
        String raw = NodeSql.text(config, "compression", "auto");
        String value = raw.trim().toLowerCase(Locale.ROOT);
        boolean forceGzip = "gzip".equals(value);
        boolean forceNone = "none".equals(value);
        if (!forceGzip && !forceNone && !"auto".equals(value)) {
            throw new IllegalArgumentException("csv_reader: 'compression' must be auto, "
                    + "none or gzip, got '" + raw + "'");
        }

        byte[] magic;
        try (InputStream input = Files.newInputStream(file)) {
            magic = input.readNBytes(4);
        } catch (IOException failure) {
            throw new IllegalArgumentException("csv_reader: cannot read file " + file + ": "
                    + failure.getMessage(), failure);
        }
        boolean gzip = magic.length >= 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b;
        boolean zstd = magic.length >= 4 && (magic[0] & 0xff) == 0x28 && (magic[1] & 0xff) == 0xb5
                && (magic[2] & 0xff) == 0x2f && (magic[3] & 0xff) == 0xfd;
        if (zstd) {
            throw new IllegalArgumentException("csv_reader: CSV file " + file + " is zstd-compressed; "
                    + "zstd is not supported (decompress it to plain CSV or .csv.gz)");
        }
        if (forceNone && gzip) {
            throw new IllegalArgumentException("csv_reader: CSV file " + file + " is gzip-compressed but "
                    + "compression is 'none'; set compression to 'auto' or 'gzip'");
        }
        if (forceGzip && !gzip) {
            throw new IllegalArgumentException("csv_reader: CSV file " + file + " is not gzip-compressed "
                    + "but compression is 'gzip'; set compression to 'auto' or 'none'");
        }
        return gzip;
    }

    private static List<ColumnSpec> columns(JsonNode config) {
        List<ColumnSpec> columns = new ArrayList<>();
        JsonNode columnsNode = config.get("columns");
        if (columnsNode == null || columnsNode.isNull()) {
            return columns;
        }
        if (!columnsNode.isArray()) {
            throw new IllegalArgumentException("csv_reader: columns must be an array of {name,type}");
        }
        for (JsonNode column : columnsNode) {
            String name = NodeSql.requiredText(column, "name");
            String type = NodeSql.text(column, "type", "Utf8");
            if (!TypeMap.isSupported(type)) {
                throw new IllegalArgumentException("csv_reader: unsupported type '" + type
                        + "' for column '" + name + "'");
            }
            columns.add(ColumnSpec.of(name, type));
        }
        return columns;
    }

    /** The DuckDB table expression, with an explicit projection when a schema is configured. */
    private static String relation(Path file, String delimiter, boolean header, boolean gzip,
                                   List<ColumnSpec> columns, NodeContext context) {
        String source = "read_csv(" + NodeSql.literal(file.toString())
                + ", delim=" + NodeSql.literal(delimiter)
                + ", header=" + header
                + ", compression=" + NodeSql.literal(gzip ? "gzip" : "none") + ")";
        if (columns.isEmpty()) {
            return "SELECT * FROM " + source;
        }

        // With a header the configured names are looked up in the header, as before.
        // Without one DuckDB exposes the fields positionally as column0, column1, ...
        List<String> headerNames = header
                ? IoNodeSupport.describe(context, "SELECT * FROM " + source, NODE).stream()
                        .map(ColumnSpec::name).toList()
                : List.of();
        StringJoiner select = new StringJoiner(", ", "SELECT ", " FROM " + source);
        for (int i = 0; i < columns.size(); i++) {
            ColumnSpec column = columns.get(i);
            String field;
            if (header) {
                if (!headerNames.contains(column.name())) {
                    throw new IllegalArgumentException("csv_reader: CSV header has no column '"
                            + column.name() + "'; header is " + headerNames);
                }
                field = column.name();
            } else {
                field = "column" + i;
            }
            select.add("CAST(" + NodeSql.quote(field) + " AS " + column.duckDbType()
                    + ") AS " + NodeSql.quote(column.name()));
        }
        return select.toString();
    }
}
