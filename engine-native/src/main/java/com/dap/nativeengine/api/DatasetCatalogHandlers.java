package com.dap.nativeengine.api;

import com.dap.nativeengine.io.DatasetCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent dataset catalog over /v1/catalog/datasets: list, add, show, update, delete,
 * refresh and the cached profile. Names are path parameters, so the routes stay flat.
 */
public final class DatasetCatalogHandlers {
    private final DatasetCatalog catalog;

    public DatasetCatalogHandlers(DatasetCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** GET /v1/catalog/datasets: compact listing sorted by name. */
    public void list(Context ctx) {
        Http.session(ctx);
        List<Map<String, Object>> datasets = new ArrayList<>();
        for (DatasetCatalog.Entry entry : catalog.list()) {
            datasets.add(summary(entry));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", datasets.size());
        body.put("datasets", datasets);
        Http.ok(ctx, body);
    }

    /** POST /v1/catalog/datasets: ingest a source file or an already ingested Parquet dataset. */
    public void add(Context ctx) throws Exception {
        Http.session(ctx);
        JsonNode request = Json.parseBody(ctx.body());
        String name = optionalText(request, "name");
        String file = pathText(request, "file");
        String dataset = pathText(request, "dataset");
        if (file != null && dataset != null) {
            throw ApiException.badRequest("pass either 'file' or 'dataset', not both");
        }
        if (file == null && dataset == null) {
            throw ApiException.badRequest("'file' or 'dataset' is required: pass a "
                    + "CSV/Excel/JSON/Parquet file or an ingested Parquet path");
        }
        String format = optionalText(request, "format");
        String sourceFormat = format;
        Path source;
        try {
            if (dataset != null) {
                if (format != null && !"parquet".equalsIgnoreCase(format)) {
                    throw ApiException.badRequest("invalid_format",
                            "'dataset' input is Parquet, got format '" + format + "'",
                            "drop 'format' or pass the source file as 'file'");
                }
                source = Path.of(dataset);
                sourceFormat = "parquet";
            } else {
                source = Path.of(file);
            }
        } catch (InvalidPathException invalid) {
            throw ApiException.badRequest("invalid_path", "invalid path: " + invalid.getInput(),
                    "pass a file path without NUL bytes");
        }
        DatasetCatalog.Entry entry = catalog.add(name, source, sourceFormat,
                optionalText(request, "description"), optionalTags(request),
                optionalText(request, "owner"));
        Http.ok(ctx, view(entry));
    }

    /** GET /v1/catalog/datasets/{name}: the full entry with history and the cached profile. */
    public void get(Context ctx) {
        Http.session(ctx);
        Http.ok(ctx, view(catalog.get(ctx.pathParam("name"))));
    }

    /** PATCH /v1/catalog/datasets/{name}: merge description, tags and owner. */
    public void update(Context ctx) throws Exception {
        Http.session(ctx);
        JsonNode request = Json.parseBody(ctx.body());
        Http.ok(ctx, view(catalog.update(ctx.pathParam("name"),
                optionalText(request, "description"), optionalTags(request),
                optionalText(request, "owner"))));
    }

    /** DELETE /v1/catalog/datasets/{name}. */
    public void remove(Context ctx) {
        Http.session(ctx);
        String name = ctx.pathParam("name");
        catalog.remove(name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("name", name);
        Http.ok(ctx, body);
    }

    /** POST /v1/catalog/datasets/{name}/refresh: re-ingest the source and bump the version. */
    public void refresh(Context ctx) {
        Http.session(ctx);
        Http.ok(ctx, view(catalog.refresh(ctx.pathParam("name"))));
    }

    /** GET /v1/catalog/datasets/{name}/profile?refresh=false: cached or recomputed profile. */
    public void profile(Context ctx) {
        Http.session(ctx);
        String name = ctx.pathParam("name");
        boolean refresh = Http.queryBoolean(ctx, "refresh", false);
        DatasetCatalog.Entry before = catalog.get(name);
        boolean cached = !refresh && before.profile() != null
                && before.profile().contentHash().equals(before.contentHash());
        DatasetCatalog.Profile profile = catalog.profile(name, refresh);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", before.version());
        body.put("contentHash", profile.contentHash());
        body.put("rows", profile.rows());
        body.put("cached", cached);
        body.put("computedAt", profile.computedAt());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (DatasetCatalog.ProfileColumn column : profile.columns()) {
            columns.add(column(column));
        }
        body.put("columns", columns);
        Http.ok(ctx, body);
    }

    // ------------------------------------------------------------- internals

    private static Map<String, Object> view(DatasetCatalog.Entry entry) {
        Map<String, Object> body = summary(entry);
        List<Map<String, Object>> history = new ArrayList<>();
        for (DatasetCatalog.Version version : entry.history()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("version", version.version());
            item.put("contentHash", version.contentHash());
            item.put("rows", version.rows());
            item.put("columns", version.columns());
            item.put("path", version.path());
            item.put("updatedAt", version.updatedAt());
            history.add(item);
        }
        body.put("history", history);
        if (entry.profile() != null) {
            body.put("profile", profile(entry.profile()));
        }
        return body;
    }

    private static Map<String, Object> summary(DatasetCatalog.Entry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", entry.name());
        body.put("description", entry.description());
        body.put("owner", entry.owner());
        body.put("tags", entry.tags());
        body.put("source", entry.source());
        body.put("format", entry.format());
        body.put("path", entry.path());
        body.put("rows", entry.rows());
        body.put("columns", entry.columns());
        body.put("version", entry.version());
        body.put("contentHash", entry.contentHash());
        body.put("createdAt", entry.createdAt());
        body.put("updatedAt", entry.updatedAt());
        return body;
    }

    private static Map<String, Object> profile(DatasetCatalog.Profile profile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentHash", profile.contentHash());
        body.put("rows", profile.rows());
        body.put("computedAt", profile.computedAt());
        List<Map<String, Object>> columns = new ArrayList<>();
        for (DatasetCatalog.ProfileColumn column : profile.columns()) {
            columns.add(column(column));
        }
        body.put("columns", columns);
        return body;
    }

    private static Map<String, Object> column(DatasetCatalog.ProfileColumn column) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", column.name());
        body.put("type", column.type());
        body.put("missing", column.missing());
        body.put("distinct", column.distinct());
        if (column.min() != null) {
            body.put("min", column.min());
        }
        if (column.max() != null) {
            body.put("max", column.max());
        }
        if (column.mean() != null) {
            body.put("mean", column.mean());
        }
        return body;
    }

    private static String optionalText(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw ApiException.badRequest("'" + field + "' must be a string");
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : value;
    }

    private static String pathText(JsonNode request, String field) {
        JsonNode node = request.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().isBlank()) {
            throw ApiException.badRequest("'" + field + "' must be a non-empty string");
        }
        return node.asText().strip();
    }

    private static List<String> optionalTags(JsonNode request) {
        JsonNode node = request.get("tags");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw ApiException.badRequest("'tags' must be an array of strings");
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw ApiException.badRequest("'tags' must be an array of strings");
            }
            tags.add(item.asText().strip());
        }
        return tags;
    }
}
