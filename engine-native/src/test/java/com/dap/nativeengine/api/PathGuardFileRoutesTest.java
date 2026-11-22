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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workflow save/load file routes must obey the same PathGuard as file nodes. */
class PathGuardFileRoutesTest {
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
        session = "pathguard-" + UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Test
    void saveRejectsADeniedDirectoryWithoutCreatingIt() throws Exception {
        String workflow = createWorkflow("guard-save");
        Path denied = deniedDir("dap-save-");

        Resp rejected = post("/v1/workflows/" + workflow + "/save", Map.of("dir", denied.toString()));

        assertEquals(400, rejected.status(), rejected.raw().body());
        assertEquals("path_not_allowed", rejected.body().path("code").asText(), rejected.raw().body());
        assertTrue(rejected.body().path("hint").asText().contains("allowed roots"), rejected.raw().body());
        assertFalse(Files.exists(denied), "a rejected save must not create the directory: " + denied);
        assertFalse(Files.exists(denied.resolve("workflow.json")), "a rejected save must not write anything");
    }

    @Test
    void loadRejectsADeniedDirectoryBeforeProbingIt() throws Exception {
        Path denied = deniedDir("dap-load-");

        Resp rejected = post("/v1/workflows/load", Map.of("dir", denied.toString()));

        assertEquals(400, rejected.status(), rejected.raw().body());
        assertEquals("path_not_allowed", rejected.body().path("code").asText(), rejected.raw().body());
        assertEquals(0, get("/v1/workflows").body().path("workflows").size(),
                "a rejected load must not create a workflow");
    }

    @Test
    void saveAndLoadAnAllowedDirectoryStillWork() throws Exception {
        String workflow = createWorkflow("guard-ok");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("factory", "dap.sorter");
        node.put("settings", Map.of("keys", List.of()));
        assertEquals(200, post("/v1/workflows/" + workflow + "/nodes", node).status());
        Path dir = tempDir.resolve("saved-workflow");

        Resp saved = post("/v1/workflows/" + workflow + "/save", Map.of("dir", dir.toString()));
        assertEquals(200, saved.status(), saved.raw().body());
        assertTrue(Files.isRegularFile(dir.resolve("workflow.json")), "save must write workflow.json");

        Resp loaded = post("/v1/workflows/load", Map.of("dir", dir.toString()));
        assertEquals(200, loaded.status(), loaded.raw().body());
        assertEquals(1, loaded.body().path("nodes").asInt());
    }

    /** ~/.ssh is on PathGuard's built-in denylist, whatever the configured allowlist is. */
    private static Path deniedDir(String prefix) {
        return Path.of(System.getProperty("user.home"), ".ssh", prefix + UUID.randomUUID());
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
