package com.dap.nativeengine.api;

import com.dap.nativeengine.catalog.NodeRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for GET /v1/nodes, /v1/nodes/{id} and /v1/nodes/{id}/dialog. */
class CatalogHandlersTest {
    private static final NodeRegistry REGISTRY = new NodeRegistry();

    private Javalin app;
    private HttpClient client;
    private String base;

    @BeforeEach
    void startServer() throws IOException {
        int port = freePort();
        CatalogHandlers handlers = new CatalogHandlers(REGISTRY);
        app = Javalin.create(config -> config.showJavalinBanner = false);
        app.exception(ApiException.class, (exception, context) ->
                Http.error(context, exception.status(), exception.getMessage()));
        app.get("/v1/nodes", handlers::list);
        app.get("/v1/nodes/{nodeTypeId}", handlers::get);
        app.get("/v1/nodes/{nodeTypeId}/dialog", handlers::dialog);
        app.start(port);
        assertTrue(app.port() > 0);
        base = "http://127.0.0.1:" + app.port() + "/v1";
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stopServer() {
        if (app != null) {
            app.stop();
            app = null;
        }
    }

    @Test
    void listsAllNodesSortedByName() throws Exception {
        HttpResponse<String> response = fetch("/nodes?limit=100", "agent-1");
        assertEquals(200, response.statusCode());
        assertEquals("1", response.headers().firstValue("X-DAP-Api-Version").orElseThrow());
        assertEquals("agent-1", response.headers().firstValue("X-DAP-Session").orElseThrow());

        JsonNode body = body(response);
        assertEquals(56, body.path("count").asInt());
        JsonNode nodes = body.path("nodes");
        assertEquals(56, nodes.size());
        assertEquals(List.of(
                "Binner", "Chart", "Column Filter",
                "Column Renamer", "Column Resorter", "Concatenate",
                "Correlation", "CSV Reader", "CSV Writer",
                "Data Quality", "Database Reader", "Database Writer",
                "Date & Time",
                "Date Range", "Describe", "Diff",
                "Duplicate Filter", "Encoder", "Excel Reader",
                "Excel Writer", "File List", "File Reader",
                "Fuzzy Join", "Group By",
                "Histogram", "Hypothesis Test", "Joiner",
                "JSON Reader", "JSON Writer", "Linear Regression",
                "Markdown Report", "Math Formula", "Missing Value",
                "Normalizer", "Outlier Detect", "Outlier Filter",
                "Parquet Reader", "Parquet Writer", "Pivot",
                "Quantile", "Resample", "Rolling",
                "Row Aggregator", "Row Filter", "Rule Engine",
                "Sample Rows", "Sorter", "SQL Query",
                "String Manipulation", "Text Normalize", "Top N per Group",
                "Type Cast",
                "Unpivot", "Value Counts", "Value Replace",
                "Window"), names(nodes));

        JsonNode first = nodes.get(0);
        assertEquals("dap.binner", first.path("id").asText());
        JsonNode csvNode = nodes.get(7);
        assertEquals("dap.csv_reader", csvNode.path("id").asText());
        assertEquals("CSV Reader", csvNode.path("name").asText());
        assertEquals("/IO/Read", csvNode.path("category").asText());
        assertEquals("Source", csvNode.path("type").asText());
        assertEquals("core", first.path("tier").asText());
        assertFalse(first.path("deprecated").asBoolean());
        assertEquals(0, csvNode.path("inPorts").size());
        assertEquals(1, csvNode.path("outPorts").size());
        assertEquals("Data", csvNode.path("outPorts").get(0).path("name").asText());
        assertEquals("BufferedDataTable", csvNode.path("outPorts").get(0).path("type").asText());
        assertFalse(first.has("contributor"));
        assertFalse(first.has("available"));
    }

    @Test
    void filtersByQueryAndCategory() throws Exception {
        JsonNode csv = body(fetch("/nodes?q=CSV", "s"));
        assertEquals(2, csv.path("count").asInt());
        assertEquals("dap.csv_reader", csv.path("nodes").get(0).path("id").asText());
        assertEquals("dap.csv_writer", csv.path("nodes").get(1).path("id").asText());

        JsonNode io = body(fetch("/nodes?category=%2FIO", "s"));
        assertEquals(13, io.path("count").asInt());
        assertEquals(List.of("CSV Reader", "CSV Writer", "Database Reader", "Database Writer",
                "Excel Reader", "Excel Writer", "File List", "File Reader", "JSON Reader",
                "JSON Writer", "Markdown Report", "Parquet Reader", "Parquet Writer"),
                names(io.path("nodes")));

        JsonNode combined = body(fetch("/nodes?q=reader&category=%2FIO", "s"));
        assertEquals(6, combined.path("count").asInt());
    }

    @Test
    void appliesLimitAndReturnsEmptyForUnknownValues() throws Exception {
        JsonNode limited = body(fetch("/nodes?limit=3", "s"));
        assertEquals(3, limited.path("count").asInt());
        assertEquals(List.of("Binner", "Chart", "Column Filter"), names(limited.path("nodes")));

        HttpResponse<String> empty = fetch("/nodes?q=no-such-node", "s");
        assertEquals(200, empty.statusCode());
        JsonNode none = body(empty);
        assertEquals(0, none.path("count").asInt());
        assertTrue(none.path("nodes").isArray());
        assertEquals(0, none.path("nodes").size());
        assertEquals(0, body(fetch("/nodes?category=%2Fnope", "s")).path("count").asInt());
    }

    @Test
    void defaultsSessionWhenHeaderIsMissing() throws Exception {
        HttpResponse<String> response = fetch("/nodes", null);
        assertEquals(200, response.statusCode());
        assertEquals("default", response.headers().firstValue("X-DAP-Session").orElseThrow());
    }

    @Test
    void rejectsInvalidSession() throws Exception {
        HttpResponse<String> response = fetch("/nodes", "bad session!");
        assertEquals(400, response.statusCode());
        assertEquals("1", response.headers().firstValue("X-DAP-Api-Version").orElseThrow());
        assertEquals("invalid session id", body(response).path("error").asText());
    }

    @Test
    void returnsTheFullSpec() throws Exception {
        HttpResponse<String> response = fetch("/nodes/dap.csv_reader", "agent-1");
        assertEquals(200, response.statusCode());
        assertEquals("1", response.headers().firstValue("X-DAP-Api-Version").orElseThrow());
        assertEquals("agent-1", response.headers().firstValue("X-DAP-Session").orElseThrow());

        JsonNode body = body(response);
        assertEquals("dap.csv_reader", body.path("id").asText());
        assertEquals("dap-native", body.path("contributor").asText());
        assertEquals(true, body.path("configurable").asBoolean());
        assertEquals(true, body.path("available").asBoolean());
        assertEquals("core", body.path("tier").asText());
        assertFalse(body.path("deprecated").asBoolean());
        assertTrue(body.path("settingsXml").isNull());
        assertTrue(body.path("settingsUnavailable").asText().contains("/v1/nodes/dap.csv_reader/dialog"));
        assertFalse(body.path("description").asText().isBlank());
        assertEquals(List.of("columns", "compression", "delimiter", "emptyStringIsNull", "file", "header"), texts(body.path("settings")));
        assertEquals(1, body.path("outPorts").size());
    }

    @Test
    void joinerSpecHasTwoInputs() throws Exception {
        JsonNode body = body(fetch("/nodes/dap.joiner", "s"));
        assertEquals(2, body.path("inPorts").size());
        assertEquals(0, body.path("inPorts").get(0).path("index").asInt());
        assertEquals("Left", body.path("inPorts").get(0).path("name").asText());
        assertEquals(1, body.path("inPorts").get(1).path("index").asInt());
        assertEquals("Right", body.path("inPorts").get(1).path("name").asText());
    }

    @Test
    void unknownNodeIs404() throws Exception {
        HttpResponse<String> response = fetch("/nodes/dap.nope", "s");
        assertEquals(404, response.statusCode());
        assertEquals("unknown node: dap.nope", body(response).path("error").asText());
        assertEquals("1", response.headers().firstValue("X-DAP-Api-Version").orElseThrow());
    }

    @Test
    void dialogForAvailableNodeReturnsAnnotatedSchema() throws Exception {
        HttpResponse<String> response = fetch("/nodes/dap.csv_reader/dialog", "s");
        assertEquals(200, response.statusCode());
        JsonNode body = body(response);
        assertEquals("dap.csv_reader", body.path("id").asText());
        assertEquals(true, body.path("available").asBoolean());
        assertEquals("annotations", body.path("schemaKind").asText());
        assertEquals(true, body.path("precomputed").asBoolean());
        assertEquals("core", body.path("tier").asText());

        JsonNode schema = body.path("schema");
        assertEquals("dap.csv_reader", schema.path("class").asText());
        assertEquals("6/6", schema.path("labelCoverage").asText());
        assertTrue(schema.path("warnings").isArray());
        assertEquals(0, schema.path("warnings").size());
        JsonNode fields = schema.path("fields");
        assertEquals(6, fields.size());

        JsonNode file = fields.get(0);
        assertEquals("file", file.path("field").asText());
        assertEquals("file", file.path("configKey").asText());
        assertEquals("Input file", file.path("title").asText());
        assertEquals("string", file.path("type").asText());
        assertFalse(file.path("optional").asBoolean());
        assertEquals(",", fields.get(1).path("default").asText());
        assertEquals("array", fields.get(4).path("type").asText());
        assertEquals("object", fields.get(4).path("items").path("type").asText());
    }

    @Test
    void dialogForMissingValueShowsArrayRules() throws Exception {
        JsonNode fields = body(fetch("/nodes/dap.missing_value/dialog", "s")).path("schema").path("fields");
        assertEquals(1, fields.size());
        JsonNode rules = fields.get(0);
        assertEquals("rules", rules.path("field").asText());
        assertEquals("array", rules.path("type").asText());
        assertEquals("object", rules.path("items").path("type").asText());
        assertEquals(List.of("constant", "mean", "mode"),
                texts(rules.path("items").path("properties").path("method").path("enum")));
        assertEquals(List.of("column", "method"), texts(rules.path("items").path("required")));
    }

    @Test
    void dialogEnumFieldCarriesChoices() throws Exception {
        JsonNode logic = body(fetch("/nodes/dap.row_filter/dialog", "s"))
                .path("schema").path("fields").get(0);
        assertEquals("logic", logic.path("field").asText());
        assertEquals("enum", logic.path("type").asText());
        assertEquals("and", logic.path("default").asText());
        assertEquals(2, logic.path("choices").size());
        assertEquals("and", logic.path("choices").get(0).path("value").asText());
        assertEquals("And", logic.path("choices").get(0).path("label").asText());
        assertEquals("or", logic.path("choices").get(1).path("value").asText());
    }

    @Test
    void dialogForUnknownNodeIs400() throws Exception {
        HttpResponse<String> response = fetch("/nodes/dap.nope/dialog", "s");
        assertEquals(400, response.statusCode());
        assertEquals("unknown node: dap.nope", body(response).path("error").asText());
    }

    @Test
    void dialogCoversEveryCatalogNode() throws Exception {
        for (var spec : REGISTRY.all()) {
            HttpResponse<String> response = fetch("/nodes/" + spec.id() + "/dialog", "s");
            assertEquals(200, response.statusCode(), spec.id());
            JsonNode body = body(response);
            assertEquals(spec.id(), body.path("id").asText());
            assertEquals(spec.available(), body.path("available").asBoolean(), spec.id());
            assertEquals("core", body.path("tier").asText());
        }
    }

    private HttpResponse<String> fetch(String path, String session) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (session != null) {
            request.header("X-DAP-Session", session);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return Json.MAPPER.readTree(response.body());
    }

    private static List<String> names(JsonNode nodes) {
        List<String> names = new ArrayList<>();
        nodes.forEach(node -> names.add(node.path("name").asText()));
        return names;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
