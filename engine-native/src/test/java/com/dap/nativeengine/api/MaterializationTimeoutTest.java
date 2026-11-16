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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cross-join preview must be bounded by DAP_QUERY_TIMEOUT_SEC instead of hanging the
 * engine: /table answers 408 query_timeout and the next read still works.
 */
class MaterializationTimeoutTest {
    /**
     * Two 60k-row readers joined on a constant key: the preview materializes 3.6B rows.
     * Measured unbounded on the development machine: 6.0 s, so the 1 s bound is deterministic
     * (the plan's 100M rows count in ~160 ms and would not trip a 1 s timeout).
     */
    private static final int ROWS = 60_000;
    private static final Duration REQUEST = Duration.ofSeconds(10);
    private static final int QUERY_TIMEOUT_SEC = 1;

    @TempDir
    Path tempDir;

    private NativeService service;
    private HttpClient client;
    private String base;
    private String session;

    @BeforeEach
    void startServer() throws Exception {
        // Last setting is DAP_QUERY_TIMEOUT_SEC=1 for this service.
        NativeService.Settings settings = new NativeService.Settings(100, 600, "127.0.0.1", 0,
                null, null, null, 900, QUERY_TIMEOUT_SEC);
        service = new NativeService(settings, new NodeRegistry(), NativeService.defaultExecutors());
        service.start(0);
        base = "http://127.0.0.1:" + service.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        session = "timeout-" + UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Test
    void materializationTimesOut() throws Exception {
        String workflow = createWorkflow("cross-join");
        String leftReader = csvReader(workflow, "left.csv");
        String rightReader = csvReader(workflow, "right.csv");
        String leftKey = mathKey(workflow, leftReader);
        String rightKey = mathKey(workflow, rightReader);
        String joinNode = addNode(workflow, "dap.joiner", Map.of(
                "how", "inner",
                "left_keys", List.of("key"),
                "right_keys", List.of("key"),
                "merge_keys", true));
        connect(workflow, leftKey, joinNode, 0, 0);
        connect(workflow, rightKey, joinNode, 0, 1);

        Resp run = post("/v1/workflows/" + workflow + "/run", Map.of());
        assertEquals(200, run.status(), run.raw().body());

        long started = System.nanoTime();
        Resp table = get("/v1/workflows/" + workflow + "/nodes/" + joinNode + "/table");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(408, table.status(), table.raw().body());
        assertEquals("query_timeout", table.body().path("code").asText());
        assertTrue(elapsedMs < REQUEST.toMillis(),
                "the bounded read must answer before the client timeout: " + elapsedMs + "ms");
        assertTrue(table.body().path("error").asText().contains("timed out"), table.raw().body());

        // The engine stays healthy: a small read of the same session still works.
        Resp readerTable = get("/v1/workflows/" + workflow + "/nodes/" + leftReader + "/table");
        assertEquals(200, readerTable.status(), readerTable.raw().body());
        assertEquals(ROWS, readerTable.body().path("rows").asInt());
    }

    // -------------------------------------------------------------- workflow

    private String csvReader(String workflow, String name) throws Exception {
        Path file = tempDir.resolve(name);
        StringBuilder content = new StringBuilder("id\n");
        for (int row = 0; row < ROWS; row++) {
            content.append(row).append('\n');
        }
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        return addNode(workflow, "dap.csv_reader", Map.of(
                "file", file.toString(),
                "columns", List.of(Map.of("name", "id", "type", "Int64"))));
    }

    private String mathKey(String workflow, String reader) throws Exception {
        String key = addNode(workflow, "dap.math_formula",
                Map.of("outputs", List.of(Map.of("name", "key", "expression", "1"))));
        connect(workflow, reader, key, 0, 0);
        return key;
    }

    // --------------------------------------------------------------- helpers

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

    private void connect(String workflow, String from, String to, int fromPort, int toPort)
            throws Exception {
        Resp response = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", from, "to", to, "fromPort", fromPort, "toPort", toPort));
        assertEquals(200, response.status(), response.raw().body());
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
                .timeout(REQUEST);
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
