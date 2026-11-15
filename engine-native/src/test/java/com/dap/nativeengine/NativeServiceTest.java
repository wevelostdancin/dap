package com.dap.nativeengine;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.Executors;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.io.DatasetCatalog;
import com.dap.nativeengine.io.IngestService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Black-box HTTP tests for the complete /v1 surface of the native service. */
class NativeServiceTest {
    private static final Duration FAST = Duration.ofSeconds(5);
    private static final Duration SLOW_REQUEST = Duration.ofSeconds(20);
    private static final String SLOW_SPEC = """
            {
              "id": "dap.test_sleep",
              "name": "Test Sleep",
              "category": "/Test",
              "type": "Source",
              "contributor": "dap-native",
              "tier": "core",
              "deprecated": false,
              "available": true,
              "description": "Test-only source node that sleeps.",
              "inPorts": [],
              "outPorts": [{"index": 0, "name": "Data", "type": "BufferedDataTable"}],
              "configSchema": {
                "type": "object",
                "additionalProperties": false,
                "properties": {"millis": {"type": "integer", "title": "Millis"}}
              }
            }
            """;

    @TempDir
    Path tempDir;

    private final List<NativeService> extras = new ArrayList<>();
    private NativeService service;
    private HttpClient client;
    private String base;
    private String session;

