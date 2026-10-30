package com.dap.nativeengine.catalog;

import com.dap.nativeengine.api.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NodeSpec projects a JSON spec into the three /v1 catalog payload shapes. */
class NodeSpecTest {
    private final NodeRegistry registry = new NodeRegistry();
    private final NodeSpec csv = registry.require("dap.csv_reader");

    @Test
    void briefKeepsOnlyCatalogFields() {
        Map<String, Object> brief = csv.brief();
        assertEquals(Set.of("id", "name", "category", "type", "deprecated", "tier", "inPorts", "outPorts"),
                brief.keySet());
        assertEquals("dap.csv_reader", brief.get("id"));
        assertEquals("CSV Reader", brief.get("name"));
        assertEquals("/IO/Read", brief.get("category"));
        assertEquals("Source", brief.get("type"));
        assertEquals(false, brief.get("deprecated"));
        assertEquals("core", brief.get("tier"));
        assertEquals(List.of(), brief.get("inPorts"));
        List<?> outPorts = (List<?>) brief.get("outPorts");
        assertEquals(1, outPorts.size());
        assertEquals(Map.of("index", 0, "name", "Data", "type", "BufferedDataTable"), outPorts.get(0));
    }

    @Test
    void fullAddsSettingsAndAvailability() {
        Map<String, Object> full = csv.full();
        assertEquals("dap-native", full.get("contributor"));
        assertEquals(true, full.get("configurable"));
        assertEquals(List.of("columns", "delimiter", "emptyStringIsNull", "file", "header"),
                full.get("settings"));
        assertTrue(full.containsKey("settingsXml"));
        assertNull(full.get("settingsXml"));
        String note = (String) full.get("settingsUnavailable");
        assertTrue(note.contains("/v1/nodes/dap.csv_reader/dialog"));
        assertFalse(((String) full.get("description")).isBlank());
        assertEquals(true, full.get("available"));
        assertEquals("core", full.get("tier"));
        assertEquals(false, full.get("deprecated"));
    }

    @Test
    void fullMarksUnavailableSpecs() throws Exception {
        // Every pilot node is available; the flag keeps working for future waves.
        NodeSpec future = NodeSpec.fromJson(Json.parse("""
                {"id":"dap.future","name":"Future","category":"/IO/Read","type":"Source","available":false,
                 "configSchema":{"type":"object","properties":{"sheet":{"type":"string"}}}}
                """));
        Map<String, Object> full = future.full();
        assertEquals(false, full.get("available"));
        assertTrue(((List<?>) full.get("settings")).contains("sheet"));
        assertNull(full.get("settingsXml"));
    }

    @Test
    void availableDialogIsAnAnnotatedSchema() {
        JsonNode dialog = dialog("dap.csv_reader");
        assertEquals("dap.csv_reader", dialog.path("id").asText());
        assertEquals("core", dialog.path("tier").asText());
        assertTrue(dialog.path("precomputed").asBoolean());
        assertTrue(dialog.path("available").asBoolean());
        assertEquals("annotations", dialog.path("schemaKind").asText());

        JsonNode schema = schema(dialog);
        assertEquals("dap.csv_reader", schema.path("class").asText());
        assertEquals("5/5", schema.path("labelCoverage").asText());
        assertTrue(schema.path("warnings").isArray());
        assertEquals(0, schema.path("warnings").size());

        JsonNode fields = schema.path("fields");
        assertEquals(List.of("file", "delimiter", "header", "columns", "emptyStringIsNull"),
                texts(fields, "field"));

        JsonNode file = fields.get(0);
        assertEquals("string", file.path("type").asText());
        assertFalse(file.path("optional").asBoolean());
        assertEquals("Input file", file.path("title").asText());
        assertTrue(file.path("description").asText().length() > 10);
        assertFalse(file.path("advanced").asBoolean());

        JsonNode delimiter = fields.get(1);
        assertEquals("string", delimiter.path("type").asText());
        assertEquals(",", delimiter.path("default").asText());
        assertTrue(delimiter.path("optional").asBoolean());

        JsonNode header = fields.get(2);
        assertEquals("boolean", header.path("type").asText());
        assertTrue(header.path("default").asBoolean());

        JsonNode columns = fields.get(3);
        assertEquals("array", columns.path("type").asText());
        assertEquals("object", columns.path("items").path("type").asText());

        JsonNode empty = fields.get(4);
        assertEquals("boolean", empty.path("type").asText());
        assertFalse(empty.path("default").asBoolean());
    }

