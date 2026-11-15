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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for the additive error envelope: every error body carries a
 * non-blank {@code error} and a machine-readable {@code code}; actionable errors
 * also carry a {@code hint}. Follows the NativeServiceTest pattern (real Javalin
 * on a random port, no shared state).
 */
class ContractErrorTest {
    private static final Duration FAST = Duration.ofSeconds(5);

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
        session = "contract-" + UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Test
    void everyErrorBodyHasCode() throws Exception {
        // 400 unknown setting, 404 unknown workflow, 409 cancel of a finished job, 500 missing file
        Resp badSetting = badSetting();
        Resp unknownWorkflow = get("/v1/workflows/wf-999999/nodes");
        Resp cancelFinished = cancelFinishedJob();
        Resp missingFile = runWithMissingFile();

        assertEquals(400, badSetting.status(), badSetting.raw().body());
        assertEquals(404, unknownWorkflow.status(), unknownWorkflow.raw().body());
        assertEquals(409, cancelFinished.status(), cancelFinished.raw().body());
        assertEquals(500, missingFile.status(), missingFile.raw().body());

        for (Resp response : List.of(badSetting, unknownWorkflow, cancelFinished, missingFile)) {
            assertTrue(nonBlank(response.body(), "error"),
                    "error must be present: " + response.raw().body());
            assertTrue(nonBlank(response.body(), "code"),
                    "code must be present: " + response.raw().body());
        }
        assertNotNull(badSetting.body().get("hint"), badSetting.raw().body());
        assertFalse(badSetting.body().path("hint").asText().isBlank(), badSetting.raw().body());
    }

    // ---------------------------------------------------------------- cases

    private Resp badSetting() throws Exception {
        String workflow = createWorkflow("bad-setting");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factory", "dap.sorter");
        body.put("settings", Map.of("nope", 1));
        return post("/v1/workflows/" + workflow + "/nodes", body);
    }

    private Resp cancelFinishedJob() throws Exception {
        String workflow = createWorkflow("cancel-finished");
        addNode(workflow, "dap.csv_reader", csvSettings(csvFile()));
        Resp submitted = post("/v1/workflows/" + workflow + "/jobs", Map.of());
        assertEquals(202, submitted.status(), submitted.raw().body());
        String jobId = submitted.body().path("id").asText();
        awaitJobState(jobId, "succeeded");
        return delete("/v1/jobs/" + jobId);
    }

    private Resp runWithMissingFile() throws Exception {
        String workflow = createWorkflow("missing-file");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("file", tempDir.resolve("does-not-exist.csv").toString());
        settings.put("columns", List.of(Map.of("name", "a", "type", "Int64")));
        addNode(workflow, "dap.csv_reader", settings);
        return post("/v1/workflows/" + workflow + "/run", Map.of());
    }

    // -------------------------------------------------------------- helpers

    private static boolean nonBlank(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank();
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
