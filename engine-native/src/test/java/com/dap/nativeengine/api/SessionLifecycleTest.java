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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session lifecycle: the endpoints, the LRU cap and TTL eviction. Eviction cancels the
 * session jobs, deletes its workflows and closes its DuckDB database.
 */
class SessionLifecycleTest {
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
        service = startService(settings(64, 1800));
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        session = "sess-" + UUID.randomUUID();
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
    void deletingASessionClosesItsDatabaseAndWorkflows() throws Exception {
        String first = newSession();
        String second = newSession();
        String third = newSession();
        Map<String, String> workflows = new LinkedHashMap<>();
        for (String id : List.of(first, second, third)) {
            workflows.put(id, runWorkflowIn(id));
        }

        Resp listed = send("GET", "/v1/sessions", session, null);
        assertEquals(200, listed.status(), listed.raw().body());
        assertEquals(3, listed.body().path("count").asInt(), listed.raw().body());
        assertEquals(3, listed.body().path("sessions").size(), listed.raw().body());
        JsonNode entry = entry(listed.body(), first);
        assertTrue(entry.path("lastAccess").asLong() > 0, listed.raw().body());
        assertEquals(1, entry.path("workflows").asInt(), listed.raw().body());

        Resp deleted = send("DELETE", "/v1/sessions/" + first, session, null);
        assertEquals(200, deleted.status(), deleted.raw().body());
        assertTrue(deleted.body().path("ok").asBoolean(), deleted.raw().body());
        assertEquals(first, deleted.body().path("id").asText(), deleted.raw().body());

        assertEquals(404, send("GET", "/v1/workflows/" + workflows.get(first) + "/nodes",
                first, null).status(), "the evicted session workflows must be gone");
        assertEquals(200, send("GET", "/v1/workflows/" + workflows.get(second) + "/nodes",
                second, null).status(), "other sessions must be untouched");
        assertEquals(2, send("GET", "/v1/health", session, null)
                .body().path("sessions").asInt());
        assertEquals(404, send("DELETE", "/v1/sessions/" + first, session, null).status());
    }

    @Test
    void sessionCapEvictsTheLeastRecentlyUsed() throws Exception {
        NativeService capped = startService(settings(4, 1800));
        extras.add(capped);
        String previousBase = base;
        String previousSession = session;
        base = "http://127.0.0.1:" + capped.port();
        session = "cap-" + UUID.randomUUID();
        try {
            List<String> sessions = new ArrayList<>();
            Map<String, String> workflows = new LinkedHashMap<>();
            for (int index = 0; index < 6; index++) {
                String id = newSession();
                sessions.add(id);
                workflows.put(id, runWorkflowIn(id));
                Thread.sleep(5);
            }

            int open = send("GET", "/v1/health", session, null)
                    .body().path("sessions").asInt();
            assertTrue(open <= 4, "session cap must hold, got " + open);

            String newest = sessions.get(sessions.size() - 1);
            assertEquals(200, send("GET", "/v1/workflows/" + workflows.get(newest) + "/nodes",
                    newest, null).status(), "the newest session must survive");
            String oldest = sessions.get(0);
            assertEquals(404, send("GET", "/v1/workflows/" + workflows.get(oldest) + "/nodes",
                    oldest, null).status(), "the least recently used session must be evicted");
        } finally {
            base = previousBase;
            session = previousSession;
        }
    }

    @Test
    void ttlEvictionClosesIdleSessions() throws Exception {
        NativeService.Settings ttl = settings(64, 1);
        ServiceState state = new ServiceState(ttl, new NodeRegistry(),
                NativeService.defaultExecutors());
        String idle = "idle-" + UUID.randomUUID();
        try {
            DuckDbSession database = state.session(idle);
            assertNotNull(database);
            assertEquals(1, state.sessionCount());

            Thread.sleep(1200);
            state.evictIdle(System.currentTimeMillis());

            assertNull(state.sessionIfPresent(idle), "the idle session database must be closed");
            assertEquals(0, state.sessionCount(), "the idle session database must be closed");
        } finally {
            state.close();
        }
    }

    // --------------------------------------------------------------- helpers

    private static NativeService.Settings settings(int maxSessions, int sessionTtlSec) {
        return new NativeService.Settings(100, 600, "127.0.0.1", 0,
                null, null, null, 900, 60, maxSessions, sessionTtlSec);
    }

    private static JsonNode entry(JsonNode body, String id) {
        for (JsonNode entry : body.path("sessions")) {
            if (id.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        throw new AssertionError("session " + id + " missing from " + body);
    }

    private String newSession() {
        return "sess-" + UUID.randomUUID();
    }

    /** A workflow with one executed csv_reader: creating its session database is the point. */
    private String runWorkflowIn(String sessionId) throws Exception {
        Resp created = send("POST", "/v1/workflows", sessionId, Map.of("name", "wf"));
        assertEquals(200, created.status(), created.raw().body());
        String workflow = created.body().path("id").asText();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("factory", "dap.csv_reader");
        node.put("settings", csvSettings());
        assertEquals(200, send("POST", "/v1/workflows/" + workflow + "/nodes",
                sessionId, node).status());
        assertEquals(200, send("POST", "/v1/workflows/" + workflow + "/run",
                sessionId, Map.of()).status());
        return workflow;
    }

    private Map<String, Object> csvSettings() throws Exception {
        Path file = tempDir.resolve("people.csv");
        if (!Files.isRegularFile(file)) {
            Files.writeString(file, "name,age\nalice,34\nbob,28\n",
                    StandardCharsets.UTF_8);
        }
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("file", file.toString());
        settings.put("columns", List.of(
                Map.of("name", "name", "type", "Utf8"),
                Map.of("name", "age", "type", "Int64")));
        return settings;
    }

    private NativeService startService(NativeService.Settings settings) throws Exception {
        NativeService started = new NativeService(settings, new NodeRegistry(),
                NativeService.defaultExecutors());
        started.start(0);
        base = "http://127.0.0.1:" + started.port();
        return started;
    }

    private Resp send(String method, String path, String sessionId, Object body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-DAP-Session", sessionId)
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