    @Test
    void missingValueDialogExposesRuleArray() {
        JsonNode fields = fields(dialog("dap.missing_value"));
        assertEquals(1, fields.size());
        JsonNode rules = fields.get(0);
        assertEquals("rules", rules.path("field").asText());
        assertEquals("array", rules.path("type").asText());
        assertEquals("object", rules.path("items").path("type").asText());
        JsonNode properties = rules.path("items").path("properties");
        assertTrue(properties.has("column"));
        assertTrue(properties.has("method"));
        assertTrue(properties.has("value"));
        assertEquals(List.of("column", "method"), texts(rules.path("items").path("required")));
        assertEquals(List.of("constant", "mean", "mode"),
                texts(properties.path("method").path("enum")));
    }

    @Test
    void enumDialogFieldsCarryChoices() {
        JsonNode logic = field(dialog("dap.row_filter"), "logic");
        assertEquals("enum", logic.path("type").asText());
        assertEquals("and", logic.path("default").asText());
        assertEquals(List.of("and", "or"), texts(logic.path("choices"), "value"));
        assertEquals(List.of("And", "Or"), texts(logic.path("choices"), "label"));

        JsonNode collation = field(dialog("dap.sorter"), "collation");
        assertEquals("enum", collation.path("type").asText());
        assertEquals(List.of("natural", "binary", "nocase"), texts(collation.path("choices"), "value"));
    }

    @Test
    void unavailableDialogExplainsTheGap() throws Exception {
        // Every pilot node is available; the reason payload stays for future waves.
        NodeSpec future = NodeSpec.fromJson(Json.parse("""
                {"id":"dap.future","name":"Future","category":"/IO/Read","type":"Source","available":false}
                """));
        JsonNode dialog = Json.MAPPER.valueToTree(future.dialog());
        assertEquals("dap.future", dialog.path("id").asText());
        assertFalse(dialog.path("available").asBoolean());
        assertEquals("not implemented in native engine yet", dialog.path("reason").asText());
        assertEquals("core", dialog.path("tier").asText());
        assertTrue(dialog.path("precomputed").asBoolean());
        assertFalse(dialog.has("schema"));
        assertFalse(dialog.has("schemaKind"));
    }

    @Test
    void everySpecHasAStableDialogShape() {
        for (NodeSpec spec : registry.all()) {
            JsonNode dialog = dialog(spec.id());
            assertEquals(spec.id(), dialog.path("id").asText());
            assertEquals("core", dialog.path("tier").asText());
            assertTrue(dialog.path("precomputed").asBoolean());
            assertEquals(spec.available(), dialog.path("available").asBoolean());
            assertEquals(!spec.available(), dialog.has("reason"));
        }
    }

    private JsonNode dialog(String id) {
        return Json.MAPPER.valueToTree(registry.require(id).dialog());
    }

    private static JsonNode schema(JsonNode dialog) {
        return dialog.path("schema");
    }

    private static JsonNode fields(JsonNode dialog) {
        return schema(dialog).path("fields");
    }

    private static JsonNode field(JsonNode dialog, String name) {
        for (JsonNode field : fields(dialog)) {
            if (field.path("field").asText().equals(name)) {
                return field;
            }
        }
        throw new AssertionError("dialog has no field " + name);
    }

    private static List<String> texts(JsonNode array) {
        return texts(array, null);
    }

    private static List<String> texts(JsonNode array, String key) {
        List<String> values = new ArrayList<>();
        for (JsonNode entry : array) {
            values.add(key == null ? entry.asText() : entry.path(key).asText());
        }
        return values;
    }
}