    @BeforeEach
    void startServer() throws IOException {
        service = startService(new NativeService.Settings(100, 600, "127.0.0.1", 0),
                new NodeRegistry(), NativeService.defaultExecutors());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        session = "test-" + UUID.randomUUID();
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

    // ------------------------------------------------------------------ meta

    @Test
    void metaAndHealth() throws Exception {
        Resp meta = send("GET", "/v1", "bad session!", null);
        assertEquals(200, meta.status());
        assertEquals("DAP", meta.body().path("name").asText());
        assertEquals("1.1.0", meta.body().path("version").asText());
        assertEquals("v1", meta.body().path("api").asText());
        assertEquals("/v1/nodes, /v1/workflows, /v1/jobs, /v1/health", meta.body().path("endpoints").asText());
        assertEquals("1", meta.header("X-DAP-Api-Version"));
        assertNull(meta.header("X-DAP-Session"), "GET /v1 must not validate or echo a session");

        Resp health = get("/v1/health");
        assertEquals(200, health.status());
        assertEquals("1", health.header("X-DAP-Api-Version"));
        assertEquals(session, health.header("X-DAP-Session"));
        JsonNode body = health.body();
        assertTrue(body.path("ok").asBoolean());
        assertEquals("DAP", body.path("name").asText());
        assertEquals("1.1.0", body.path("version").asText());
        assertEquals("1", body.path("api").asText());
        assertEquals(61, body.path("nodes").asInt());
        assertEquals(61, body.path("coreNodes").asInt());
        assertEquals(0, body.path("workflows").asInt());
        assertEquals(0, body.path("sessions").asInt());
        assertEquals(0, body.path("jobs").asInt());
        assertTrue(body.path("memory").path("usedMb").isInt());
        assertTrue(body.path("memory").path("maxMb").isInt());

        Resp invalid = send("GET", "/v1/health", "bad session!", null);
        assertEquals(400, invalid.status());
        assertEquals("invalid session id", invalid.error());
    }

    // --------------------------------------------------------------- catalog

    @Test
    void catalogRoutes() throws Exception {
        Resp list = get("/v1/nodes?limit=100");
        assertEquals(200, list.status());
        assertEquals(61, list.body().path("count").asInt());

        Resp node = get("/v1/nodes/dap.csv_reader");
        assertEquals(200, node.status());
        assertEquals("dap.csv_reader", node.body().path("id").asText());
        assertEquals(true, node.body().path("available").asBoolean());
        assertEquals(200, get("/v1/nodes/dap.csv_reader/dialog").status());
        assertEquals(true, get("/v1/nodes/dap.csv_reader/dialog").body().path("available").asBoolean());

        Resp unknown = get("/v1/nodes/dap.nope");
        assertEquals(404, unknown.status());
        assertEquals("unknown node: dap.nope", unknown.error());
        assertEquals(400, get("/v1/nodes/dap.nope/dialog").status());
        Resp badQuery = get("/v1/nodes?limit=not-a-number");
        assertEquals(400, badQuery.status());
        assertEquals("invalid_query_param", badQuery.body().path("code").asText());
    }

    // ------------------------------------------------- workfow happy path

    @Test
    void workflowLifecycleRunTableProfileDialogDebugAndSave() throws Exception {
        Path csv = csvFile();

        Resp created = post("/v1/workflows", Map.of("name", "people"));
        assertEquals(200, created.status());
        String workflow = created.body().path("id").asText();
        assertTrue(workflow.matches("wf-\\d+"), workflow);
        assertEquals("people", created.body().path("name").asText());
        assertEquals(session, created.body().path("session").asText());
        assertEquals(0, created.body().path("nodes").asInt());

        JsonNode listed = get("/v1/workflows").body();
        assertEquals(1, listed.path("workflows").size());
        assertEquals(workflow, listed.path("workflows").get(0).path("id").asText());
        assertEquals(0, listed.path("workflows").get(0).path("nodes").asInt());

        String reader = addNode(workflow, "dap.csv_reader", csvSettings(csv));
        String firstSorter = addNode(workflow, "dap.sorter", Map.of("keys",
                List.of(Map.of("column", "age", "direction", "asc"))));
        connect(workflow, reader, firstSorter);

        Resp nodes = get("/v1/workflows/" + workflow + "/nodes");
        assertEquals(200, nodes.status());
        assertEquals(2, nodes.body().path("nodes").size());
        JsonNode readerView = nodes.body().path("nodes").get(0);
        assertEquals(reader, readerView.path("node").asText());
        assertEquals("CSV Reader", readerView.path("name").asText());
        assertEquals("IDLE", readerView.path("state").asText());
        assertFalse(readerView.has("message"));
        assertEquals(2, get("/v1/workflows").body().path("workflows").get(0).path("nodes").asInt());

        Resp run = post("/v1/workflows/" + workflow + "/run", Map.of());
        assertEquals(200, run.status(), run.raw().body());
        assertTrue(run.body().path("ok").asBoolean());
        assertTrue(run.body().path("durationMs").asLong() >= 0);
        assertEquals(workflow, run.body().path("workflow").asText());
        assertEquals(2, run.body().path("nodes").size());
        for (JsonNode node : run.body().path("nodes")) {
            assertEquals("EXECUTED", node.path("state").asText());
        }

        assertEquals("EXECUTED", get("/v1/workflows/" + workflow + "/nodes")
                .body().path("nodes").get(1).path("state").asText());

        Resp table = get("/v1/workflows/" + workflow + "/nodes/" + firstSorter + "/table?port=0&limit=10");
        assertEquals(200, table.status(), table.raw().body());
        assertEquals(3, table.body().path("rows").asInt());
        assertEquals(3, table.body().path("preview").asInt());
        assertEquals("name", table.body().path("columns").get(0).path("name").asText());
        assertEquals("String", table.body().path("columns").get(0).path("type").asText());
        assertEquals(0, table.body().path("columns").get(0).path("index").asInt());
        assertEquals("age", table.body().path("columns").get(1).path("name").asText());
        assertEquals("Number (Integer)", table.body().path("columns").get(1).path("type").asText());
        assertEquals("bob", table.body().path("data").get(0).get(0).asText());
        assertEquals("28", table.body().path("data").get(0).get(1).asText());
        assertTrue(table.body().path("data").get(0).get(2).isNull(), "csv null must stay null");

        Resp profile = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/profile");
        assertEquals(200, profile.status(), profile.raw().body());
        assertEquals(3, profile.body().path("rows").asInt());
        JsonNode age = column(profile.body(), "age");
        assertEquals("Number (Integer)", age.path("type").asText());
        assertEquals(0, age.path("missing").asInt());
        assertEquals(3, age.path("distinct").asInt());
        assertEquals(28.0, age.path("min").asDouble(), 1e-9);
        assertEquals(41.0, age.path("max").asDouble(), 1e-9);
        JsonNode city = column(profile.body(), "city");
        assertEquals(1, city.path("missing").asInt());
        assertEquals(2, city.path("distinct").asInt());
        assertFalse(city.has("min"), "non-numeric columns must not carry min/max/mean");
        assertFalse(city.has("max"));
        assertFalse(city.has("mean"));

        Resp dialog = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/dialog");
        assertEquals(200, dialog.status());
        assertEquals("dap.csv_reader", dialog.body().path("id").asText());
        assertEquals(reader, dialog.body().path("node").asText());
        assertEquals(true, dialog.body().path("available").asBoolean());

        Resp debug = get("/v1/workflows/" + workflow + "/debug");
        assertEquals(200, debug.status());
        assertEquals(workflow, debug.body().path("workflow").asText());
        assertEquals(2, debug.body().path("nodes").size());
        assertEquals(1, debug.body().path("connections").size());
        assertEquals(reader + ":0 -> " + firstSorter + ":0",
                debug.body().path("connections").get(0).asText());

        Path saveDir = tempDir.resolve("saved-workflow");
        Resp saved = post("/v1/workflows/" + workflow + "/save", Map.of("dir", saveDir.toString()));
        assertEquals(200, saved.status(), saved.raw().body());
        assertTrue(saved.body().path("ok").asBoolean());
        assertEquals(saveDir.toString(), saved.body().path("dir").asText());
        Path document = saveDir.resolve("workflow.json");
        assertTrue(Files.isRegularFile(document), "save must write workflow.json");
        JsonNode savedJson = Json.MAPPER.readTree(Files.readString(document, StandardCharsets.UTF_8));
        assertEquals("dap-native-workflow/1", savedJson.path("format").asText());
        assertEquals(workflow, savedJson.path("id").asText());
        assertEquals(2, savedJson.path("nodes").size());
        assertEquals(1, savedJson.path("connections").size());

        // Load recreates the graph in a new workflow, settings and edges included.
        Resp loaded = post("/v1/workflows/load", Map.of("dir", saveDir.toString()));
        assertEquals(200, loaded.status(), loaded.raw().body());
        String loadedWorkflow = loaded.body().path("id").asText();
        assertNotEquals(workflow, loadedWorkflow);
        assertEquals(2, loaded.body().path("nodes").asInt());
        Resp loadedDebug = get("/v1/workflows/" + loadedWorkflow + "/debug");
        assertEquals(1, loadedDebug.body().path("connections").size());
        Resp loadedNodes = get("/v1/workflows/" + loadedWorkflow + "/nodes");
        assertEquals(2, loadedNodes.body().path("nodes").size());
        String loadedTail = loadedNodes.body().path("nodes").get(1).path("node").asText();
        assertEquals(200, post("/v1/workflows/" + loadedWorkflow + "/run", Map.of()).status());
        JsonNode loadedRow = get("/v1/workflows/" + loadedWorkflow + "/nodes/" + loadedTail
                + "/table?limit=1").body().path("data").get(0);
        assertEquals("bob", loadedRow.get(0).asText(), "loaded settings must survive the round-trip");
        assertEquals(400, post("/v1/workflows/load", Map.of()).status());
        assertEquals(400, post("/v1/workflows/load",
                Map.of("dir", tempDir.resolve("missing").toString())).status());

        // PATCH merges settings; the next run uses them (descending age now).
        Resp patched = patch("/v1/workflows/" + workflow + "/nodes/" + firstSorter,
                Map.of("settings", Map.of("keys", List.of(Map.of("column", "age", "direction", "desc")))));
        assertEquals(200, patched.status(), patched.raw().body());
        assertTrue(patched.body().path("ok").asBoolean());
        assertEquals(firstSorter, patched.body().path("node").asText());
        assertEquals(200, post("/v1/workflows/" + workflow + "/run", Map.of()).status());
        JsonNode sortedDesc = get("/v1/workflows/" + workflow + "/nodes/" + firstSorter + "/table?limit=1").body();
        assertEquals("carol", sortedDesc.path("data").get(0).get(0).asText());
        assertEquals("41", sortedDesc.path("data").get(0).get(1).asText());

        // A connected node cannot be removed; an isolated one can.
        Resp cannotRemove = delete("/v1/workflows/" + workflow + "/nodes/" + reader);
        assertEquals(400, cannotRemove.status());
        assertEquals(404, delete("/v1/workflows/" + workflow + "/nodes/999:1").status());
        String lonely = addNode(workflow, "dap.sorter", Map.of("keys", List.of()));
        Resp removed = delete("/v1/workflows/" + workflow + "/nodes/" + lonely);
        assertEquals(200, removed.status());
        assertTrue(removed.body().path("ok").asBoolean());
        assertEquals(lonely, removed.body().path("node").asText());
        assertEquals(2, get("/v1/workflows/" + workflow + "/nodes").body().path("nodes").size());

        // Disconnect/reconnect is exact and idempotency is not silently accepted.
        Resp disconnected = post("/v1/workflows/" + workflow + "/disconnect",
                Map.of("from", reader, "to", firstSorter, "fromPort", 0, "toPort", 0));
        assertEquals(200, disconnected.status());
        assertTrue(disconnected.body().path("ok").asBoolean());
        Resp missingEdge = post("/v1/workflows/" + workflow + "/disconnect",
                Map.of("from", reader, "to", firstSorter));
        assertEquals(404, missingEdge.status());
        assertEquals("connection not found", missingEdge.error());
        Resp reconnected = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", reader, "to", firstSorter));
        assertEquals(200, reconnected.status());

        assertEquals(200, delete("/v1/workflows/" + workflow).status());
        assertEquals(404, get("/v1/workflows/" + workflow + "/nodes").status());
        assertEquals(404, delete("/v1/workflows/" + workflow).status());

        // Default workflow name when the body is empty.
        Resp unnamed = post("/v1/workflows", Map.of());
        assertEquals(200, unnamed.status());
        assertTrue(unnamed.body().path("name").asText().startsWith("workflow-"),
                unnamed.body().path("name").asText());
        assertEquals(200, delete("/v1/workflows/" + unnamed.body().path("id").asText()).status());
    }

    // ---------------------------------------------------------- node errors

    @Test
    void addNodeValidationLeavesNoGhostNode() throws Exception {
        String workflow = createWorkflow("node-errors");

        Resp noFactory = post("/v1/workflows/" + workflow + "/nodes", Map.of());
        assertEquals(400, noFactory.status());
        assertEquals("'factory' is required", noFactory.error());

        Resp unknown = post("/v1/workflows/" + workflow + "/nodes", Map.of("factory", "dap.nope"));
        assertEquals(400, unknown.status());
        assertEquals("unknown node: dap.nope", unknown.error());

        assertEquals(0, get("/v1/workflows/" + workflow + "/nodes").body().path("nodes").size());

        Resp badShape = post("/v1/workflows/" + workflow + "/nodes",
                Map.of("factory", "dap.sorter", "settings", "not-an-object"));
        assertEquals(400, badShape.status());
        assertEquals("settings must be an object", badShape.error());

        Resp unknownSetting = post("/v1/workflows/" + workflow + "/nodes",
                Map.of("factory", "dap.sorter", "settings", Map.of("nope", 1)));
        assertEquals(400, unknownSetting.status());
        assertTrue(unknownSetting.error().startsWith("unknown setting 'nope' in 'settings'; available: ["),
                unknownSetting.error());
        assertTrue(unknownSetting.error().contains("keys"), unknownSetting.error());

        // Settings are validated before the node is created: rejected requests leave no ghost.
        assertEquals(0, get("/v1/workflows/" + workflow + "/nodes").body().path("nodes").size());
        String nodeId = addNode(workflow, "dap.sorter", Map.of("keys", List.of()));
        JsonNode nodes = get("/v1/workflows/" + workflow + "/nodes").body().path("nodes");
        assertEquals(1, nodes.size());
        assertEquals("Sorter", nodes.get(0).path("name").asText());
        assertEquals("IDLE", nodes.get(0).path("state").asText());
        assertEquals(nodeId, nodes.get(0).path("node").asText());

        Resp patchUnknownWorkflow = patch("/v1/workflows/wf-999999/nodes/" + nodeId,
                Map.of("settings", Map.of()));
        assertEquals(404, patchUnknownWorkflow.status());
        Resp patchUnknownNode = patch("/v1/workflows/" + workflow + "/nodes/999:1",
                Map.of("settings", Map.of()));
        assertEquals(404, patchUnknownNode.status());
        Resp patchUnknownSetting = patch("/v1/workflows/" + workflow + "/nodes/" + nodeId,
                Map.of("settings", Map.of("nope", 1)));
        assertEquals(400, patchUnknownSetting.status());
        assertTrue(patchUnknownSetting.error().contains("unknown setting"));
        Resp patchMissingSettings = patch("/v1/workflows/" + workflow + "/nodes/" + nodeId, Map.of());
        assertEquals(400, patchMissingSettings.status());
        assertTrue(patchMissingSettings.error().contains("settings"));

        Resp patchOk = patch("/v1/workflows/" + workflow + "/nodes/" + nodeId,
                Map.of("settings", Map.of("keys", List.of(Map.of("column", "age", "direction", "asc")))));
        assertEquals(200, patchOk.status());
        assertEquals(nodeId, patchOk.body().path("node").asText());
    }

    @Test
    void sqlQueryAcceptsEightInputsAndRejectsTheNinth() throws Exception {
        String workflow = createWorkflow("sql-eight-ports");
        String query = addNode(workflow, "dap.sql_query", Map.of("sql", "SELECT 1 AS one"));
        for (int port = 0; port < 8; port++) {
            String source = addNode(workflow, "dap.sorter", Map.of("keys", List.of()));
            Resp connected = post("/v1/workflows/" + workflow + "/connect",
                    Map.of("from", source, "to", query, "fromPort", 0, "toPort", port));
            assertEquals(200, connected.status(), "port " + port + ": " + connected.raw().body());
        }
        String ninth = addNode(workflow, "dap.sorter", Map.of("keys", List.of()));
        Resp rejected = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", ninth, "to", query, "fromPort", 0, "toPort", 8));
        assertEquals(400, rejected.status());
        assertEquals("node has 8 in ports", rejected.error());
    }

    @Test
    void connectValidation() throws Exception {
        String workflow = createWorkflow("connect-errors");
        String reader = addNode(workflow, "dap.csv_reader", Map.of());
        String a = addNode(workflow, "dap.sorter", Map.of("keys", List.of()));
        String b = addNode(workflow, "dap.sorter", Map.of("keys", List.of()));
        String c = addNode(workflow, "dap.sorter", Map.of("keys", List.of()));

        assertEquals(400, post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", reader, "to", "999:1")).status());
        assertEquals(400, post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", "999:1", "to", a)).status());
        Resp badOutPort = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", reader, "to", a, "fromPort", 1, "toPort", 0));
        assertEquals(400, badOutPort.status());
        assertEquals("node has 1 out ports", badOutPort.error());
        Resp badInPort = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", reader, "to", reader, "fromPort", 0, "toPort", 0));
        assertEquals(400, badInPort.status());
        assertEquals("node has 0 in ports", badInPort.error());
        Resp self = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", a, "to", a));
        assertEquals(400, self.status());

        assertEquals(200, post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", a, "to", b)).status());
        Resp duplicate = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", a, "to", b));
        assertEquals(400, duplicate.status());
        assertEquals(200, post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", b, "to", c)).status());
        Resp cycle = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", c, "to", a));
        assertEquals(400, cycle.status());

        // Bad disconnect bodies are 400, a valid but absent edge is 404.
        assertEquals(400, post("/v1/workflows/" + workflow + "/disconnect", Map.of("from", a)).status());
        Resp absentEdge = post("/v1/workflows/" + workflow + "/disconnect",
                Map.of("from", a, "to", c)); // ports 0:0 edge a->c does not exist
        assertEquals(404, absentEdge.status());
        assertEquals("connection not found", absentEdge.error());
        assertEquals(200, post("/v1/workflows/" + workflow + "/disconnect",
                Map.of("from", a, "to", b)).status());
        assertEquals(200, post("/v1/workflows/" + workflow + "/disconnect",
                Map.of("from", b, "to", c)).status());
        assertEquals(404, post("/v1/workflows/" + workflow + "/disconnect",
                Map.of("from", "999:1", "to", a)).status());
    }

    @Test
    void tableAndProfileErrors() throws Exception {
        String workflow = createWorkflow("data-errors");
        String reader = addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));

        assertEquals(404, get("/v1/workflows/wf-999999/nodes/" + reader + "/table").status());
        Resp beforeRun = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table");
        assertEquals(409, beforeRun.status());
        assertEquals("not_materialized", beforeRun.body().path("code").asText());
        assertEquals("node has no table output", beforeRun.error());
        assertEquals(1, beforeRun.body().path("ports").size());
        assertEquals(0, beforeRun.body().path("ports").get(0).path("index").asInt());
        assertEquals("Data", beforeRun.body().path("ports").get(0).path("name").asText());
        assertEquals(404, get("/v1/workflows/" + workflow + "/nodes/999:9/table").status());
        Resp beforeProfile = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/profile");
        assertEquals(409, beforeProfile.status());
        assertEquals("not_materialized", beforeProfile.body().path("code").asText());
        assertEquals("node has no table output", beforeProfile.error());

        assertEquals(200, post("/v1/workflows/" + workflow + "/run", Map.of()).status());

        Resp badPort = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table?port=1");
        assertEquals(400, badPort.status());
        assertEquals("node has 1 out ports", badPort.error());
        Resp negativePort = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table?port=-1");
        assertEquals(400, negativePort.status());

        JsonNode limited = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table?limit=2").body();
        assertEquals(3, limited.path("rows").asInt());
        assertEquals(2, limited.path("preview").asInt());
        assertEquals(2, limited.path("data").size());
        JsonNode clamped = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table?limit=-5").body();
        assertEquals(3, clamped.path("rows").asInt());
        assertEquals(0, clamped.path("preview").asInt());
        assertEquals(0, clamped.path("data").size());

        assertEquals(404, get("/v1/workflows/wf-999999/nodes/" + reader + "/profile").status());
        assertEquals(404, get("/v1/workflows/" + workflow + "/nodes/999:9/profile").status());
        assertEquals(404, get("/v1/workflows/wf-999999/debug").status());
        assertEquals(404, get("/v1/workflows/" + workflow + "/nodes/999:9/dialog").status());
    }

    // --------------------------------------------------------------- jobs

    @Test
    void asyncJobsListGetCancelAndRunQueryVariant() throws Exception {
        String workflow = createWorkflow("jobs");
        addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));

        Resp submitted = post("/v1/workflows/" + workflow + "/jobs", Map.of());
        assertEquals(202, submitted.status(), submitted.raw().body());
        String jobId = submitted.body().path("id").asText();
        assertTrue(jobId.matches("job-\\d+"), jobId);
        assertEquals(workflow, submitted.body().path("workflow").asText());
        assertTrue(Set.of("queued", "running").contains(submitted.body().path("state").asText()),
                submitted.body().path("state").asText());
        assertEquals(600, submitted.body().path("timeoutSec").asInt());
        assertEquals("/v1/jobs/" + jobId, submitted.body().path("statusUrl").asText());
        assertTrue(submitted.body().path("createdAt").asLong() > 0);
        assertFalse(submitted.body().has("result"));

        JsonNode finished = awaitJobState(jobId, Set.of("succeeded"), Duration.ofSeconds(10));
        assertTrue(finished.path("result").path("ok").asBoolean());
        assertEquals(workflow, finished.path("result").path("workflow").asText());
        assertEquals(1, finished.path("result").path("nodes").size());
        assertTrue(finished.path("startedAt").asLong() > 0);
        assertTrue(finished.path("finishedAt").asLong() >= finished.path("startedAt").asLong());
        assertEquals("/v1/jobs/" + jobId, finished.path("statusUrl").asText());

        JsonNode listedJobs = get("/v1/jobs").body();
        assertEquals(1, listedJobs.path("count").asInt());
        JsonNode listedJob = listedJobs.path("jobs").get(0);
        assertEquals(jobId, listedJob.path("id").asText());
        assertFalse(listedJob.has("result"), "GET /v1/jobs must not include results");

        Resp cancelFinished = delete("/v1/jobs/" + jobId);
        assertEquals(409, cancelFinished.status());
        assertFalse(cancelFinished.body().path("ok").asBoolean());
        assertEquals(jobId, cancelFinished.body().path("id").asText());
        assertEquals("succeeded", cancelFinished.body().path("job").path("state").asText());

        Resp queryAsync = post("/v1/workflows/" + workflow + "/run?async=true", Map.of());
        assertEquals(202, queryAsync.status());
        String asyncJob = queryAsync.body().path("id").asText();
        awaitJobState(asyncJob, Set.of("succeeded"), Duration.ofSeconds(10));

        assertEquals(404, get("/v1/jobs/job-999999").status());
        assertTrue(get("/v1/jobs/job-999999").error().contains("unknown job"));
        assertEquals(404, delete("/v1/jobs/job-999999").status());
    }

    @Test
    void cancelRunningJob() throws Exception {
        startSlowService();
        String workflow = createWorkflow("cancel");
        addNode(workflow, "dap.test_sleep", Map.of("millis", 3000));

        Resp submitted = post("/v1/workflows/" + workflow + "/jobs", Map.of("timeoutSec", 30));
        assertEquals(202, submitted.status());
        String jobId = submitted.body().path("id").asText();
        assertEquals(jobId, awaitRunningJob(Duration.ofSeconds(5)));

        Resp cancelled = delete("/v1/jobs/" + jobId);
        assertEquals(200, cancelled.status(), cancelled.raw().body());
        assertTrue(cancelled.body().path("ok").asBoolean());
        assertEquals(jobId, cancelled.body().path("id").asText());

        JsonNode terminal = awaitJobState(jobId, Set.of("cancelled"), Duration.ofSeconds(5));
        assertEquals("cancelled", terminal.path("state").asText());

        Resp again = delete("/v1/jobs/" + jobId);
        assertEquals(409, again.status());
        assertFalse(again.body().path("ok").asBoolean());
    }

    @Test
    void syncRunTimeoutReturns408Quickly() throws Exception {
        startSlowService();
        String workflow = createWorkflow("timeout");
        addNode(workflow, "dap.test_sleep", Map.of("millis", 3000));

        long started = System.nanoTime();
        Resp response = send("POST", "/v1/workflows/" + workflow + "/run",
                session, Map.of("timeoutSec", 1), SLOW_REQUEST);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(408, response.status(), response.raw().body());
        assertEquals("timeout", response.body().path("state").asText());
        assertEquals(workflow, response.body().path("workflow").asText());
        assertTrue(elapsedMs < 5000, "timed out sync run took " + elapsedMs + "ms");
        assertTrue(elapsedMs >= 900, "timeout fired too early: " + elapsedMs + "ms");
        assertFalse(response.body().has("result"));
    }

    // ------------------------------------------------------- slow run health

    @Test
    void healthAnswersWhileSyncRunBlocks() throws Exception {
        long slowMillis = Long.getLong("dap.test.slowMs", 1500L);
        startSlowService();
        String workflow = createWorkflow("blocking");
        addNode(workflow, "dap.test_sleep", Map.of("millis", slowMillis));

        HttpRequest request = request("POST", "/v1/workflows/" + workflow + "/run", session, Map.of())
                .timeout(Duration.ofSeconds(30))
                .build();
        CompletableFuture<HttpResponse<String>> syncRun =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        String jobId = awaitRunningJob(Duration.ofSeconds(10));
        long started = System.nanoTime();
        Resp health = send("GET", "/v1/health", session, null, Duration.ofSeconds(1));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("health answered in " + elapsedMs + "ms while sync run job " + jobId
                + " was running (slow node " + slowMillis + "ms)");

        assertEquals(200, health.status(), health.raw().body());
        assertTrue(health.body().path("ok").asBoolean());
        assertEquals(jobId, get("/v1/jobs/" + jobId).body().path("id").asText());
        assertEquals("running", get("/v1/jobs/" + jobId).body().path("state").asText());
        assertFalse(syncRun.isDone(), "health must be served while the sync run is still in flight");

        HttpResponse<String> finished = syncRun.get(30, TimeUnit.SECONDS);
        assertEquals(200, finished.statusCode());
        JsonNode result = Json.MAPPER.readTree(finished.body());
        assertTrue(result.path("ok").asBoolean());
        assertEquals("EXECUTED", result.path("nodes").get(0).path("state").asText());
        assertTrue(elapsedMs < slowMillis, "health call must not wait for the slow node: " + elapsedMs + "ms");
    }

    // --------------------------------------------------------- isolation etc.

    @Test
    void twoIndependentCsvReadersRunWithoutConnectionErrors() throws Exception {
        String workflow = createWorkflow("two-readers");
        addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));
        addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));
        for (int attempt = 0; attempt < 5; attempt++) {
            Resp run = post("/v1/workflows/" + workflow + "/run", Map.of());
            assertEquals(200, run.status(), run.raw().body());
            assertTrue(run.body().path("ok").asBoolean());
        }
    }

    @Test
    void sessionIsolationAndForeignObjects() throws Exception {
        String workflow = createWorkflow("isolation");
        addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));
        String other = "other-" + UUID.randomUUID();

        assertEquals(404, send("GET", "/v1/workflows/" + workflow + "/nodes", other, null).status());
        assertEquals(404, send("DELETE", "/v1/workflows/" + workflow, other, null).status());
        assertEquals(0, send("GET", "/v1/workflows", other, null).body().path("workflows").size());
        assertEquals(404, send("POST", "/v1/workflows/" + workflow + "/run", other, Map.of()).status());
        assertEquals(404, send("PATCH", "/v1/workflows/" + workflow + "/nodes/1:1", other,
                Map.of("settings", Map.of())).status());

        Resp submitted = post("/v1/workflows/" + workflow + "/jobs", Map.of());
        String jobId = submitted.body().path("id").asText();
        awaitJobState(jobId, Set.of("succeeded"), Duration.ofSeconds(10));
        assertEquals(404, send("GET", "/v1/jobs/" + jobId, other, null).status());
        assertEquals(404, send("DELETE", "/v1/jobs/" + jobId, other, null).status());
        assertEquals(0, send("GET", "/v1/jobs", other, null).body().path("count").asInt());
        assertEquals(200, send("GET", "/v1/health", other, null).status());
    }

    @Test
    void workflowLimitIs500() throws Exception {
        NativeService limited = startService(new NativeService.Settings(1, 600, "127.0.0.1", 0),
                new NodeRegistry(), NativeService.defaultExecutors());
        String previousBase = base;
        base = "http://127.0.0.1:" + limited.port();
        try {
            assertEquals(200, post("/v1/workflows", Map.of("name", "one")).status());
            Resp second = post("/v1/workflows", Map.of("name", "two"));
            assertEquals(409, second.status());
            assertEquals("workflow_limit", second.body().path("code").asText());
            assertEquals(1, second.body().path("limit").asInt());
            assertEquals("workflow limit for session", second.error());
            Resp otherSession = send("POST", "/v1/workflows", "limit-other", Map.of("name", "three"));
            assertEquals(200, otherSession.status(), "the limit is per session");
        } finally {
            base = previousBase;
        }
    }

    @Test
    void unknownRoutesMalformedJsonAndBadBodies() throws Exception {
        Resp noRoute = send("GET", "/v1/nope", session, null);
        assertEquals(404, noRoute.status());
        assertEquals("no route: GET /v1/nope", noRoute.error());
        Resp wrongMethod = send("POST", "/v1/health", session, Map.of());
        assertEquals(404, wrongMethod.status());
        assertEquals("no route: POST /v1/health", wrongMethod.error());
        assertEquals("1", noRoute.header("X-DAP-Api-Version"));

        Resp malformed = send("POST", "/v1/workflows", session, "{not json");
        assertEquals(500, malformed.status());
        assertTrue(malformed.body().has("error"));

        assertEquals(404, get("/v1/workflows/wf-999999/nodes").status());
        assertTrue(get("/v1/workflows/wf-999999/nodes").error().contains("unknown workflow"));

        String workflow = createWorkflow("save");
        assertEquals(400, post("/v1/workflows/" + workflow + "/save", Map.of()).status());
        assertEquals(400, post("/v1/workflows/" + workflow + "/save", Map.of("dir", "  ")).status());
        assertEquals(404, post("/v1/workflows/wf-999999/save", Map.of("dir", tempDir.toString())).status());

        Resp invalidSession = send("GET", "/v1/jobs", "bad session!", null);
        assertEquals(400, invalidSession.status());
        assertEquals("invalid session id", invalidSession.error());
    }

    @Test
    void debugReportsConfiguredResourcesBeforeTheSessionDatabaseExists() throws Exception {
        Path spill = tempDir.resolve("spill-config");
        NativeService limited = startService(
                new NativeService.Settings(100, 600, "127.0.0.1", 0, "1GB", 2, spill.toString()),
                new NodeRegistry(), NativeService.defaultExecutors());
        extras.add(limited);

        String workflow = createWorkflow("resources-config");
        Resp debug = get("/v1/workflows/" + workflow + "/debug");
        assertEquals(200, debug.status());
        JsonNode duckdb = debug.body().path("duckdb");
        assertTrue(duckdb.isObject(), debug.body().toString());
        // No run yet: GET reports the configured values and must not create a session database.
        assertEquals("1GB", duckdb.path("memoryLimit").asText());
        assertEquals(2, duckdb.path("threads").asInt());
        assertEquals(spill.toString(), duckdb.path("tempDirectory").asText());
        assertEquals(0, get("/v1/health").body().path("sessions").asInt(),
                "GET /debug must not create a session database");
    }

    @Test
    void debugReportsEffectiveDuckDbResources() throws Exception {
        Path spill = tempDir.resolve("spill");
        NativeService limited = startService(
                new NativeService.Settings(100, 600, "127.0.0.1", 0, "1GB", 2, spill.toString()),
                new NodeRegistry(), NativeService.defaultExecutors());
        extras.add(limited);

        String workflow = createWorkflow("resources");
        addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));
        Resp run = post("/v1/workflows/" + workflow + "/run", Map.of());
        assertEquals(200, run.status(), run.raw().body());

        Resp debug = get("/v1/workflows/" + workflow + "/debug");
        assertEquals(200, debug.status());
        assertEquals(workflow, debug.body().path("workflow").asText());
        JsonNode duckdb = debug.body().path("duckdb");
        assertTrue(duckdb.isObject(), debug.body().toString());
        assertEquals(2, duckdb.path("threads").asInt());
        assertEquals(spill.toString(), duckdb.path("tempDirectory").asText());
        assertTrue(duckdb.path("memoryLimit").asText().endsWith("MiB"), duckdb.toString());
        assertTrue(Files.isDirectory(spill), "the session must create the configured temp directory");
    }

    @Test
    void shutdownStopsTheService() throws Exception {
        Resp response = post("/v1/shutdown", Map.of());
        assertEquals(200, response.status());
        assertTrue(response.body().path("ok").asBoolean());

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!service.shuttingDown() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(service.shuttingDown(), "shutdownAsync must flip the shuttingDown flag");
    }

    // ------------------------------------------------------------ idle timeout

    @Test
    void idleServiceStopsItselfWithoutTraffic() throws Exception {
        String previousBase = base;
        NativeService idle = startService(new NativeService.Settings(100, 600, "127.0.0.1", 0,
                null, null, null, 1), new NodeRegistry(), NativeService.defaultExecutors());
        extras.add(idle);
        try {
            long started = System.nanoTime();
            awaitStopped(idle, Duration.ofSeconds(5));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(idle.shuttingDown(), "the idle watchdog must request shutdown");
            assertTrue(elapsedMs <= 5000, "idle shutdown took " + elapsedMs + "ms");
        } finally {
            base = previousBase;
        }
    }

    @Test
    void idleWatchdogWaitsForAnActiveJob() throws Exception {
        String previousBase = base;
        NativeService slow = startSlowService(1);
        try {
            String workflow = createWorkflow("idle-job");
            addNode(workflow, "dap.test_sleep", Map.of("millis", 4000));
            Resp submitted = post("/v1/workflows/" + workflow + "/jobs", Map.of("timeoutSec", 30));
            assertEquals(202, submitted.status(), submitted.raw().body());
            String jobId = submitted.body().path("id").asText();
            assertEquals(jobId, awaitRunningJob(Duration.ofSeconds(5)));

            Thread.sleep(2500);
            assertFalse(slow.shuttingDown(), "an active job must keep the engine alive");
            assertEquals(200, get("/v1/health").status(), "the idle engine still serves requests");

            awaitJobState(jobId, Set.of("succeeded"), Duration.ofSeconds(15));
            awaitStopped(slow, Duration.ofSeconds(5));
            assertTrue(slow.shuttingDown());
        } finally {
            base = previousBase;
        }
    }

    @Test
    void zeroIdleTimeoutDisablesTheWatchdog() throws Exception {
        String previousBase = base;
        NativeService persistent = startService(new NativeService.Settings(100, 600, "127.0.0.1", 0,
                null, null, null, 0), new NodeRegistry(), NativeService.defaultExecutors());
        extras.add(persistent);
        try {
            Thread.sleep(2000);
            assertFalse(persistent.shuttingDown(), "DAP_IDLE_TIMEOUT_SEC=0 must disable idle shutdown");
            assertEquals(200, get("/v1/health").status());
        } finally {
            base = previousBase;
        }
    }

    // --------------------------------------------------------------- helpers

    private NativeService startSlowService() throws Exception {
        return startSlowService(0);
    }

    private NativeService startSlowService(int idleTimeoutSec) throws Exception {
        Path root = Files.createTempDirectory("dap-slow-specs");
        Path specs = Files.createDirectories(root.resolve("specs"));
        String index = readResource("specs/index.txt");
        Files.writeString(specs.resolve("index.txt"), index + "\ndap.test_sleep.json\n", StandardCharsets.UTF_8);
        Files.writeString(specs.resolve("dap.test_sleep.json"), SLOW_SPEC, StandardCharsets.UTF_8);

        NodeRegistry registry = new NodeRegistry(new SpecsFirstLoader(root, NodeRegistry.class.getClassLoader()));
        Executors executors = NativeService.defaultExecutors().register(new NodeExecutor() {
            @Override
            public String nodeId() {
                return "dap.test_sleep";
            }

            @Override
            public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
                long millis = context.config().path("millis").asLong(1500L);
                Thread.sleep(millis);
                return new LazyPlan(context.db(), "SELECT 1 AS x", List.of(ColumnSpec.of("x", "Int64")));
            }
        });
        NativeService slow = new NativeService(new NativeService.Settings(100, 600, "127.0.0.1", 0,
                null, null, null, idleTimeoutSec), registry, executors);
        slow.start(0);
        extras.add(slow);
        base = "http://127.0.0.1:" + slow.port();
        return slow;
    }

    /**
     * Resolves specs from the temporary root first and everything else (classes, the real
     * specs) from the parent. A plain URLClassLoader delegates resources to its parent first,
     * which would shadow the temporary index.txt in the application classpath.
     */
    private static final class SpecsFirstLoader extends ClassLoader {
        private final Path root;

        SpecsFirstLoader(Path root, ClassLoader parent) {
            super(parent);
            this.root = root;
        }

        @Override
        public java.net.URL getResource(String name) {
            if (name.startsWith("specs/")) {
                Path candidate = root.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    try {
                        return candidate.toUri().toURL();
                    } catch (java.net.MalformedURLException exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            }
            return getParent().getResource(name);
        }
    }

    private static String readResource(String name) throws IOException {
        try (InputStream stream = NativeServiceTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, "missing classpath resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private NativeService startService(NativeService.Settings settings, NodeRegistry registry,
                                       Executors executors) throws IOException {
        NativeService started = new NativeService(settings, registry, executors);
        started.start(0);
        base = "http://127.0.0.1:" + started.port();
        return started;
    }

    /** Service whose dataset catalog writes to a temp file instead of the shared one. */
    private NativeService startCatalogService(Path datasetsFile) throws IOException {
        return startServiceWithCatalog(new DatasetCatalog(datasetsFile,
                new IngestService(tempDir.resolve("catalog-cache"))));
    }

    private NativeService startServiceWithCatalog(DatasetCatalog catalog) throws IOException {
        NativeService started = new NativeService(
                new NativeService.Settings(100, 600, "127.0.0.1", 0), new NodeRegistry(),
                NativeService.defaultExecutors(), catalog);
        started.start(0);
        base = "http://127.0.0.1:" + started.port();
        extras.add(started);
        return started;
    }

    private Path csvFile() throws IOException {
        Path file = tempDir.resolve("people.csv");
        Files.writeString(file, "name,age,city\nalice,34,Berlin\nbob,28,\ncarol,41,Paris\n",
                StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, Object> csvSettings(Path file) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("file", file.toString());
        settings.put("delimiter", ",");
        settings.put("header", true);
        settings.put("emptyStringIsNull", false);
        settings.put("columns", List.of(
                Map.of("name", "name", "type", "Utf8"),
                Map.of("name", "age", "type", "Int64"),
                Map.of("name", "city", "type", "Utf8")));
        return settings;
    }

    private String createWorkflow(String name) throws Exception {
        Resp response = post("/v1/workflows", Map.of("name", name));
        assertEquals(200, response.status(), response.raw().body());
        return response.body().path("id").asText();
    }

    private String addNode(String workflow, String factory, Map<String, Object> settings) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factory", factory);
        if (settings != null) {
            body.put("settings", settings);
        }
        Resp response = post("/v1/workflows/" + workflow + "/nodes", body);
        assertEquals(200, response.status(), response.raw().body());
        return response.body().path("node").asText();
    }

    private void connect(String workflow, String from, String to) throws Exception {
        Resp response = post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", from, "to", to, "fromPort", 0, "toPort", 0));
        assertEquals(200, response.status(), response.raw().body());
    }

    private static JsonNode column(JsonNode profile, String name) {
        for (JsonNode column : profile.path("columns")) {
            if (name.equals(column.path("name").asText())) {
                return column;
            }
        }
        throw new AssertionError("profile misses column " + name);
    }

    private static void awaitStopped(NativeService stopped, Duration timeout) throws InterruptedException {
        Thread waiter = new Thread(stopped::awaitStop, "test-await-stop");
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(timeout.toMillis());
        assertFalse(waiter.isAlive(), "service did not stop within " + timeout);
    }

    private String awaitRunningJob(Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Resp listed = get("/v1/jobs");
            assertEquals(200, listed.status());
            for (JsonNode job : listed.body().path("jobs")) {
                if ("running".equals(job.path("state").asText())) {
                    return job.path("id").asText();
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no job reached the running state");
    }

    @Test
    void workflowLoadRejectsBrokenDocumentsAndLeavesNoWorkflow() throws Exception {
        Path dir = tempDir.resolve("broken-workflows");
        Files.createDirectories(dir);
        Path file = dir.resolve("workflow.json");
        String reader = "{\"id\":\"a\",\"factory\":\"dap.csv_reader\","
                + "\"settings\":{\"file\":\"/tmp/data.csv\"}}";
        String sorter = "{\"id\":\"b\",\"factory\":\"dap.sorter\",\"settings\":{}}";

        Files.writeString(file, "{\"format\":\"dap-native-workflow/1\",\"nodes\":["
                + reader + "," + reader + "],\"connections\":[]}");
        assertEquals(400, post("/v1/workflows/load", Map.of("dir", dir.toString())).status(),
                "duplicate node ids must be rejected");

        Files.writeString(file, "{\"format\":\"knime/1\",\"nodes\":[],\"connections\":[]}");
        assertEquals(400, post("/v1/workflows/load", Map.of("dir", dir.toString())).status());

        Files.writeString(file, "{\"format\":\"dap-native-workflow/1\",\"nodes\":["
                + "{\"id\":\"a\",\"factory\":\"dap.unknown\"}],\"connections\":[]}");
        assertEquals(400, post("/v1/workflows/load", Map.of("dir", dir.toString())).status());

        Files.writeString(file, "{\"format\":\"dap-native-workflow/1\",\"nodes\":["
                + reader + "," + sorter + "],"
                + "\"connections\":[{\"from\":\"a\",\"to\":\"b\",\"toPort\":9}]}");
        assertEquals(400, post("/v1/workflows/load", Map.of("dir", dir.toString())).status());

        Files.writeString(file, "{\"format\":\"dap-native-workflow/1\",\"nodes\":["
                + reader + "," + sorter + "],"
                + "\"connections\":[{\"from\":\"a\",\"to\":\"b\",\"toPort\":-1}]}");
        assertEquals(400, post("/v1/workflows/load", Map.of("dir", dir.toString())).status());

        Files.writeString(file, "{\"format\":\"dap-native-workflow/1\",\"nodes\":{},\"connections\":[]}");
        assertEquals(400, post("/v1/workflows/load", Map.of("dir", dir.toString())).status());

        assertEquals(0, get("/v1/workflows").body().path("workflows").size(),
                "failed loads must not leak workflows");
    }

    @Test
    void tablePagingProjectionAndFilter() throws Exception {
        String workflow = createWorkflow("table-page");
        String node = addNode(workflow, "dap.sql_query", Map.of("sql",
                "SELECT * FROM (VALUES (1, 'a'), (2, 'b'), (3, 'c')) AS t(id, name) ORDER BY id"));
        assertEquals(200, post("/v1/workflows/" + workflow + "/run", Map.of()).status());

        String base = "/v1/workflows/" + workflow + "/nodes/" + node + "/table";
        Resp first = get(base + "?limit=2&offset=0");
        assertEquals(200, first.status(), first.raw().body());
        assertEquals(3, first.body().path("rows").asInt());
        assertEquals(0, first.body().path("offset").asInt());
        assertEquals(2, first.body().path("nextOffset").asInt());
        assertTrue(first.body().path("truncated").asBoolean());
        assertEquals(2, first.body().path("data").size());
        assertEquals("1", first.body().path("data").get(0).get(0).asText());

        Resp second = get(base + "?limit=2&offset=2");
        assertEquals(200, second.status());
        assertEquals(1, second.body().path("data").size());
        assertEquals("3", second.body().path("data").get(0).get(0).asText());
        assertTrue(second.body().path("nextOffset").isNull());
        assertFalse(second.body().path("truncated").asBoolean());

        Resp projected = get(base + "?columns=name");
        assertEquals(200, projected.status(), projected.raw().body());
        assertEquals(1, projected.body().path("columns").size());
        assertEquals("name", projected.body().path("columns").get(0).path("name").asText());
        assertEquals("a", projected.body().path("data").get(0).get(0).asText());

        Resp filtered = get(base + "?where=" + java.net.URLEncoder.encode("id > 1",
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(200, filtered.status(), filtered.raw().body());
        assertEquals(2, filtered.body().path("rows").asInt());
        assertEquals(2, filtered.body().path("data").size());

        Resp unknown = get(base + "?columns=nope");
        assertEquals(400, unknown.status());
        assertEquals("unknown_column", unknown.body().path("code").asText(), unknown.error());

        for (String injection : List.of("1=1; DROP TABLE x", "1=1 -- x", "SELECT 1",
                "read_csv('/etc/passwd')", "1=1 /* x")) {
            Resp rejected = get(base + "?where=" + java.net.URLEncoder.encode(injection,
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(400, rejected.status(), injection);
            assertEquals("invalid_filter", rejected.body().path("code").asText(), rejected.error());
        }
    }

    @Test
    void estimateReportsCardinalityForReaderAndQueryNodes() throws Exception {
        Path csv = Path.of("..", "tests", "goldens-native", "data", "iris.csv")
                .toAbsolutePath().normalize();
        String workflow = createWorkflow("estimate");
        String reader = addNode(workflow, "dap.csv_reader", Map.of("file", csv.toString()));
        String query = addNode(workflow, "dap.sql_query",
                Map.of("sql", "SELECT * FROM \"" + reader + "\" WHERE sepal_length > 5"));
        assertEquals(200, post("/v1/workflows/" + workflow + "/connect",
                Map.of("from", reader, "to", query)).status());

        Resp before = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/estimate");
        assertEquals(409, before.status());
        assertEquals("estimate_unavailable", before.body().path("code").asText(), before.error());

        assertEquals(200, post("/v1/workflows/" + workflow + "/run", Map.of()).status());

        Resp readerEstimate = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/estimate");
        assertEquals(200, readerEstimate.status(), readerEstimate.raw().body());
        assertEquals(reader, readerEstimate.body().path("node").asText());
        assertEquals(150, readerEstimate.body().path("rows").asInt());
        assertFalse(readerEstimate.body().path("operators").isEmpty());
        assertTrue(readerEstimate.body().path("plan").has("physical_plan"));

        Resp queryEstimate = get("/v1/workflows/" + workflow + "/nodes/" + query + "/estimate");
        assertEquals(200, queryEstimate.status(), queryEstimate.raw().body());
        assertTrue(queryEstimate.body().path("rows").asInt() > 0);
        assertTrue(queryEstimate.body().path("operators").size() >= 2);
    }

    @Test
    void asyncRunReportsProgress() throws Exception {
        String workflow = createWorkflow("progress");
        String node = addNode(workflow, "dap.sql_query",
                Map.of("sql", "SELECT * FROM range(5) AS t(i)"));
        Resp submitted = post("/v1/workflows/" + workflow + "/run?async=true", Map.of());
        assertEquals(202, submitted.status(), submitted.raw().body());
        String jobId = submitted.body().path("id").asText();

        JsonNode finished = awaitJobState(jobId, Set.of("succeeded"), Duration.ofSeconds(10));
        JsonNode progress = finished.path("progress");
        assertEquals(1, progress.path("nodesTotal").asInt());
        assertEquals(1, progress.path("nodesDone").asInt());
        assertEquals(100, progress.path("percent").asInt());
        assertTrue(progress.has("currentNode"));
        assertNotNull(node);
    }

    @Test
    void ingestFeedsParquetReader() throws Exception {
        Path csv = Path.of("..", "tests", "goldens-native", "data", "iris.csv")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(csv), "fixture missing: " + csv);

        Resp ingested = post("/v1/datasets", Map.of("file", csv.toString(), "format", "csv"));
        assertEquals(200, ingested.status(), ingested.raw().body());
        String parquet = ingested.body().path("path").asText();
        assertEquals(150, ingested.body().path("rows").asInt());
        assertTrue(Files.isRegularFile(Path.of(parquet)), "ingest must write a parquet file");

        Resp cached = post("/v1/datasets", Map.of("file", csv.toString(), "format", "csv"));
        assertTrue(cached.body().path("cached").asBoolean(), "second ingest must hit the cache");

        String workflow = createWorkflow("ingest");
        String reader = addNode(workflow, "dap.parquet_reader", Map.of("file", parquet));
        assertEquals(200, post("/v1/workflows/" + workflow + "/run", Map.of()).status());
        Resp table = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table?limit=3");
        assertEquals(200, table.status(), table.raw().body());
        assertEquals(150, table.body().path("rows").asInt());
        assertEquals("sepal_length", table.body().path("columns").get(0).path("name").asText());
    }

    // ------------------------------------------------------- dataset catalog

    @Test
    void datasetCatalogCrudRoutes() throws Exception {
        Path datasetsFile = tempDir.resolve("catalog").resolve("datasets.json");
        startCatalogService(datasetsFile);

        Resp empty = get("/v1/catalog/datasets");
        assertEquals(200, empty.status(), empty.raw().body());
        assertEquals(0, empty.body().path("count").asInt());

        Path csv = csvFile();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "people");
        request.put("file", csv.toString());
        request.put("format", "csv");
        request.put("description", "demo data");
        request.put("tags", List.of("demo", "p6"));
        request.put("owner", "alice");
        Resp added = post("/v1/catalog/datasets", request);
        assertEquals(200, added.status(), added.raw().body());
        JsonNode entry = added.body();
        assertEquals("people", entry.path("name").asText());
        assertEquals("demo data", entry.path("description").asText());
        assertEquals("alice", entry.path("owner").asText());
        assertEquals(2, entry.path("tags").size());
        assertEquals(csv.toAbsolutePath().normalize().toString(), entry.path("source").asText());
        assertEquals("csv", entry.path("format").asText());
        assertEquals(3, entry.path("rows").asInt());
        assertEquals(3, entry.path("columns").asInt());
        assertEquals(1, entry.path("version").asInt());
        assertEquals(64, entry.path("contentHash").asText().length());
        assertTrue(Files.isRegularFile(Path.of(entry.path("path").asText())));
        assertTrue(Files.isRegularFile(datasetsFile), "the catalog file must be written");
        assertEquals(0, entry.path("history").size());
        assertFalse(entry.has("profile"), "a fresh entry has no cached profile");

        Resp duplicate = post("/v1/catalog/datasets", request);
        assertEquals(409, duplicate.status());
        assertEquals("dataset_exists", duplicate.body().path("code").asText());
        assertTrue(duplicate.body().path("hint").asText().contains("refresh"));

        Resp invalidName = post("/v1/catalog/datasets",
                Map.of("name", "bad name", "file", csv.toString()));
        assertEquals(400, invalidName.status());
        assertEquals("invalid_dataset_name", invalidName.body().path("code").asText());
        Resp missingName = post("/v1/catalog/datasets", Map.of("file", csv.toString()));
        assertEquals(400, missingName.status());
        assertEquals("invalid_dataset_name", missingName.body().path("code").asText());
        assertEquals(400, post("/v1/catalog/datasets", Map.of("name", "nofile")).status());
        assertEquals(400, post("/v1/catalog/datasets",
                Map.of("name", "two", "file", csv.toString(), "dataset", entry.path("path").asText()))
                .status());
        assertEquals(400, post("/v1/catalog/datasets",
                Map.of("name", "badformat", "file", csv.toString(), "format", "xml")).status());
        assertEquals(400, post("/v1/catalog/datasets",
                Map.of("name", "badtags", "file", csv.toString(), "tags", "not-an-array")).status());
        Path denied = Path.of(System.getProperty("user.home"), ".ssh", "id_ed25519");
        Resp deniedSource = post("/v1/catalog/datasets",
                Map.of("name", "keys", "file", denied.toString()));
        assertEquals(400, deniedSource.status());
        assertEquals("path_not_allowed", deniedSource.body().path("code").asText());
        assertTrue(deniedSource.body().path("hint").asText().contains("DAP_ALLOWED_ROOTS"));

        Resp listed = get("/v1/catalog/datasets");
        assertEquals(200, listed.status());
        assertEquals(1, listed.body().path("count").asInt());
        assertEquals("people", listed.body().path("datasets").get(0).path("name").asText());
        assertFalse(listed.body().path("datasets").get(0).has("history"),
                "the listing stays compact");

        Resp fetched = get("/v1/catalog/datasets/people");
        assertEquals(200, fetched.status());
        assertEquals("people", fetched.body().path("name").asText());
        assertEquals(0, fetched.body().path("history").size());

        // An already ingested Parquet dataset is accepted through the dataset field.
        Resp fromParquet = post("/v1/catalog/datasets", Map.of("name", "people-parquet",
                "dataset", entry.path("path").asText()));
        assertEquals(200, fromParquet.status(), fromParquet.raw().body());
        assertEquals("parquet", fromParquet.body().path("format").asText());
        assertEquals(3, fromParquet.body().path("rows").asInt());
        assertEquals(400, post("/v1/catalog/datasets", Map.of("name", "parquet-csv",
                "dataset", entry.path("path").asText(), "format", "csv")).status());

        // Profile: computed once, then served from the cache.
        Resp profile = get("/v1/catalog/datasets/people/profile");
        assertEquals(200, profile.status(), profile.raw().body());
        assertEquals("people", profile.body().path("name").asText());
        assertEquals(1, profile.body().path("version").asInt());
        assertEquals(3, profile.body().path("rows").asInt());
        assertEquals(3, profile.body().path("columns").size());
        assertEquals("age", profile.body().path("columns").get(1).path("name").asText());
        assertEquals("Int64", profile.body().path("columns").get(1).path("type").asText());
        assertEquals(28.0, profile.body().path("columns").get(1).path("min").asDouble());
        assertEquals(41.0, profile.body().path("columns").get(1).path("max").asDouble());
        assertEquals(1, profile.body().path("columns").get(2).path("missing").asInt());
        assertFalse(profile.body().path("cached").asBoolean());
        Resp cachedProfile = get("/v1/catalog/datasets/people/profile");
        assertTrue(cachedProfile.body().path("cached").asBoolean());
        Resp forcedProfile = get("/v1/catalog/datasets/people/profile?refresh=true");
        assertFalse(forcedProfile.body().path("cached").asBoolean());
        assertEquals(profile.body().path("columns"), forcedProfile.body().path("columns"));
        assertTrue(get("/v1/catalog/datasets/people").body().has("profile"),
                "the computed profile is cached in the entry");

        Resp patched = patch("/v1/catalog/datasets/people",
                Map.of("description", "updated", "tags", List.of("x"), "owner", "bob"));
        assertEquals(200, patched.status(), patched.raw().body());
        assertEquals("updated", patched.body().path("description").asText());
        assertEquals("bob", patched.body().path("owner").asText());
        assertEquals(1, patched.body().path("tags").size());
        assertEquals(1, patched.body().path("version").asInt());
        assertEquals(400, patch("/v1/catalog/datasets/people", Map.of("tags", "not-an-array")).status());

        Resp refreshed = post("/v1/catalog/datasets/people/refresh", Map.of());
        assertEquals(200, refreshed.status(), refreshed.raw().body());
        assertEquals(2, refreshed.body().path("version").asInt());
        assertEquals("updated", refreshed.body().path("description").asText(),
                "refresh keeps the metadata");
        assertEquals(1, refreshed.body().path("history").size());
        assertEquals(1, refreshed.body().path("history").get(0).path("version").asInt());
        assertEquals(2, get("/v1/catalog/datasets/people/profile").body().path("version").asInt());

        Resp unknown = get("/v1/catalog/datasets/nope");
        assertEquals(404, unknown.status());
        assertEquals("dataset_not_found", unknown.body().path("code").asText());
        assertTrue(unknown.body().path("hint").asText().contains("/v1/catalog/datasets"));
        assertEquals(404, get("/v1/catalog/datasets/nope/profile").status());
        assertEquals(404, patch("/v1/catalog/datasets/nope", Map.of("description", "x")).status());
        assertEquals(404, post("/v1/catalog/datasets/nope/refresh", Map.of()).status());
        assertEquals(404, delete("/v1/catalog/datasets/nope").status());

        Resp removed = delete("/v1/catalog/datasets/people");
        assertEquals(200, removed.status());
        assertTrue(removed.body().path("ok").asBoolean());
        assertEquals("people", removed.body().path("name").asText());
        assertEquals(404, get("/v1/catalog/datasets/people").status());
        assertEquals(1, get("/v1/catalog/datasets").body().path("count").asInt());
    }

    @Test
    void datasetCatalogSurvivesARestart() throws Exception {
        Path datasetsFile = tempDir.resolve("catalog-restart").resolve("datasets.json");
        startCatalogService(datasetsFile);
        Path csv = csvFile();
        Resp added = post("/v1/catalog/datasets",
                Map.of("name", "people", "file", csv.toString(), "description", "demo"));
        assertEquals(200, added.status(), added.raw().body());
        assertEquals(200, post("/v1/catalog/datasets/people/refresh", Map.of()).status());
        assertFalse(get("/v1/catalog/datasets/people/profile").body().path("cached").asBoolean());

        // A new engine process over the same file sees the entry, its history and the cache.
        DatasetCatalog reloaded = new DatasetCatalog(datasetsFile,
                new IngestService(tempDir.resolve("catalog-cache")));
        startServiceWithCatalog(reloaded);

        Resp listed = get("/v1/catalog/datasets");
        assertEquals(200, listed.status(), listed.raw().body());
        assertEquals(1, listed.body().path("count").asInt());
        Resp fetched = get("/v1/catalog/datasets/people");
        assertEquals("people", fetched.body().path("name").asText());
        assertEquals("demo", fetched.body().path("description").asText());
        assertEquals(2, fetched.body().path("version").asInt());
        assertEquals(1, fetched.body().path("history").size());
        assertTrue(get("/v1/catalog/datasets/people/profile").body().path("cached").asBoolean(),
                "the profile cache must survive a restart");
    }

    @Test
    void datasetCatalogCorruptFileFailsWithAHint() throws Exception {
        Path datasetsFile = tempDir.resolve("broken-catalog").resolve("datasets.json");
        Files.createDirectories(datasetsFile.getParent());
        Files.writeString(datasetsFile, "{not json", StandardCharsets.UTF_8);
        startCatalogService(datasetsFile);

        Resp listed = get("/v1/catalog/datasets");

        assertEquals(500, listed.status(), listed.raw().body());
        assertEquals("datasets_corrupt", listed.body().path("code").asText());
        assertTrue(listed.body().path("error").asText().contains("datasets.json"));
        assertTrue(listed.body().path("hint").asText().contains("datasets.json"));
    }

    @Test
    void exploreProfilesIrisAndIsStableAcrossCalls() throws Exception {
        Path csv = Path.of("..", "tests", "goldens-native", "data", "iris.csv")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(csv), "fixture missing: " + csv);

        Resp first = post("/v1/explore", Map.of("file", csv.toString(), "format", "csv"));
        assertEquals(200, first.status(), first.raw().body());
        JsonNode body = first.body();
        assertEquals(csv.toString(), body.path("source").asText());
        assertEquals(150, body.path("rows").asInt());
        assertEquals(5, body.path("columns").size());
        JsonNode species = column(body, "species");
        assertEquals("Utf8", species.path("type").asText());
        assertEquals(3, species.path("distinct").asInt());
        assertEquals(0, species.path("missing").asInt());
        assertEquals(43, column(body, "petal_length").path("distinct").asInt());
        int score = body.path("quality").path("score").asInt();
        assertTrue(score >= 0 && score <= 100, "quality score out of range: " + score);
        assertTrue(body.path("quality").path("issues").isArray());

        JsonNode petals = findPair(body.path("relationships"), "petal_length", "petal_width");
        assertNotNull(petals, "petal relationship missing: " + body.path("relationships"));
        assertEquals("pearson", petals.path("method").asText());
        assertTrue(petals.path("value").asDouble() > 0.9);
        assertTrue(petals.path("p_value").asDouble() < 1e-10);
        assertEquals(150, petals.path("n").asInt());
        assertEquals("very strong", petals.path("strength").asText());

        JsonNode anova = findPair(body.path("group_differences"), "petal_length", "species");
        assertNotNull(anova, "species difference missing: " + body.path("group_differences"));
        assertEquals("anova", anova.path("test").asText());
        assertTrue(anova.path("p_value").asDouble() < 1e-6);

        JsonNode hypotheses = body.path("hypotheses");
        assertTrue(hypotheses.size() >= 3 && hypotheses.size() <= 5,
                "expected 3 to 5 hypotheses, got " + hypotheses);
        for (JsonNode hypothesis : hypotheses) {
            assertFalse(hypothesis.path("id").asText().isBlank());
            assertFalse(hypothesis.path("question").asText().isBlank());
            assertFalse(hypothesis.path("test").asText().isBlank());
            assertTrue(hypothesis.path("columns").size() > 0);
            assertFalse(hypothesis.path("rationale").asText().isBlank());
            assertTrue(Set.of("high", "medium", "low").contains(hypothesis.path("priority").asText()));
        }
        assertTrue(body.path("recommendations").size() > 0);
        assertTrue(body.path("time").isMissingNode(), "iris has no date column");

        Resp second = post("/v1/explore", Map.of("file", csv.toString(), "format", "csv"));
        assertEquals(200, second.status(), second.raw().body());
        assertEquals(hypotheses, second.body().path("hypotheses"),
                "hypotheses must be stable across calls");
        assertEquals(body.path("relationships"), second.body().path("relationships"));
    }

    @Test
    void exploreAcceptsAnIngestedDatasetPath() throws Exception {
        Path csv = Path.of("..", "tests", "goldens-native", "data", "iris.csv")
                .toAbsolutePath().normalize();
        Resp ingested = post("/v1/datasets", Map.of("file", csv.toString(), "format", "csv"));
        assertEquals(200, ingested.status(), ingested.raw().body());
        String parquet = ingested.body().path("path").asText();

        Resp explored = post("/v1/explore", Map.of("dataset", parquet));
        assertEquals(200, explored.status(), explored.raw().body());
        assertEquals(parquet, explored.body().path("source").asText());
        assertEquals(150, explored.body().path("rows").asInt());

        Resp sql = post("/v1/explore", Map.of("dataset", parquet,
                "sql", "SELECT petal_length * 2 AS pl2, species FROM src"));
        assertEquals(200, sql.status(), sql.raw().body());
        assertEquals(2, sql.body().path("columns").size());
        assertEquals("pl2", sql.body().path("columns").get(0).path("name").asText());
    }

    @Test
    void exploreRejectsBothOrNeitherSource() throws Exception {
        Path csv = Path.of("..", "tests", "goldens-native", "data", "iris.csv")
                .toAbsolutePath().normalize();

        Resp both = post("/v1/explore", Map.of("file", csv.toString(), "dataset", csv.toString()));
        assertEquals(400, both.status(), both.raw().body());

        Resp neither = post("/v1/explore", Map.of());
        assertEquals(400, neither.status(), neither.raw().body());

        Resp badFormat = post("/v1/explore", Map.of("file", csv.toString(), "format", "xml"));
        assertEquals(400, badFormat.status(), badFormat.raw().body());
    }

    @Test
    void exploreReportsMissingDatasetAndInvalidSql() throws Exception {
        Resp missing = post("/v1/explore", Map.of("dataset", tempDir.resolve("absent.parquet").toString()));
        assertEquals(404, missing.status(), missing.raw().body());
        assertEquals("dataset_not_found", missing.body().path("code").asText());

        Path csv = Path.of("..", "tests", "goldens-native", "data", "iris.csv")
                .toAbsolutePath().normalize();
        Resp invalidSql = post("/v1/explore", Map.of("file", csv.toString(),
                "sql", "DROP TABLE src"));
        assertEquals(400, invalidSql.status(), invalidSql.raw().body());
        assertEquals("invalid_sql", invalidSql.body().path("code").asText());
    }

    private static JsonNode findPair(JsonNode array, String left, String right) {
        for (JsonNode pair : array) {
            if (left.equals(pair.path("left").asText())
                    && right.equals(pair.path("right").asText())) {
                return pair;
            }
            if (left.equals(pair.path("value").asText())
                    && right.equals(pair.path("group").asText())) {
                return pair;
            }
        }
        return null;
    }

    private JsonNode awaitJobState(String jobId, Set<String> states, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            Resp response = get("/v1/jobs/" + jobId);
            assertEquals(200, response.status());
            last = response.body();
            if (states.contains(last.path("state").asText())) {
                return last;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job " + jobId + " did not reach " + states + "; last=" + last);
    }

    private HttpRequest.Builder request(String method, String path, String sessionHeader, Object body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (sessionHeader != null) {
            builder.header("X-DAP-Session", sessionHeader);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(
                    body instanceof String text ? text : Json.write(body)));
        }
        return builder;
    }

    private Resp get(String path) throws Exception {
        return send("GET", path, session, null);
    }

    private Resp post(String path, Object body) throws Exception {
        return send("POST", path, session, body);
    }

    private Resp patch(String path, Object body) throws Exception {
        return send("PATCH", path, session, body);
    }

    private Resp delete(String path) throws Exception {
        return send("DELETE", path, session, null);
    }

    private Resp send(String method, String path, String sessionHeader, Object body) throws Exception {
        return send(method, path, sessionHeader, body, FAST);
    }

    private Resp send(String method, String path, String sessionHeader, Object body, Duration timeout)
            throws Exception {
        HttpRequest httpRequest = request(method, path, sessionHeader, body).timeout(timeout).build();
        HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        JsonNode parsed;
        String raw = response.body();
        if (raw == null || raw.isBlank()) {
            parsed = Json.object();
        } else {
            try {
                parsed = Json.MAPPER.readTree(raw);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                parsed = Json.object();
            }
        }
        return new Resp(response.statusCode(), parsed, response);
    }

    private record Resp(int status, JsonNode body, HttpResponse<String> raw) {
        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        String error() {
            return body.path("error").asText();
        }
    }
}