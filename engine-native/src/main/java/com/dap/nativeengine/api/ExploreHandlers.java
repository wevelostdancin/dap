package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.explore.ExploreResult;
import com.dap.nativeengine.explore.ExploreService;
import com.dap.nativeengine.io.IngestService;
import com.dap.nativeengine.io.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * POST /v1/explore: profile, anomalies, relationships and hypotheses in one call.
 * Exactly one source is accepted: a source file (ingested through {@link IngestService})
 * or an already ingested Parquet dataset path.
 */
public final class ExploreHandlers {
    private final ExploreService explore;
    private final IngestService ingest;

    public ExploreHandlers() {
        this(new ExploreService(), new IngestService());
    }

    /** Production wiring: explore inherits the engine's DuckDB resource settings. */
    public ExploreHandlers(NativeService.Settings settings) {
        this(new ExploreService(settings.duckdbMemoryLimit(), settings.duckdbThreads(),
                settings.duckdbTempDirectory(), settings.duckdbExtensionDirectory()),
                new IngestService());
    }

    /** Test seam: explicit services. */
    public ExploreHandlers(ExploreService explore, IngestService ingest) {
        this.explore = Objects.requireNonNull(explore, "explore");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
    }

    public void explore(Context ctx) throws Exception {
        Http.session(ctx);
        JsonNode request = Json.parseBody(ctx.body());
        String file = text(request, "file");
        String dataset = text(request, "dataset");
        if (file != null && dataset != null) {
            throw ApiException.badRequest(
                    "explore: pass exactly one of 'file' or 'dataset', not both");
        }
        if (file == null && dataset == null) {
            throw ApiException.badRequest("explore: pass exactly one of 'file' or 'dataset'");
        }
        String format = text(request, "format");
        if (format != null && file == null) {
            throw ApiException.badRequest("explore: 'format' only applies to 'file'");
        }
        String sql = text(request, "sql");
        int maxColumns = integer(request, "maxColumns", ExploreService.DEFAULT_MAX_COLUMNS);
        int sampleRows = integer(request, "sampleRows", ExploreService.DEFAULT_SAMPLE_ROWS);

        String source;
        Path parquet;
        try {
            if (file != null) {
                Path path = Path.of(file).toAbsolutePath().normalize();
                source = path.toString();
                String resolved = format == null ? formatOf(path) : format;
                if (resolved == null) {
                    throw ApiException.badRequest(
                            "cannot infer format from the file name, pass 'format': " + path);
                }
                parquet = ingest.ingest(path, resolved).path();
            } else {
                Path path = PathGuard.shared().checkRead(
                        Path.of(dataset).toAbsolutePath().normalize().toString());
                source = path.toString();
                parquet = path;
            }
        } catch (IllegalArgumentException invalid) {
            throw ApiException.badRequest(invalid.getMessage());
        }
        ExploreResult result = explore.explore(source, parquet, sql, maxColumns, sampleRows);
        Http.ok(ctx, result.toMap());
    }

    /** Optional non-empty string field; a non-string value is a 400. */
    private static String text(JsonNode request, String name) {
        JsonNode node = request.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw ApiException.badRequest("'" + name + "' must be a string");
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** Optional integer field with a default; a non-integer value is a 400. */
    private static int integer(JsonNode request, String name, int fallback) {
        JsonNode node = request.get(name);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber()) {
            throw ApiException.badRequest("'" + name + "' must be an integer");
        }
        long value = node.longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw ApiException.badRequest("'" + name + "' is out of range: " + value);
        }
        return (int) value;
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
            case "xlsx" -> "xlsx";
            case "json" -> "json";
            case "parquet" -> "parquet";
            default -> null;
        };
    }
}
