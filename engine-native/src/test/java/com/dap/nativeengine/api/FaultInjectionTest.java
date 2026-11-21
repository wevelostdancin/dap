package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault injection: every failure mode must produce a bounded, explainable response and
 * leave the engine healthy for the next call.
 */
class FaultInjectionTest {
    private static final Duration FAST = Duration.ofSeconds(10);
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
    void startServer() throws Exception {
        service = startService(new NativeService.Settings(100, 600, "127.0.0.1", 0));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        session = "fault-" + UUID.randomUUID();
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
    void cancelMidIngestLeavesTheEngineHealthy() throws Exception {
        Path big = tempDir.resolve("big.csv");
        StringBuilder rows = new StringBuilder("id\n");
        for (int row = 0; row < 1_000_000; row++) {
            rows.append(row).append('\n');
        }
        Files.writeString(big, rows.toString(), StandardCharsets.UTF_8);

        String workflow = createWorkflow("cancel-ingest");
        addNode(workflow, "dap.csv_reader", Map.of(
                "file", big.toString(),
                "columns", List.of(Map.of("name", "id", "type", "Int64"))));
        Resp submitted = post("/v1/workflows/" + workflow + "/jobs", Map.of());
        assertEquals(202, submitted.status(), submitted.raw().body());
        String jobId = submitted.body().path("id").asText();
        awaitJobState(jobId, Set.of("running"), Duration.ofSeconds(5));

        Resp cancelled = delete("/v1/jobs/" + jobId);
        assertEquals(200, cancelled.status(), cancelled.raw().body());
        awaitJobState(jobId, Set.of("cancelled", "failed", "timeout"), Duration.ofSeconds(15));

        assertEquals(200, get("/v1/health").status(), "the engine must stay healthy");
        String next = createWorkflow("after-cancel");
        assertEquals(0, get("/v1/workflows/" + next + "/nodes").body().path("nodes").size());
    }

    @Test
    void syncRunTimesOutOnASlowNode() throws Exception {
        startSlowService();
        String workflow = createWorkflow("timeout");
        addNode(workflow, "dap.test_sleep", Map.of("millis", 3000));

        long started = System.nanoTime();
        Resp response = post("/v1/workflows/" + workflow + "/run", Map.of("timeoutSec", 1));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(408, response.status(), response.raw().body());
        assertEquals("timeout", response.body().path("state").asText());
        assertTrue(elapsedMs < 5_000, "the sync run timeout must be quick, took " + elapsedMs + "ms");
        assertEquals(200, get("/v1/health").status());
    }

    @Test
    void missingFileFailsTheNodeWithItsMessage() throws Exception {
        String workflow = createWorkflow("missing-file");
        String reader = addNode(workflow, "dap.csv_reader", Map.of(
                "file", tempDir.resolve("nope.csv").toString(),
                "columns", List.of(Map.of("name", "a", "type", "Int64"))));

        Resp run = post("/v1/workflows/" + workflow + "/run", Map.of());
        assertEquals(500, run.status(), run.raw().body());
        assertEquals("failed", run.body().path("state").asText());
        assertEquals("node_failed", run.body().path("code").asText());

        Resp table = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table");
        assertEquals(409, table.status(), table.raw().body());
        assertEquals("node_failed", table.body().path("code").asText());
        assertTrue(table.body().path("error").asText().contains("file not found"),
                table.raw().body());
    }

    @Test
    void malformedCsvFailsWithTheDuckDbConversionMessage() throws Exception {
        Path broken = tempDir.resolve("broken.csv");
        Files.writeString(broken, "a\n1\nabc\n", StandardCharsets.UTF_8);
        String workflow = createWorkflow("malformed-csv");
        String reader = addNode(workflow, "dap.csv_reader", Map.of(
                "file", broken.toString(),
                "columns", List.of(Map.of("name", "a", "type", "Int64"))));

        // read_csv is lazy: the file is scanned by the first /table call, not by run.
        Resp run = post("/v1/workflows/" + workflow + "/run", Map.of());
        assertEquals(200, run.status(), run.raw().body());

        Resp table = get("/v1/workflows/" + workflow + "/nodes/" + reader + "/table");
        assertTrue(table.status() >= 400, table.raw().body());
        assertTrue(table.body().path("error").asText().contains("abc"), table.raw().body());
    }

    @Test
    void crossJoinIsBoundedByTheQueryTimeout() throws Exception {
        NativeService limited = startService(new NativeService.Settings(100, 600, "127.0.0.1", 0,
                null, null, null, 900, 1));
        extras.add(limited);
        String previousBase = base;
        String previousSession = session;
        base = "http://127.0.0.1:" + limited.port();
        session = "fault-timeout-" + UUID.randomUUID();
        try {
            Path left = tempDir.resolve("left.csv");
            Path right = tempDir.resolve("right.csv");
            StringBuilder rows = new StringBuilder("id\n");
            for (int row = 0; row < 60_000; row++) {
                rows.append(row).append('\n');
            }
            Files.writeString(left, rows.toString(), StandardCharsets.UTF_8);
            Files.writeString(right, rows.toString(), StandardCharsets.UTF_8);

            String workflow = createWorkflow("cross-join");
            String leftReader = addNode(workflow, "dap.csv_reader", Map.of(
                    "file", left.toString(),
                    "columns", List.of(Map.of("name", "id", "type", "Int64"))));
            String rightReader = addNode(workflow, "dap.csv_reader", Map.of(
                    "file", right.toString(),
                    "columns", List.of(Map.of("name", "id", "type", "Int64"))));
            String leftKey = mathKey(workflow, leftReader);
            String rightKey = mathKey(workflow, rightReader);
            String join = addNode(workflow, "dap.joiner", Map.of("how", "inner",
                    "left_keys", List.of("key"), "right_keys", List.of("key"), "merge_keys", true));
            connect(workflow, leftKey, join, 0, 0);
            connect(workflow, rightKey, join, 0, 1);
            assertEquals(200, post("/v1/workflows/" + workflow + "/run", Map.of()).status());

            long started = System.nanoTime();
            Resp table = get("/v1/workflows/" + workflow + "/nodes/" + join + "/table");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals(408, table.status(), table.raw().body());
            assertEquals("query_timeout", table.body().path("code").asText());
            assertTrue(elapsedMs < 10_000, "the bounded read took " + elapsedMs + "ms");

            assertEquals(200, get("/v1/workflows/" + workflow + "/nodes/" + leftReader
                    + "/table").status(), "the engine must stay healthy after a timeout");
        } finally {
            base = previousBase;
            session = previousSession;
        }
    }

    @Test
    void engineRestartLosesWorkflowsAndChangesTheGeneration() throws Exception {
        String workflow = createWorkflow("restart");
        String generation = get("/v1/health").body().path("generation").asText();
        assertTrue(!generation.isBlank(), "health must expose a generation");

        assertEquals(200, post("/v1/shutdown", Map.of()).status());
        service.awaitStop();
        service = null;

        NativeService restarted = startService(new NativeService.Settings(100, 600, "127.0.0.1", 0));
        extras.add(restarted);
        assertNotEquals(generation, get("/v1/health").body().path("generation").asText(),
                "a restart must produce a new generation");
        assertEquals(404, get("/v1/workflows/" + workflow + "/nodes").status(),
                "workflows do not survive a restart");
    }

    // --------------------------------------------------------------- helpers

    private String mathKey(String workflow, String reader) throws Exception {
        String key = addNode(workflow, "dap.math_formula",
                Map.of("outputs", List.of(Map.of("name", "key", "expression", "1"))));
        connect(workflow, reader, key, 0, 0);
        return key;
    }

    private NativeService startService(NativeService.Settings settings) throws Exception {
        NativeService started = new NativeService(settings, new NodeRegistry(),
                NativeService.defaultExecutors());
        started.start(0);
        base = "http://127.0.0.1:" + started.port();
        return started;
    }

    private NativeService startSlowService() throws Exception {
        Path root = Files.createTempDirectory("dap-fault-specs");
        Path specs = Files.createDirectories(root.resolve("specs"));
        String index = readResource("specs/index.txt");
        Files.writeString(specs.resolve("index.txt"), index + "\ndap.test_sleep.json\n",
                StandardCharsets.UTF_8);
        Files.writeString(specs.resolve("dap.test_sleep.json"), SLOW_SPEC, StandardCharsets.UTF_8);
        NodeRegistry registry = new NodeRegistry(
                new SpecsFirstLoader(root, NodeRegistry.class.getClassLoader()));
        Executors executors = NativeService.defaultExecutors().register(new NodeExecutor() {
            @Override
            public String nodeId() {
                return "dap.test_sleep";
            }

            @Override
            public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
                Thread.sleep(context.config().path("millis").asLong(1500L));
                return new LazyPlan(context.db(), "SELECT 1 AS x",
                        List.of(ColumnSpec.of("x", "Int64")));
            }
        });
        NativeService slow = new NativeService(new NativeService.Settings(100, 600, "127.0.0.1", 0),
                registry, executors);
        slow.start(0);
        extras.add(slow);
        base = "http://127.0.0.1:" + slow.port();
        return slow;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream stream = FaultInjectionTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull(stream, "missing classpath resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

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

    private void awaitJobState(String jobId, Set<String> expected, Duration timeout)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            Resp response = get("/v1/jobs/" + jobId);
            assertEquals(200, response.status());
            last = response.body();
            if (expected.contains(last.path("state").asText())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job " + jobId + " did not reach " + expected + "; last=" + last);
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
