package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /v1/health carries a boot generation so the MCP adapter can notice an engine restart. */
class HealthGenerationTest {
    private final List<NativeService> services = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void stopServices() {
        for (NativeService service : services) {
            service.close();
        }
        services.clear();
    }

    @Test
    void generationIsNonEmptyAndStableForTheProcess() throws Exception {
        NativeService service = start();
        JsonNode first = health(service);
        JsonNode second = health(service);

        String generation = first.path("generation").asText();
        assertFalse(generation.isBlank(), "health must expose a boot generation: " + first);
        assertEquals(generation, second.path("generation").asText(),
                "the generation must be stable for the process");
    }

    @Test
    void anotherProcessHasAnotherGeneration() throws Exception {
        NativeService first = start();
        NativeService second = start();
        assertNotEquals(health(first).path("generation").asText(),
                health(second).path("generation").asText(),
                "each process start must produce a new generation");
    }

    private NativeService start() {
        NativeService service = new NativeService(
                new NativeService.Settings(100, 600, "127.0.0.1", 0),
                new NodeRegistry(), NativeService.defaultExecutors());
        service.start(0);
        services.add(service);
        return service;
    }

    private JsonNode health(NativeService service) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.port() + "/v1/health"))
                .header("X-DAP-Session", "health-" + service.port())
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Json.MAPPER.readTree(response.body());
    }
}
