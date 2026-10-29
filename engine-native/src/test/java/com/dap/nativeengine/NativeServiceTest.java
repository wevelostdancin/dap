package com.dap.nativeengine;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.Executors;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
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
        assertEquals("1.0.0", meta.body().path("version").asText());
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
        assertEquals("1.0.0", body.path("version").asText());
        assertEquals("1", body.path("api").asText());
        assertEquals(10, body.path("nodes").asInt());
        assertEquals(10, body.path("coreNodes").asInt());
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
        assertEquals(10, list.body().path("count").asInt());

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
        assertEquals(500, get("/v1/nodes?limit=not-a-number").status());
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
    void addNodeValidationLeavesTheNodeAfterBadSettings() throws Exception {
        String workflow = createWorkflow("node-errors");

        Resp noFactory = post("/v1/workflows/" + workflow + "/nodes", Map.of());
        assertEquals(400, noFactory.status());
        assertEquals("'factory' is required", noFactory.error());

        Resp unknown = post("/v1/workflows/" + workflow + "/nodes", Map.of("factory", "dap.nope"));
        assertEquals(400, unknown.status());
        assertEquals("unknown node: dap.nope", unknown.error());

        Resp unavailable = post("/v1/workflows/" + workflow + "/nodes", Map.of("factory", "dap.group_by"));
        assertEquals(400, unavailable.status());
        assertEquals("node not available in native engine: dap.group_by", unavailable.error());
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

        // Frozen contract quirk: the node stays in the workflow after a settings 400,
        // so both rejected settings requests left one node behind.
        JsonNode nodes = get("/v1/workflows/" + workflow + "/nodes").body().path("nodes");
        assertEquals(2, nodes.size());
        assertEquals("Sorter", nodes.get(1).path("name").asText());
        assertEquals("IDLE", nodes.get(1).path("state").asText());
        String nodeId = nodes.get(1).path("node").asText();

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

        Resp patchOk = patch("/v1/workflows/" + workflow + "/nodes/" + nodeId,
                Map.of("settings", Map.of("keys", List.of(Map.of("column", "age", "direction", "asc")))));
        assertEquals(200, patchOk.status());
        assertEquals(nodeId, patchOk.body().path("node").asText());
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
        assertEquals(400, beforeRun.status());
        assertEquals("node has no table output", beforeRun.error());
        assertEquals(1, beforeRun.body().path("ports").size());
        assertEquals(0, beforeRun.body().path("ports").get(0).path("index").asInt());
        assertEquals("Data", beforeRun.body().path("ports").get(0).path("name").asText());
        assertEquals(400, get("/v1/workflows/" + workflow + "/nodes/999:9/table").status());
        Resp beforeProfile = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/profile");
        assertEquals(400, beforeProfile.status());
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
        assertEquals(400, get("/v1/workflows/" + workflow + "/nodes/999:9/profile").status());
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
            assertEquals(500, second.status());
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

    // --------------------------------------------------------------- helpers

    private NativeService startSlowService() throws Exception {
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
        NativeService slow = new NativeService(new NativeService.Settings(100, 600, "127.0.0.1", 0),
                registry, executors);
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