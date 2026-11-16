package com.dap.nativeengine;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Startup warm-up: real CSV path, cleanup, one run per process, env/property switch. */
class WarmUpTest {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path tempDir;

    @Test
    void runsTheRealCsvPathAndLeavesNoTempFiles() throws Exception {
        NativeService.Settings settings = settings(tempDir);

        WarmUp.Outcome first = WarmUp.run(settings);
        assertTrue(first.elapsedMs() >= 0, "elapsed ms must be measured");
        assertEquals(3, first.rows(), "the warm-up CSV has three data rows");

        WarmUp.Outcome second = WarmUp.run(settings);
        assertEquals(first.rows(), second.rows(), "a repeated warm-up must be harmless");

        try (Stream<Path> files = Files.list(tempDir)) {
            List<String> leftovers = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("dap-warmup-"))
                    .toList();
            assertEquals(List.of(), leftovers, "warm-up must delete its temp CSV");
        }
    }

    @Test
    void claimsOnlyOneWarmUpPerProcess() {
        WarmUp.reset();
        assertTrue(WarmUp.claim(), "the first service warms the process up");
        assertTrue(WarmUp.claimed());
        assertFalse(WarmUp.claim(), "a second service must not repeat the warm-up");
        WarmUp.reset();
        assertFalse(WarmUp.claimed());
    }

    @Test
    void disabledByEnvironment() {
        assertTrue(WarmUp.enabled(Map.of()), "warm-up is on by default");
        assertTrue(WarmUp.enabled(Map.of(WarmUp.ENV, "1")));
        assertFalse(WarmUp.enabled(Map.of(WarmUp.ENV, "0")));
        assertFalse(WarmUp.enabled(Map.of(WarmUp.ENV, " 0 ")));
    }

    @Test
    void disabledBySystemProperty() {
        System.setProperty(WarmUp.PROPERTY, "0");
        try {
            assertFalse(WarmUp.enabled(), "the system property must win over the environment");
        } finally {
            System.clearProperty(WarmUp.PROPERTY);
        }
        assertTrue(WarmUp.enabled());
    }

    @Test
    void serviceAnswersHealthBeforeWarmUpAndRegistersNoSession() throws Exception {
        WarmUp.reset();
        NativeService service = new NativeService(settings(tempDir), new NodeRegistry(),
                NativeService.defaultExecutors());
        try {
            service.start(0);
            JsonNode health = health(service.port());
            assertTrue(health.path("ok").asBoolean());
            assertEquals(0, health.path("sessions").asInt());

            service.warmUpDone().get(30, TimeUnit.SECONDS);

            health = health(service.port());
            assertTrue(health.path("ok").asBoolean());
            assertEquals(0, health.path("sessions").asInt(),
                    "warm-up must not register a session");
        } finally {
            service.close();
            WarmUp.reset();
        }
    }

    @Test
    void serviceSkipsWarmUpWhenDisabled() throws Exception {
        WarmUp.reset();
        System.setProperty(WarmUp.PROPERTY, "0");
        NativeService service = new NativeService(settings(tempDir), new NodeRegistry(),
                NativeService.defaultExecutors());
        try {
            service.start(0);
            service.warmUpDone().get(5, TimeUnit.SECONDS);
            assertFalse(WarmUp.claimed(), "a disabled warm-up must not claim the process");
            assertTrue(health(service.port()).path("ok").asBoolean());
        } finally {
            service.close();
            System.clearProperty(WarmUp.PROPERTY);
            WarmUp.reset();
        }
    }

    private static NativeService.Settings settings(Path tempDirectory) {
        return new NativeService.Settings(100, 600, "127.0.0.1", 0, null, null,
                tempDirectory.toString());
    }

    private static JsonNode health(int port) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/v1/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Json.MAPPER.readTree(response.body());
    }
}
