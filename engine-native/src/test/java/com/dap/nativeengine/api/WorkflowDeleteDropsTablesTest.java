package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.DuckDbSession;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Deleting a workflow must drop the tables its readers materialized, so a long session
 * does not leak one copy per deleted workflow.
 */
class WorkflowDeleteDropsTablesTest {
    private static final Duration FAST = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

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
        session = "drop-" + UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Test
    void dropTablesForWorkflowDropsOnlyThatWorkflowsTables() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            createTable(db, "csv_wf-1_1:1");
            createTable(db, "excel_wf-1_2:1");
            // What the reader executors actually create: the id is sanitized (':' and '-' -> '_').
            createTable(db, "csv_wf_1_1_1");
            createTable(db, "csv_wf-10_1_1");
            createTable(db, "csv_wf-2_1:1");
            // The in-memory statistics nodes materialize their per-row mapping this way.
            createTable(db, "stats_wf_1_1_1");
            createTable(db, "stats_wf_10_1_1");

            db.dropTablesForWorkflow("wf-1");

            assertEquals(0, tablesStartingWith(db, "csv_wf-1_"), "plan-named csv table must be dropped");
            assertEquals(0, tablesStartingWith(db, "excel_wf-1_"), "plan-named excel table must be dropped");
            assertEquals(0, tablesStartingWith(db, "csv_wf_1_"), "engine-named table must be dropped");
            assertEquals(0, tablesStartingWith(db, "stats_wf_1_"), "stats mapping table must be dropped");
            assertEquals(1, tablesStartingWith(db, "csv_wf-10_"), "wf-10 must survive a wf-1 delete");
            assertEquals(1, tablesStartingWith(db, "csv_wf-2_"), "wf-2 must survive a wf-1 delete");
            assertEquals(1, tablesStartingWith(db, "stats_wf_10_"), "wf-10 stats table must survive a wf-1 delete");
        }
    }

    @Test
    void deletingARunWorkflowLeavesTheNextRunWorking() throws Exception {
        String first = createWorkflow("first");
        String firstReader = addNode(first, csvReaderSettings());
        Resp run = post("/v1/workflows/" + first + "/run", Map.of());
        assertEquals(200, run.status(), run.raw().body());
        assertEquals(200, get("/v1/workflows/" + first + "/nodes/" + firstReader + "/table").status());

        Resp deleted = delete("/v1/workflows/" + first);
        assertEquals(200, deleted.status(), deleted.raw().body());
        assertEquals(404, get("/v1/workflows/" + first + "/nodes").status());

        String second = createWorkflow("second");
        String secondReader = addNode(second, csvReaderSettings());
        Resp secondRun = post("/v1/workflows/" + second + "/run", Map.of());
        assertEquals(200, secondRun.status(), secondRun.raw().body());
        Resp secondTable = get("/v1/workflows/" + second + "/nodes/" + secondReader + "/table");
        assertEquals(200, secondTable.status(), secondTable.raw().body());
        assertEquals(3, secondTable.body().path("rows").asInt());
    }

    // --------------------------------------------------------------- helpers

    private static void createTable(DuckDbSession db, String name) throws Exception {
        db.execute("CREATE TABLE " + DuckDbSession.quote(name) + " (x INTEGER)");
    }

    private static int tablesStartingWith(DuckDbSession db, String prefix) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE starts_with(table_name, '"
                             + prefix + "')")) {
            result.next();
            return result.getInt(1);
        }
    }

    private Map<String, Object> csvReaderSettings() throws Exception {
        Path file = tempDir.resolve("people.csv");
        if (!Files.isRegularFile(file)) {
            Files.writeString(file, "name,age\nalice,34\nbob,28\ncarol,41\n",
                    StandardCharsets.UTF_8);
        }
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

    private String addNode(String workflow, Map<String, Object> settings) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factory", "dap.csv_reader");
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
