package com.dap.nativeengine.api;

import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.exec.Workflow;
import com.dap.nativeengine.io.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Save, diagnostics and shutdown routes. */
public final class OpsHandlers {
    private final ServiceState state;
    private final Runnable onShutdown;

    public OpsHandlers(ServiceState state, Runnable onShutdown) {
        this.state = state;
        this.onShutdown = onShutdown;
    }

    public void save(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        JsonNode request = Json.parseBody(ctx.body());
        JsonNode dirNode = request.get("dir");
        if (dirNode == null || !dirNode.isTextual() || dirNode.asText().isBlank()) {
            throw ApiException.badRequest("'dir' is required");
        }
        String dir = dirNode.asText();
        try {
            Path target = Path.of(dir);
            // Guard before touching the filesystem: a rejected save must not create the directory.
            PathGuard.shared().checkWrite(target.resolve("workflow.json").toString());
            Files.createDirectories(target);
            Files.writeString(target.resolve("workflow.json"), Json.write(workflow.toJson()),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new ApiException(500, "cannot save workflow: " + failure.getMessage(), failure);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("dir", dir);
        Http.ok(ctx, body);
    }

    public void debug(Context ctx) {
        String sessionId = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(sessionId, ctx.pathParam("workflowId"));
        Map<String, Object> body = new LinkedHashMap<>(workflow.debug());
        Map<String, Object> duckdb = new LinkedHashMap<>();
        // Effective values come from the session database once it exists; before the first
        // data request /debug reports what was configured (null means DuckDB's default).
        DuckDbSession database = state.sessionIfPresent(sessionId);
        if (database != null) {
            DuckDbSession.Resources resources = database.resources();
            duckdb.put("memoryLimit", resources.memoryLimit());
            duckdb.put("threads", resources.threads());
            duckdb.put("tempDirectory", resources.tempDirectory());
        } else {
            duckdb.put("memoryLimit", state.settings().duckdbMemoryLimit());
            duckdb.put("threads", state.settings().duckdbThreads());
            duckdb.put("tempDirectory", state.settings().duckdbTempDirectory());
        }
        body.put("duckdb", duckdb);
        Http.ok(ctx, body);
    }

    public void shutdown(Context ctx) {
        Http.session(ctx);
        Http.ok(ctx, Map.of("ok", true));
        onShutdown.run();
    }
}
