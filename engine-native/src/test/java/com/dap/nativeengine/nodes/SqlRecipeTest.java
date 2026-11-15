package com.dap.nativeengine.nodes;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.DuckDbSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The documented SQL recipes (regexp_extract, string_split+unnest, covar_samp and
 * read_csv_auto through httpfs) end to end through /v1.
 */
class SqlRecipeTest {
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
        session = "recipe-" + UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Test
    void regexpExtractRecipe() throws Exception {
        JsonNode table = query("""
                code
                abc-123
                def-456
                """, "SELECT regexp_extract(code, '[a-z]+-([0-9]+)', 1) AS number "
                + "FROM src ORDER BY number");

        assertEquals(2, table.path("rows").asInt(), table.toString());
        assertEquals(List.of("number"), names(table));
        assertEquals(List.of(List.of("123"), List.of("456")), data(table));
    }

    @Test
    void splitColumnRecipe() throws Exception {
        JsonNode table = query("""
                tags
                "a,b"
                "c"
                """, "SELECT unnest(string_split(tags, ',')) AS tag FROM src ORDER BY tag");

        assertEquals(3, table.path("rows").asInt(), table.toString());
        assertEquals(List.of("tag"), names(table));
        assertEquals(List.of(List.of("a"), List.of("b"), List.of("c")), data(table));
    }

    @Test
    void covarianceRecipe() throws Exception {
        JsonNode table = query("""
                x,y
                1,2
                2,4
                3,6
                4,8
                """, "SELECT covar_samp(x, y) AS covariance FROM src");

        assertEquals(1, table.path("rows").asInt(), table.toString());
        assertEquals(List.of("covariance"), names(table));
        assertEquals(10.0 / 3.0, Double.parseDouble(table.path("data").get(0).get(0).asText()),
                1e-9);
    }

    @Test
    void httpReadCsvAutoRecipe() throws Exception {
        assumeTrue(httpfsAvailable(), "httpfs is not installed; run make extensions");
        byte[] csv = "id,name\n1,ann\n2,bob\n".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data.csv", exchange -> {
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, csv.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(csv);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            JsonNode table = queryWithoutInput("SELECT * FROM read_csv_auto('http://127.0.0.1:"
                    + port + "/data.csv') ORDER BY id");
            assertEquals(2, table.path("rows").asInt(), table.toString());
            assertEquals(List.of("id", "name"), names(table));
            assertEquals(List.of(List.of("1", "ann"), List.of("2", "bob")), data(table));
        } finally {
            server.stop(0);
        }
    }

    // -------------------------------------------------------------- helpers

    private static boolean httpfsAvailable() {
        try (DuckDbSession db = new DuckDbSession()) {
            db.execute("LOAD httpfs");
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private JsonNode query(String csv, String sql) throws Exception {
        Path file = tempDir.resolve(UUID.randomUUID() + ".csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        String workflow = createWorkflow();
        String source = addNode(workflow, "dap.csv_reader", Map.of("file", file.toString()));
        String query = addNode(workflow, "dap.sql_query",
                Map.of("sql", sql, "inputs", List.of("src")));
        connect(workflow, source, query);
        return runAndRead(workflow, query);
    }

    private JsonNode queryWithoutInput(String sql) throws Exception {
        String workflow = createWorkflow();
        String query = addNode(workflow, "dap.sql_query", Map.of("sql", sql));
        return runAndRead(workflow, query);
    }

    private JsonNode runAndRead(String workflow, String node) throws Exception {
        Resp run = send("POST", "/v1/workflows/" + workflow + "/run", Map.of());
        assertEquals(200, run.status(), run.raw().body());
        Resp table = send("GET", "/v1/workflows/" + workflow + "/nodes/" + node
                + "/table?port=0&limit=100", null);
        assertEquals(200, table.status(), table.raw().body());
        return table.body();
    }

    private static List<String> names(JsonNode table) {
        List<String> names = new ArrayList<>();
        table.path("columns").forEach(column -> names.add(column.path("name").asText()));
        return names;
    }

    private static List<List<Object>> data(JsonNode table) {
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode row : table.path("data")) {
            List<Object> cells = new ArrayList<>();
            for (JsonNode cell : row) {
                // /table returns every cell as a string (or null); keep that contract.
                cells.add(cell.isNull() ? null : cell.asText());
            }
            rows.add(cells);
        }
        return rows;
    }

    private String createWorkflow() throws Exception {
        Resp response = send("POST", "/v1/workflows", Map.of("name", "recipe"));
        assertEquals(200, response.status(), response.raw().body());
        return response.body().path("id").asText();
    }

    private String addNode(String workflow, String factory, Map<String, Object> settings)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("factory", factory);
        body.put("settings", settings);
        Resp response = send("POST", "/v1/workflows/" + workflow + "/nodes", body);
        assertEquals(200, response.status(), response.raw().body());
        return response.body().path("node").asText();
    }

    private void connect(String workflow, String from, String to) throws Exception {
        Resp response = send("POST", "/v1/workflows/" + workflow + "/connect",
                Map.of("from", from, "to", to, "fromPort", 0, "toPort", 0));
        assertEquals(200, response.status(), response.raw().body());
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
