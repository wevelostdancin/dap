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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rejected add_node must not leave a ghost node: settings are validated before the node
 * is created.
 */
class AddNodeRollbackTest {
    private static final Duration FAST = Duration.ofSeconds(10);

    @TempDir
    java.nio.file.Path tempDir;

    private NativeService service;
    private HttpClient client;
    private String base;
    private String session;

    @BeforeEach
    void startServer() throws Exception {
        service = new NativeService(new NativeService.Settings(100, 600, "127.0.0.1", 0),
                new NodeRegistry(), NativeService.defaultExecutors());
        service.start(0);
        base = "http://127.0.0.1:" + service.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        session = "ghost-" + UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Test
    void rejectedSettingsLeaveNoGhostNode() throws Exception {
        String workflow = createWorkflow("ghost");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factory", "dap.sorter");
        body.put("settings", Map.of("keys", List.of(), "typo", 1));
        Resp rejected = post("/v1/workflows/" + workflow + "/nodes", body);

        assertEquals(400, rejected.status(), rejected.raw().body());
        assertEquals("unknown_setting", rejected.body().path("code").asText());
        assertEquals(0, nodeCount(workflow), "a rejected add_node must not leave a ghost node");

        // A non-object settings value is rejected the same way.
        body.put("settings", "not-an-object");
        assertEquals(400, post("/v1/workflows/" + workflow + "/nodes", body).status());
        assertEquals(0, nodeCount(workflow));

        // Valid settings still create exactly one node.
        body.put("settings", Map.of("keys", List.of()));
        Resp added = post("/v1/workflows/" + workflow + "/nodes", body);
        assertEquals(200, added.status(), added.raw().body());
        assertEquals(1, nodeCount(workflow));
    }

    // --------------------------------------------------------------- helpers

    private int nodeCount(String workflow) throws Exception {
        Resp nodes = get("/v1/workflows/" + workflow + "/nodes");
        assertEquals(200, nodes.status(), nodes.raw().body());
        return nodes.body().path("nodes").size();
    }

    private String createWorkflow(String name) throws Exception {
        Resp created = post("/v1/workflows", Map.of("name", name));
        assertEquals(200, created.status(), created.raw().body());
        return created.body().path("id").asText();
    }

    private Resp get(String path) throws Exception {
        return send("GET", path, null);
    }

    private Resp post(String path, Object body) throws Exception {
        return send("POST", path, body);
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
