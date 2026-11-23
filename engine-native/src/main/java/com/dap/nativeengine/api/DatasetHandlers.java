package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.io.IngestService;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One-time conversion of a source file into the Parquet cache (POST /v1/datasets). */
public final class DatasetHandlers {
    private final IngestService ingest;

    public DatasetHandlers() {
        this(new IngestService());
    }

    /** Production wiring: ingest sessions inherit the engine's DuckDB resource settings. */
    public DatasetHandlers(NativeService.Settings settings) {
        this(new IngestService(settings.duckdbMemoryLimit(), settings.duckdbThreads(),
                settings.duckdbTempDirectory()));
    }

    /** Test seam: an explicit ingest service. */
    public DatasetHandlers(IngestService ingest) {
        this.ingest = Objects.requireNonNull(ingest, "ingest");
    }

    public void ingest(Context ctx) throws Exception {
        Http.session(ctx);
        JsonNode request = Json.parseBody(ctx.body());
        JsonNode fileNode = request.get("file");
        if (fileNode == null || !fileNode.isTextual() || fileNode.asText().isBlank()) {
            throw ApiException.badRequest("'file' is required");
        }
        Path file;
        String format;
        IngestService.Result result;
        try {
            file = Path.of(fileNode.asText().trim());
            JsonNode formatNode = request.get("format");
            if (formatNode != null && !formatNode.isNull()) {
                if (!formatNode.isTextual() || formatNode.asText().isBlank()) {
                    throw ApiException.badRequest("'format' must be a non-empty string");
                }
                format = formatNode.asText().trim();
            } else {
                format = formatOf(file);
                if (format == null) {
                    throw ApiException.badRequest(
                            "cannot infer format from the file name, pass 'format': " + file);
                }
            }
            IngestService.IngestOptions options = ingestOptions(request);
            result = ingest.ingest(file, format, options);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.badRequest(invalid.getMessage());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", result.path().toString());
        body.put("rows", result.rows());
        body.put("columns", result.columns().stream()
                .map(column -> Map.of("name", column.name(), "type", column.type()))
                .toList());
        body.put("cached", result.cached());
        if (result.encoding() != null) {
            body.put("encoding", result.encoding());
        }
        if (result.sheet() != null) {
            body.put("sheet", result.sheet());
        }
        if (!result.sheets().isEmpty()) {
            body.put("sheets", result.sheets());
        }
        Http.ok(ctx, body);
    }

    /** CSV/Excel reader options; ignored for non-CSV and non-Excel formats. */
    private static IngestService.IngestOptions ingestOptions(JsonNode request) {
        IngestService.IngestOptions defaults = IngestService.IngestOptions.defaults();
        return new IngestService.IngestOptions(
                bool(request, "header", defaults.header()),
                textOrNull(request, "delimiter"),
                text(request, "quote", defaults.quote()),
                text(request, "escape", defaults.escape()),
                bool(request, "ignoreErrors", defaults.ignoreErrors()),
                bool(request, "nullPadding", defaults.nullPadding()),
                bool(request, "strictMode", defaults.strictMode()),
                strings(request, "nullStrings"),
                textOrNull(request, "encoding"),
                bool(request, "allVarchar", false),
                bool(request, "decimalComma", false),
                textOrNull(request, "sheet"));
    }

    private static boolean bool(JsonNode request, String name, boolean fallback) {
        JsonNode node = request.get(name);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isBoolean()) {
            throw ApiException.badRequest("'" + name + "' must be a boolean");
        }
        return node.booleanValue();
    }

    private static List<String> strings(JsonNode request, String name) {
        JsonNode node = request.get(name);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw ApiException.badRequest("'" + name + "' must be an array of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw ApiException.badRequest("'" + name + "' must be an array of strings");
            }
            values.add(item.asText());
        }
        return values;
    }

    private static String text(JsonNode request, String name, String fallback) {
        JsonNode node = request.get(name);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw ApiException.badRequest("'" + name + "' must be a non-empty string");
        }
        return node.asText();
    }

    /** Optional text: absent, null or blank means "use the default/auto value". */
    private static String textOrNull(JsonNode request, String name) {
        JsonNode node = request.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw ApiException.badRequest("'" + name + "' must be a string");
        }
        String value = node.asText();
        return value.isBlank() ? null : value;
    }

    /** Format from the file extension: .csv/.xlsx/.json/.parquet (and .xls/.xlsb), or null. */
    private static String formatOf(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return null;
        }
        String value = name.toString().toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        String extension = dot < 0 ? "" : value.substring(dot + 1);
        return switch (extension) {
            case "csv" -> "csv";
            // Legacy workbooks fail later with an explicit "convert to .xlsx" message.
            case "xlsx", "xls", "xlsb" -> "excel";
            case "json" -> "json";
            case "parquet" -> "parquet";
            default -> null;
        };
    }
}
