package com.dap.nativeengine.api;

import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.catalog.NodeSpec;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog routes: GET /v1/nodes, GET /v1/nodes/{nodeTypeId}, GET /v1/nodes/{nodeTypeId}/dialog.
 * Responses are built by NodeSpec; this class only validates the session, resolves the id
 * and maps unknown ids to the status codes frozen in docs/API.md (404 for get, 400 for dialog).
 */
public final class CatalogHandlers {
    private static final int DEFAULT_LIMIT = 50;

    private final NodeRegistry registry;

    public CatalogHandlers(NodeRegistry registry) {
        this.registry = registry;
    }

    public void list(Context ctx) {
        Http.session(ctx);
        String query = ctx.queryParam("q");
        String category = ctx.queryParam("category");
        int limit = Http.queryInt(ctx, "limit", DEFAULT_LIMIT);
        List<NodeSpec> specs = registry.search(query, category, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", specs.size());
        body.put("nodes", specs.stream().map(NodeSpec::brief).toList());
        Http.ok(ctx, body);
    }

    public void get(Context ctx) {
        Http.session(ctx);
        String nodeTypeId = ctx.pathParam("nodeTypeId");
        NodeSpec spec = registry.find(nodeTypeId);
        if (spec == null) {
            throw ApiException.notFound("unknown node: " + nodeTypeId);
        }
        Http.ok(ctx, spec.full());
    }

    public void dialog(Context ctx) {
        Http.session(ctx);
        String nodeTypeId = ctx.pathParam("nodeTypeId");
        NodeSpec spec = registry.find(nodeTypeId);
        if (spec == null) {
            throw ApiException.badRequest("unknown node: " + nodeTypeId);
        }
        Http.ok(ctx, spec.dialog());
    }
}
