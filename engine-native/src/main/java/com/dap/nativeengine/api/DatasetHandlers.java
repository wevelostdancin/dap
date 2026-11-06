package com.dap.nativeengine.api;

import com.dap.nativeengine.io.IngestService;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One-time conversion of a source file into the Parquet cache (POST /v1/datasets). */
public final class DatasetHandlers {
    private final IngestService ingest;

    public DatasetHandlers() {
        this(new IngestService());
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
        Path file = Path.of(fileNode.asText().trim());

        String format;
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

        IngestService.Result result;
        try {
            result = ingest.ingest(file, format);
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
        Http.ok(ctx, body);
    }

    /** Format from the file extension: .csv/.xlsx/.json/.parquet, or null. */
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
            case "xlsx" -> "excel";
            case "json" -> "json";
            case "parquet" -> "parquet";
            default -> null;
        };
    }
}
