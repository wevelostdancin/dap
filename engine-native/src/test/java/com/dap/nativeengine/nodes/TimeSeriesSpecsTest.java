package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.catalog.NodeSpec;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The P3 time-series specs parse and stay in sync with the executor contracts. */
class TimeSeriesSpecsTest {
    private static final List<String> IDS = List.of(
            "dap.outlier_detect",
            "dap.resample",
            "dap.rolling",
            "dap.diff",
            "dap.date_range");

    private static NodeSpec load(String id) throws Exception {
        try (InputStream stream = TimeSeriesSpecsTest.class.getClassLoader()
                .getResourceAsStream("specs/" + id + ".json")) {
            assertNotNull(stream, "missing spec specs/" + id + ".json");
            return NodeSpec.fromJson(Json.MAPPER.readTree(stream));
        }
    }

    @Test
    void everySpecParsesWithTheCoreMetadata() throws Exception {
        for (String id : IDS) {
            NodeSpec spec = load(id);
            assertEquals(id, spec.id());
            assertEquals("dap-native", spec.contributor(), id);
            assertEquals("core", spec.tier(), id);
            assertFalse(spec.deprecated(), id);
            assertTrue(spec.available(), id);
            assertFalse(spec.name().isBlank(), id);
            assertTrue(spec.category().startsWith("/"), id);
            assertTrue(spec.description().length() >= 20, id);
            assertEquals("object", spec.configSchema().path("type").asText(), id);
            assertEquals(1, spec.outPorts().size(), id);
            assertEquals(0, spec.outPorts().get(0).index(), id);
            assertEquals("Data", spec.outPorts().get(0).name(), id);
            assertEquals("BufferedDataTable", spec.outPorts().get(0).type(), id);
        }
    }

    @Test
    void portsAndTypesMatchTheExecutorContracts() throws Exception {
        for (String id : List.of("dap.outlier_detect", "dap.resample", "dap.rolling", "dap.diff")) {
            assertEquals("Manipulator", load(id).type(), id);
            assertEquals(List.of(new NodeSpec.Port(0, "Data", "BufferedDataTable")), load(id).inPorts(), id);
        }
        assertEquals("Source", load("dap.date_range").type());
        assertTrue(load("dap.date_range").inPorts().isEmpty());
    }

    @Test
    void configSchemasExposeTheExecutorVocabulary() throws Exception {
        assertEquals(Set.of("columns", "method", "threshold"), load("dap.outlier_detect").settingKeys());
        assertEquals(List.of("columns", "method"), load("dap.outlier_detect").requiredKeys());
        JsonNode method = property(load("dap.outlier_detect"), "method");
        assertEquals(List.of("iqr", "zscore"), enumValues(method));

        assertEquals(Set.of("timeColumn", "every", "aggregations"), load("dap.resample").settingKeys());
        assertEquals(List.of("timeColumn", "every", "aggregations"), load("dap.resample").requiredKeys());
        JsonNode aggregation = property(load("dap.resample"), "aggregations").path("items");
        assertEquals(List.of("column", "fn"), texts(aggregation.path("required")));
        assertEquals(List.of("mean", "sum", "count", "min", "max"),
                enumValues(aggregation.path("properties").path("fn")));

        assertEquals(Set.of("column", "output", "window", "fn", "orderBy", "partitionBy"),
                load("dap.rolling").settingKeys());
        assertEquals(List.of("column", "output", "window", "fn", "orderBy"), load("dap.rolling").requiredKeys());
        assertEquals(List.of("mean", "sum", "min", "max"), enumValues(property(load("dap.rolling"), "fn")));
        assertEquals(1, property(load("dap.rolling"), "window").path("minimum").asInt());

        assertEquals(Set.of("column", "output", "orderBy", "partitionBy"), load("dap.diff").settingKeys());
        assertEquals(List.of("column", "output", "orderBy"), load("dap.diff").requiredKeys());

        assertEquals(Set.of("start", "end", "step"), load("dap.date_range").settingKeys());
        assertEquals(List.of("start", "end", "step"), load("dap.date_range").requiredKeys());
        assertEquals("1 day", property(load("dap.date_range"), "step").path("default").asText());

        for (String id : List.of("dap.rolling", "dap.diff")) {
            JsonNode orderKey = property(load(id), "orderBy").path("items");
            assertEquals(List.of("column"), texts(orderKey.path("required")), id);
            assertEquals(List.of("asc", "desc"), enumValues(orderKey.path("properties").path("direction")), id);
        }
    }

    private static JsonNode property(NodeSpec spec, String key) {
        return spec.configSchema().path("properties").path(key);
    }

    private static List<String> enumValues(JsonNode node) {
        return texts(node.path("enum"));
    }

    private static List<String> texts(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
