package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Status codes pinned by the Guarantee spec: workflow limit, unmaterialized data,
 * failed nodes, unknown workflow nodes and non-integer query parameters.
 */
class StatusCodeContractTest {
    private static final Duration FAST = Duration.ofSeconds(5);

    @TempDir
    Path tempDir;

    private final List<NativeService> extras = new ArrayList<>();
    private NativeService service;
    private HttpClient client;
    private String base;
    private String session;

    @BeforeEach
    void startServer() throws Exception {
        service = startService(new NativeService.Settings(100, 600, "127.0.0.1", 0));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        session = "status-" + UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.close();
            service = null;
        }
        for (NativeService extra : extras) {
            extra.close();
        }
        extras.clear();
    }

    @Test
    void pinnedCodes() throws Exception {
        Resp tableBeforeRun = tableBeforeRun();
        assertEquals(409, tableBeforeRun.status(), tableBeforeRun.raw().body());
        assertEquals("not_materialized", tableBeforeRun.body().path("code").asText());
        assertTrue(nonBlank(tableBeforeRun.body(), "hint"), tableBeforeRun.raw().body());

        Resp profileBeforeRun = profileBeforeRun();
        assertEquals(409, profileBeforeRun.status(), profileBeforeRun.raw().body());
        assertEquals("not_materialized", profileBeforeRun.body().path("code").asText());

        Resp failedNode = tableOfFailedNode();
        assertEquals(409, failedNode.status(), failedNode.raw().body());
        assertEquals("node_failed", failedNode.body().path("code").asText());
        assertTrue(failedNode.body().path("error").asText().contains("file not found"),
                "node_failed must carry the node message: " + failedNode.raw().body());

        assertEquals(404, tableOfUnknownNode().status());
        assertEquals("unknown_node", tableOfUnknownNode().body().path("code").asText());

        Resp badLimit = get("/v1/workflows/wf-1/nodes/1:1/table?limit=abc");
        assertEquals(400, badLimit.status(), badLimit.raw().body());
        assertEquals("invalid_query_param", badLimit.body().path("code").asText());

        Resp workflowLimit = create101stWorkflow();
        assertEquals(409, workflowLimit.status(), workflowLimit.raw().body());
        assertEquals("workflow_limit", workflowLimit.body().path("code").asText());
        assertTrue(nonBlank(workflowLimit.body(), "hint"), workflowLimit.raw().body());
        assertEquals(1, workflowLimit.body().path("limit").asInt(), workflowLimit.raw().body());
    }

    @Test
    void unknownNodeIs404InEveryRoute() throws Exception {
        String workflow = createWorkflow("unknown-node");
        addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));

        Resp table = get("/v1/workflows/" + workflow + "/nodes/999:9/table");
        assertEquals(404, table.status(), table.raw().body());
        assertEquals("unknown_node", table.body().path("code").asText());

        Resp profile = get("/v1/workflows/" + workflow + "/nodes/999:9/profile");
        assertEquals(404, profile.status(), profile.raw().body());
        assertEquals("unknown_node", profile.body().path("code").asText());

        Resp dialog = get("/v1/workflows/" + workflow + "/nodes/999:9/dialog");
        assertEquals(404, dialog.status(), dialog.raw().body());
        assertEquals("unknown_node", dialog.body().path("code").asText());

        Resp patch = patch("/v1/workflows/" + workflow + "/nodes/999:9",
                Map.of("settings", Map.of()));
        assertEquals(404, patch.status(), patch.raw().body());
        assertEquals("unknown_node", patch.body().path("code").asText());

        Resp remove = delete("/v1/workflows/" + workflow + "/nodes/999:9");
        assertEquals(404, remove.status(), remove.raw().body());
        assertEquals("unknown_node", remove.body().path("code").asText());
    }

    // ---------------------------------------------------------------- cases

    private Resp tableBeforeRun() throws Exception {
        String workflow = createWorkflow("before-run");
        String reader = addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));
        return get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table");
    }

    private Resp profileBeforeRun() throws Exception {
        String workflow = createWorkflow("before-run-profile");
        String reader = addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));
        return get("/v1/workflows/" + workflow + "/nodes/" + reader + "/profile");
    }

    private Resp tableOfFailedNode() throws Exception {
        String workflow = createWorkflow("failed-node");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("file", tempDir.resolve("missing.csv").toString());
        settings.put("columns", List.of(Map.of("name", "a", "type", "Int64")));
        String reader = addNode(workflow, "dap.csv_reader", settings);
        assertEquals(500, post("/v1/workflows/" + workflow + "/run", Map.of()).status());
        return get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table");
    }

    private Resp tableOfUnknownNode() throws Exception {
        String workflow = createWorkflow("unknown-node-table");
        addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));
        return get("/v1/workflows/" + workflow + "/nodes/999:9/table");
    }

    private Resp create101stWorkflow() throws Exception {
        // The limit is per session, so a dedicated service with DAP_MAX_WORKFLOWS=1 is enough.
        NativeService limited = startService(new NativeService.Settings(1, 600, "127.0.0.1", 0));
        extras.add(limited);
        String previousBase = base;
        String previousSession = session;
        base = "http://127.0.0.1:" + limited.port();
        session = "limit-" + UUID.randomUUID();
        try {
            assertEquals(200, post("/v1/workflows", Map.of("name", "first")).status());
            return post("/v1/workflows", Map.of("name", "second"));
        } finally {
            base = previousBase;
            session = previousSession;
        }
    }

    // -------------------------------------------------------------- helpers

    private static boolean nonBlank(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank();
    }

    private NativeService startService(NativeService.Settings settings) throws Exception {
        NativeService started = new NativeService(settings, new NodeRegistry(),
                NativeService.defaultExecutors());
        started.start(0);
        base = "http://127.0.0.1:" + started.port();
        return started;
    }

    private Path csvFile() throws Exception {
        Path file = tempDir.resolve("people.csv");
        Files.writeString(file, "name,age\nalice,34\nbob,28\n", StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, Object> csvSettings(Path file) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("file", file.toString());
        settings.put("columns", List.of(
                Map.of("name", "name", "type", "Utf8"),
                Map.of("name", "age", "type", "Int64")));
        return settings;
    }

    private String createWorkflow(String name) throws Exception {
        Resp response = post("/v1/workflows", Map.of("name", name));
        assertEquals(200, response.status(), response.raw().body());
        return response.body().path("id").asText();
    }

    private String addNode(String workflow, String factory, Map<String, Object> settings)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factory", factory);
        body.put("settings", settings);
        Resp response = post("/v1/workflows/" + workflow + "/nodes", body);
        assertEquals(200, response.status(), response.raw().body());
        return response.body().path("node").asText();
    }

    private Resp get(String path) throws Exception {
        return send("GET", path, null);
    }

    private Resp post(String path, Object body) throws Exception {
        return send("POST", path, body);
    }

    private Resp patch(String path, Object body) throws Exception {
        return send("PATCH", path, body);
    }

    private Resp delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    private Resp send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-DAP-Session", session)
                .timeout(FAST);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)));
        }
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode parsed = response.body() == null || response.body().isBlank()
                ? Json.object()
                : Json.MAPPER.readTree(response.body());
        return new Resp(response.statusCode(), parsed, response);
    }

    private record Resp(int status, JsonNode body, HttpResponse<String> raw) {
    }
}
