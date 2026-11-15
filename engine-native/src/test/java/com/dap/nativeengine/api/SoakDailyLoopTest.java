package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soak: 200 daily-loop iterations (create -> add -> run -> table -> delete) must leave one
 * session, a capped job history and a bounded heap. Tagged soak: run it with
 * {@code make soak} ({@code ./mvnw -q -Dgroups=soak test}); the normal suite skips it.
 */
@Tag("soak")
class SoakDailyLoopTest {
    private static final int ITERATIONS = 200;
    private static final int WARMUP = 10;
    private static final long MAX_HEAP_DELTA_MB = 300;
    private static final Duration FAST = Duration.ofSeconds(15);

    @TempDir
    Path tempDir;

    @Test
    void dailyLoopsKeepSessionsJobsAndHeapBounded() throws Exception {
        NativeService service = new NativeService(
                new NativeService.Settings(100, 600, "127.0.0.1", 0),
                new NodeRegistry(), NativeService.defaultExecutors());
        service.start(0);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String base = "http://127.0.0.1:" + service.port();
        String session = "soak-" + UUID.randomUUID();
        Path csv = tempDir.resolve("soak.csv");
        Files.writeString(csv, "name,age\nalice,34\nbob,28\ncarol,41\n", StandardCharsets.UTF_8);
        try {
            for (int index = 0; index < WARMUP; index++) {
                dailyLoop(base, client, session, csv, index);
            }
            System.gc();
            Thread.sleep(200);
            long before = usedHeapBytes();

            for (int index = WARMUP; index < ITERATIONS; index++) {
                dailyLoop(base, client, session, csv, index);
            }

            System.gc();
            Thread.sleep(200);
            long after = usedHeapBytes();

            JsonNode health = get(base, client, session, "/v1/health").body();
            assertEquals(1, health.path("sessions").asInt(),
                    "one session database must survive the loop: " + health);
            int jobs = get(base, client, session, "/v1/jobs").body().path("count").asInt();
            assertTrue(jobs <= 200, "job history must stay capped, got " + jobs);
            long deltaMb = Math.max(0L, after - before) / (1024 * 1024);
            assertTrue(deltaMb < MAX_HEAP_DELTA_MB,
                    "heap delta after " + ITERATIONS + " loops is " + deltaMb + " MB");
        } finally {
            service.close();
        }
    }

    private void dailyLoop(String base, HttpClient client, String session, Path csv, int index)
            throws Exception {
        Resp created = send(base, client, "POST", "/v1/workflows", session,
                Map.of("name", "soak-" + index));
        assertEquals(200, created.status(), created.raw().body());
        String workflow = created.body().path("id").asText();

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("factory", "dap.csv_reader");
        node.put("settings", Map.of("file", csv.toString(), "columns", List.of(
                Map.of("name", "name", "type", "Utf8"),
                Map.of("name", "age", "type", "Int64"))));
        Resp added = send(base, client, "POST", "/v1/workflows/" + workflow + "/nodes",
                session, node);
        assertEquals(200, added.status(), added.raw().body());
        String nodeId = added.body().path("node").asText();
        assertEquals(200, send(base, client, "POST", "/v1/workflows/" + workflow + "/run",
                session, Map.of()).status());
        Resp table = send(base, client, "GET", "/v1/workflows/" + workflow
                + "/nodes/" + nodeId + "/table", session, null);
        assertEquals(200, table.status(), table.raw().body());
        assertEquals(3, table.body().path("rows").asInt());
        assertEquals(200, send(base, client, "DELETE", "/v1/workflows/" + workflow,
                session, null).status());
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static Resp get(String base, HttpClient client, String session, String path)
            throws Exception {
        return send(base, client, "GET", path, session, null);
    }

    private static Resp send(String base, HttpClient client, String method, String path,
                             String session, Object body) throws Exception {
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
