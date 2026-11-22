package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.exec.Job;
import com.dap.nativeengine.exec.JobState;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the HTTP hardening defects: unbounded /table limit, substring-based
 * where denylist, stale session TTL, leaked job state on close, 500 for client errors raised
 * by a node, and the missing code in timeout/failed job views.
 *
 * <p>Follows the NativeServiceTest pattern: real Javalin on a random port, no shared state.
 */
class ApiHardeningTest {
    private static final Duration FAST = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    private final List<NativeService> extras = new ArrayList<>();
    private NativeService service;
    private HttpClient client;
    private String base;
    private String session;

    @BeforeEach
    void startServer() throws Exception {
        service = startService(new NativeService.Settings(100, 600, "127.0.0.1", 0), null);
        base = "http://127.0.0.1:" + service.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        session = "hardening-" + UUID.randomUUID();
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

    // ------------------------------------------------------------ 1: limit cap

    @Test
    void tableLimitAboveMaxIsRejectedWithCodeAndHint() throws Exception {
        String table = materializedTable();

        Resp rejected = get(table + "/table?limit=10001");

        assertEquals(400, rejected.status(), rejected.raw().body());
        assertEquals("limit_too_large", rejected.body().path("code").asText(), rejected.raw().body());
        assertFalse(rejected.body().path("hint").asText().isBlank(), rejected.raw().body());
        assertEquals(10_000, rejected.body().path("limit").asInt(), rejected.raw().body());
    }

    @Test
    void tableLimitAtMaxIsAccepted() throws Exception {
        String table = materializedTable();

        Resp accepted = get(table + "/table?limit=10000");

        assertEquals(200, accepted.status(), accepted.raw().body());
        assertEquals(2, accepted.body().path("rows").asInt(), accepted.raw().body());
    }

    // ------------------------------------------------------------ 2: where parser

    @Test
    void whereAllowsPredicatesContainingDenylistedWords() throws Exception {
        String table = materializedTable();
        for (String predicate : List.of("from_date > 0", "status = 'selected'",
                "title LIKE '%from%'", "amount >= 5 AND status <> 'selected'")) {
            Resp response = get(table + "/table?where=" + encode(predicate));
            assertEquals(200, response.status(), predicate + ": " + response.raw().body());
            assertTrue(response.body().path("rows").asInt() > 0, predicate + ": " + response.raw().body());
        }
    }

    @Test
    void whereRejectsStatementsCommentsAndFileReads() throws Exception {
        String table = materializedTable();
        for (String predicate : List.of("amount > 0; DROP TABLE x", "amount > 0 -- comment",
                "amount > 0 /* comment */", "read_csv('/etc/passwd')",
                "ATTACH '/tmp/x' AS x", "COPY x TO '/tmp/x'", "PRAGMA version()",
                "glob('/etc/*')", "amount > 0 AND parquet_scan('/etc/passwd') IS NULL")) {
            Resp response = get(table + "/table?where=" + encode(predicate));
            assertEquals(400, response.status(), predicate + ": " + response.raw().body());
            assertEquals("invalid_filter", response.body().path("code").asText(),
                    predicate + ": " + response.raw().body());
        }
    }

    // ------------------------------------------------------------ 3: session TTL

    @Test
    void dataRequestsRefreshTheSessionTtl() throws Exception {
        NativeService.Settings ttl = new NativeService.Settings(100, 600, "127.0.0.1", 0,
                null, null, null, 900, 60, 64, 2);
        NativeService ttlService = startService(ttl, null);
        String ttlBase = "http://127.0.0.1:" + ttlService.port();
        String ttlSession = "ttl-" + UUID.randomUUID();
        String table = materializedTable(ttlBase, ttlSession);

        for (String suffix : List.of("/table", "/profile", "/estimate")) {
            Thread.sleep(1500);
            Resp response = get(ttlBase, ttlSession, table + suffix);
            assertEquals(200, response.status(), suffix + ": " + response.raw().body());
            Resp sessions = get(ttlBase, ttlSession, "/v1/sessions");
            assertEquals(1, sessions.body().path("count").asInt(),
                    "the session must stay alive while data routes are used: " + suffix
                            + "; " + sessions.raw().body());
        }
    }

    // ------------------------------------------------------------ 4: close cleanup

    @Test
    void closingASessionForgetsItsJobsAndWorkflows() throws Exception {
        String workflow = createWorkflow();
        addCsvNode(workflow);
        Resp submitted = post("/v1/workflows/" + workflow + "/jobs", Map.of());
        assertEquals(202, submitted.status(), submitted.raw().body());
        String jobId = submitted.body().path("id").asText();
        awaitJobState(jobId, "succeeded");
        assertEquals(1, get("/v1/jobs").body().path("count").asInt());

        Resp closed = delete("/v1/sessions/" + session);
        assertEquals(200, closed.status(), closed.raw().body());

        Resp jobs = get("/v1/jobs");
        assertEquals(0, jobs.body().path("count").asInt(), jobs.raw().body());
        assertEquals(0, jobs.body().path("jobs").size(), jobs.raw().body());
        assertEquals(0, get("/v1/workflows").body().path("workflows").size());
        assertEquals(404, get("/v1/jobs/" + jobId).status());
        assertEquals(404, delete("/v1/sessions/" + session).status());

        String second = createWorkflow();
        addCsvNode(second);
        Resp resubmitted = post("/v1/workflows/" + second + "/jobs", Map.of());
        assertEquals(202, resubmitted.status(), resubmitted.raw().body());
        awaitJobState(resubmitted.body().path("id").asText(), "succeeded");
        assertEquals(1, get("/v1/jobs").body().path("count").asInt(),
                "the old job must not be counted again");
    }

    @Test
    void deletingAWorkflowForgetsItsJobs() throws Exception {
        String workflow = createWorkflow();
        addCsvNode(workflow);
        Resp submitted = post("/v1/workflows/" + workflow + "/jobs", Map.of());
        assertEquals(202, submitted.status(), submitted.raw().body());
        awaitJobState(submitted.body().path("id").asText(), "succeeded");

        assertEquals(200, delete("/v1/workflows/" + workflow).status());
        assertEquals(0, get("/v1/jobs").body().path("count").asInt());
    }

    // ------------------------------------------------------------ 5: client errors

    @Test
    void deniedPathInANodeIsAClientError() throws Exception {
        String workflow = createWorkflow();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("factory", "dap.excel_reader");
        node.put("settings", Map.of("file", Path.of(System.getProperty("user.home"),
                ".ssh", "secret.xlsx").toString()));
        Resp added = post("/v1/workflows/" + workflow + "/nodes", node);
        assertEquals(200, added.status(), added.raw().body());

        Resp run = post("/v1/workflows/" + workflow + "/run", Map.of());

        assertEquals(400, run.status(), run.raw().body());
        assertEquals("path_not_allowed", run.body().path("code").asText(), run.raw().body());
        assertFalse(run.body().path("hint").asText().isBlank(), run.raw().body());
    }

    // ------------------------------------------------------------ 6: job-view codes

    @Test
    void jobViewCarriesCodeForTimeoutAndFailedRuns() {
        Job timeout = new Job("job-1", "wf-1", "s", 1);
        timeout.complete(JobState.timeout, null, "timeout");
        assertEquals("query_timeout", JobHandlers.view(timeout, false).get("code"));

        Job failed = new Job("job-2", "wf-1", "s", 1);
        failed.complete(JobState.failed, null, "reader failed");
        assertEquals("node_failed", JobHandlers.view(failed, false).get("code"));
    }

    // -------------------------------------------------------------- helpers

    private String materializedTable() throws Exception {
        return materializedTable(base, session);
    }

    private String materializedTable(String serviceBase, String serviceSession) throws Exception {
        Resp created = post(serviceBase, serviceSession, "/v1/workflows", Map.of("name", "wf"));
        assertEquals(200, created.status(), created.raw().body());
        String workflow = created.body().path("id").asText();
        String node = addCsvNode(serviceBase, serviceSession, workflow);
        Resp run = post(serviceBase, serviceSession, "/v1/workflows/" + workflow + "/run", Map.of());
        assertEquals(200, run.status(), run.raw().body());
        return "/v1/workflows/" + workflow + "/nodes/" + node;
    }

    private String createWorkflow() throws Exception {
        Resp created = post("/v1/workflows", Map.of("name", "wf"));
        assertEquals(200, created.status(), created.raw().body());
        return created.body().path("id").asText();
    }

    private void addCsvNode(String workflow) throws Exception {
        addCsvNode(base, session, workflow);
    }

    private String addCsvNode(String serviceBase, String serviceSession, String workflow) throws Exception {
        Path file = tempDir.resolve("filter.csv");
        if (!Files.isRegularFile(file)) {
            Files.writeString(file,
                    "from_date,status,title,amount\n1,selected,from the start,5\n"
                            + "2,pending,selected item,7\n", StandardCharsets.UTF_8);
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("file", file.toString());
        settings.put("columns", List.of(
                Map.of("name", "from_date", "type", "Int64"),
                Map.of("name", "status", "type", "Utf8"),
                Map.of("name", "title", "type", "Utf8"),
                Map.of("name", "amount", "type", "Int64")));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("factory", "dap.csv_reader");
        node.put("settings", settings);
        Resp added = post(serviceBase, serviceSession, "/v1/workflows/" + workflow + "/nodes", node);
        assertEquals(200, added.status(), added.raw().body());
        return added.body().path("node").asText();
    }

    private void awaitJobState(String jobId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            Resp response = get("/v1/jobs/" + jobId);
            assertEquals(200, response.status());
            last = response.body();
            if (expected.equals(last.path("state").asText())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job " + jobId + " did not reach " + expected + "; last=" + last);
    }

    private NativeService startService(NativeService.Settings settings, NodeRegistry registry) throws Exception {
        NativeService started = new NativeService(settings,
                registry == null ? new NodeRegistry() : registry, NativeService.defaultExecutors());
        started.start(0);
        extras.add(started);
        return started;
    }

    private Resp get(String path) throws Exception {
        return send("GET", path, null);
    }

    private Resp get(String serviceBase, String serviceSession, String path) throws Exception {
        return send(serviceBase, serviceSession, "GET", path, null);
    }

    private Resp post(String path, Object body) throws Exception {
        return send("POST", path, body);
    }

    private Resp post(String serviceBase, String serviceSession, String path, Object body) throws Exception {
        return send(serviceBase, serviceSession, "POST", path, body);
    }

    private Resp delete(String path) throws Exception {
        return send("DELETE", path, null);
    }

    private Resp send(String method, String path, Object body) throws Exception {
        return send(base, session, method, path, body);
    }

    private Resp send(String serviceBase, String serviceSession, String method, String path, Object body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(serviceBase + path))
                .header("X-DAP-Session", serviceSession)
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

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Resp(int status, JsonNode body, HttpResponse<String> raw) {
    }
}
