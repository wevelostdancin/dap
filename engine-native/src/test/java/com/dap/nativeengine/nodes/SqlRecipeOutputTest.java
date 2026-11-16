package com.dap.nativeengine.nodes;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.api.Json;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The output-side SQL recipes (GROUP BY ROLLUP, skewness/kurtosis, HAVING through
 * group_by -> row_filter and the train/test split) end to end through /v1.
 */
class SqlRecipeOutputTest {
    private static final Duration FAST = Duration.ofSeconds(10);
    private static final String P2 = """
            id,name,score,category
            1,alpha,1.0,A
            2,beta,2.0,B
            3,gamma,3.0,A
            4,delta,4.0,C
            3,gamma,3.0,A
            5,epsilon,5.0,B
            """;

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
        session = "recipe-output-" + UUID.randomUUID();
    }

    @AfterEach
    void stopServer() {
        if (service != null) {
            service.close();
            service = null;
        }
    }

    @Test
    void rollupRecipe() throws Exception {
        String workflow = createWorkflow();
        String source = addNode(workflow, "dap.csv_reader", Map.of("file", csv(P2)));
        String query = addNode(workflow, "dap.sql_query", Map.of(
                "sql", "SELECT category, SUM(score) AS total FROM src "
                        + "GROUP BY ROLLUP(category) ORDER BY category NULLS LAST",
                "inputs", List.of("src")));
        connect(workflow, source, query);
        JsonNode table = runAndRead(workflow, query);

        assertEquals(4, table.path("rows").asInt(), table.toString());
        assertEquals(List.of("category", "total"), names(table));
        assertEquals(List.of(
                List.of("A", "7.0"),
                List.of("B", "7.0"),
                List.of("C", "4.0"),
                java.util.Arrays.asList(null, "18.0")), data(table));
    }

    @Test
    void normalityRecipe() throws Exception {
        String workflow = createWorkflow();
        String source = addNode(workflow, "dap.csv_reader", Map.of("file",
                csv("x\n1\n2\n3\n4\n5\n6\n7\n8\n9\n100\n")));
        String query = addNode(workflow, "dap.sql_query", Map.of(
                "sql", "SELECT skewness(x) AS skewness, kurtosis(x) AS kurtosis, "
                        + "COUNT(*) AS n FROM src",
                "inputs", List.of("src")));
        connect(workflow, source, query);
        JsonNode table = runAndRead(workflow, query);

        assertEquals(1, table.path("rows").asInt(), table.toString());
        assertEquals(List.of("skewness", "kurtosis", "n"), names(table));
        // Reference values computed independently with the sample skewness and the
        // bias-corrected sample excess kurtosis (the pandas/Excel definitions).
        assertEquals(3.118895745239087, table.path("data").get(0).get(0).asDouble(), 1e-9);
        assertEquals(9.799244716163663, table.path("data").get(0).get(1).asDouble(), 1e-9);
        assertEquals("10", table.path("data").get(0).get(2).asText());
    }

    @Test
    void havingRecipe() throws Exception {
        String workflow = createWorkflow();
        String source = addNode(workflow, "dap.csv_reader", Map.of("file", csv(P2)));
        String group = addNode(workflow, "dap.group_by", Map.of(
                "keys", List.of("category"),
                "aggregations", List.of(Map.of("column", "id", "fn", "count"))));
        String having = addNode(workflow, "dap.row_filter", Map.of(
                "where", List.of(Map.of("column", "Count(id)", "op", "ge", "value", 2))));
        connect(workflow, source, group);
        connect(workflow, group, having);
        JsonNode table = runAndRead(workflow, having);

        assertEquals(2, table.path("rows").asInt(), table.toString());
        assertEquals(List.of("category", "Count(id)"), names(table));
        List<List<Object>> rows = new ArrayList<>(data(table));
        rows.sort((left, right) -> String.valueOf(left.get(0)).compareTo(String.valueOf(right.get(0))));
        assertEquals(List.of(List.of("A", "3"), List.of("B", "2")), rows);
    }

    @Test
    void trainTestRecipe() throws Exception {
        String workflow = createWorkflow();
        String source = addNode(workflow, "dap.csv_reader", Map.of("file", csv(P2)));
        // A reproducible split: random() would be re-evaluated on every read of the
        // sql_query view, so the documented recipe derives the flag from the key.
        String query = addNode(workflow, "dap.sql_query", Map.of(
                "sql", "SELECT *, ((id * 37) % 10) < 7 AS is_train FROM src",
                "inputs", List.of("src")));
        String train = addNode(workflow, "dap.row_filter", Map.of(
                "where", List.of(Map.of("column", "is_train", "op", "eq", "value", true))));
        String test = addNode(workflow, "dap.row_filter", Map.of(
                "where", List.of(Map.of("column", "is_train", "op", "eq", "value", false))));
        connect(workflow, source, query);
        connect(workflow, query, train);
        connect(workflow, query, test);
        assertEquals(200, send("POST", "/v1/workflows/" + workflow + "/run", Map.of()).status());

        JsonNode trainTable = read(workflow, train);
        JsonNode testTable = read(workflow, test);
        assertEquals(4, trainTable.path("rows").asInt(), trainTable.toString());
        assertEquals(2, testTable.path("rows").asInt(), testTable.toString());
        assertEquals(List.of("id", "name", "score", "category", "is_train"), names(trainTable));

        List<String> trainIds = column(trainTable, 0);
        List<String> testIds = column(testTable, 0);
        assertEquals(List.of("2", "3", "3", "5"), trainIds);
        assertEquals(List.of("1", "4"), testIds);
        for (String id : testIds) {
            assertTrue(!trainIds.contains(id), "row " + id + " is in both train and test");
        }
        for (JsonNode row : trainTable.path("data")) {
            assertEquals("true", row.get(4).asText());
        }
        for (JsonNode row : testTable.path("data")) {
            assertEquals("false", row.get(4).asText());
        }
        List<String> all = new ArrayList<>(trainIds);
        all.addAll(testIds);
        all.sort(String::compareTo);
        assertEquals(List.of("1", "2", "3", "3", "4", "5"), all);
    }

    // -------------------------------------------------------------- helpers

    private String csv(String content) throws Exception {
        Path file = tempDir.resolve(UUID.randomUUID() + ".csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toString();
    }

    private JsonNode runAndRead(String workflow, String node) throws Exception {
        Resp run = send("POST", "/v1/workflows/" + workflow + "/run", Map.of());
        assertEquals(200, run.status(), run.raw().body());
        return read(workflow, node);
    }

    private JsonNode read(String workflow, String node) throws Exception {
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

    private static List<String> column(JsonNode table, int index) {
        List<String> values = new ArrayList<>();
        for (JsonNode row : table.path("data")) {
            values.add(row.get(index).asText());
        }
        return values;
    }

    private String createWorkflow() throws Exception {
        Resp response = send("POST", "/v1/workflows", Map.of("name", "recipe-output"));
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
