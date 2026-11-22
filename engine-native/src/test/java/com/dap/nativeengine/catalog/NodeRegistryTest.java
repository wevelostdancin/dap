package com.dap.nativeengine.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog is the source of truth for node metadata. */
class NodeRegistryTest {
    private static final Set<String> ALL_IDS = Set.of(
            "dap.csv_reader",
            "dap.row_filter",
            "dap.column_filter",
            "dap.sorter",
            "dap.missing_value",
            "dap.excel_reader",
            "dap.group_by",
            "dap.joiner",
            "dap.string_manipulation",
            "dap.math_formula",
            "dap.column_renamer",
            "dap.parquet_reader",
            "dap.json_reader",
            "dap.csv_writer",
            "dap.parquet_writer",
            "dap.json_writer",
            "dap.type_cast",
            "dap.duplicate_filter",
            "dap.value_replace",
            "dap.column_resorter",
            "dap.outlier_filter",
            "dap.date_time",
            "dap.rule_engine",
            "dap.binner",
            "dap.normalizer",
            "dap.encoder",
            "dap.sample_rows",
            "dap.row_aggregator",
            "dap.pivot",
            "dap.unpivot",
            "dap.window",
            "dap.top_n_per_group",
            "dap.concatenate",
            "dap.describe",
            "dap.value_counts",
            "dap.correlation",
            "dap.histogram",
            "dap.quantile",
            "dap.outlier_detect",
            "dap.resample",
            "dap.rolling",
            "dap.diff",
            "dap.date_range",
            "dap.excel_writer",
            "dap.markdown_report",
            "dap.chart",
            "dap.data_quality",
            "dap.hypothesis_test",
            "dap.linear_regression",
            "dap.logistic_regression",
            "dap.cluster",
            "dap.pca",
            "dap.sql_query",
            "dap.db_reader",
            "dap.file_list",
            "dap.chart_grid",
            "dap.file_reader",
            "dap.db_writer",
            "dap.text_normalize",
            "dap.fuzzy_join",
            "dap.geo_distance",
            "dap.calendar",
            "dap.resample_test",
            "dap.survival",
            "dap.ab_test",
            "dap.time_series");

    private final NodeRegistry registry = new NodeRegistry();

    @Test
    void loadsEverySpecOnce() {
        assertEquals(ALL_IDS.size(), registry.size());
        List<String> ids = registry.all().stream().map(NodeSpec::id).toList();
        assertEquals(ALL_IDS.size(), new HashSet<>(ids).size(), "node ids must be unique");
        assertEquals(ALL_IDS, new HashSet<>(ids));
    }

    @Test
    void everySpecIsAvailable() {
        assertEquals(ALL_IDS.size(), registry.availableCount());
        Set<String> available = new HashSet<>();
        registry.all().stream().filter(NodeSpec::available).forEach(spec -> available.add(spec.id()));
        assertEquals(ALL_IDS, available);
    }

    @Test
    void everySpecCarriesCoreMetadata() {
        for (NodeSpec spec : registry.all()) {
            assertTrue(spec.id().startsWith("dap."), spec.id());
            assertEquals("dap-native", spec.contributor(), spec.id());
            assertEquals("core", spec.tier(), spec.id());
            assertFalse(spec.deprecated(), spec.id());
            assertFalse(spec.description().isBlank(), spec.id());
            assertTrue(spec.description().length() >= 20, spec.id());
            assertFalse(spec.name().isBlank(), spec.id());
            assertTrue(spec.category().startsWith("/"), spec.id());
            assertTrue(Set.of("Source", "Manipulator", "Sink").contains(spec.type()), spec.id());
            assertEquals("object", spec.configSchema().path("type").asText(), spec.id());
            assertTrue(spec.configSchema().path("properties").isObject(), spec.id());
            assertFalse(spec.configSchema().path("properties").isEmpty(), spec.id());
        }
    }

    @Test
    void everySpecHasOneDataOutput() {
        for (NodeSpec spec : registry.all()) {
            assertEquals(1, spec.outPorts().size(), spec.id());
            NodeSpec.Port out = spec.outPorts().get(0);
            assertEquals(0, out.index(), spec.id());
            assertEquals("Data", out.name(), spec.id());
            assertEquals("BufferedDataTable", out.type(), spec.id());
        }
    }

    @Test
    void sourcesHaveNoInputsAndManipulatorsHaveOne() {
        for (NodeSpec spec : registry.all()) {
            if (spec.id().equals("dap.joiner") || spec.id().equals("dap.concatenate")
                    || spec.id().equals("dap.data_quality")
                    || spec.id().equals("dap.fuzzy_join")
                    || spec.id().equals("dap.sql_query")
                    || spec.id().equals("dap.markdown_report")
                    || spec.id().equals("dap.chart_grid")) {
                continue;
            }
            if (spec.type().equals("Source")) {
                assertTrue(spec.inPorts().isEmpty(), spec.id());
            } else {
                assertEquals(List.of(new NodeSpec.Port(0, "Data", "BufferedDataTable")),
                        spec.inPorts(), spec.id());
            }
        }
    }

    @Test
    void dataQualityHasDataAndOptionalReferenceInputs() {
        assertEquals(List.of(
                        new NodeSpec.Port(0, "Data", "BufferedDataTable"),
                        new NodeSpec.Port(1, "Reference", "BufferedDataTable")),
                registry.require("dap.data_quality").inPorts());
    }

    @Test
    void markdownReportHasEightDataInputs() {
        List<NodeSpec.Port> ports = new java.util.ArrayList<>();
        for (int index = 0; index < 8; index++) {
            ports.add(new NodeSpec.Port(index, "Data " + (index + 1), "BufferedDataTable"));
        }
        assertEquals(ports, registry.require("dap.markdown_report").inPorts());
    }

    @Test
    void joinerHasLeftAndRightInputs() {
        assertEquals(List.of(
                        new NodeSpec.Port(0, "Left", "BufferedDataTable"),
                        new NodeSpec.Port(1, "Right", "BufferedDataTable")),
                registry.require("dap.joiner").inPorts());
    }

    @Test
    void configSchemasExposeTheNativeVocabulary() {
        assertEquals(Set.of("file", "delimiter", "compression", "header", "columns", "emptyStringIsNull"),
                registry.require("dap.csv_reader").settingKeys());
        assertEquals(Set.of("logic", "where"), registry.require("dap.row_filter").settingKeys());
        assertEquals(Set.of("include", "exclude"), registry.require("dap.column_filter").settingKeys());
        assertEquals(Set.of("renames"), registry.require("dap.column_renamer").settingKeys());
        assertEquals(Set.of("keys", "collation"), registry.require("dap.sorter").settingKeys());
        assertEquals(Set.of("rules"), registry.require("dap.missing_value").settingKeys());
        assertEquals(Set.of("file", "sheet", "header", "columns", "emptyStringIsNull", "errorsAsNull"),
                registry.require("dap.excel_reader").settingKeys());
        assertEquals(Set.of("keys", "aggregations"), registry.require("dap.group_by").settingKeys());
        assertEquals(Set.of("how", "left_keys", "right_keys", "merge_keys",
                        "left_time", "right_time", "asof_inequality"),
                registry.require("dap.joiner").settingKeys());
        assertEquals(Set.of("column", "op", "mode", "search", "value"),
                registry.require("dap.string_manipulation").settingKeys());
        assertEquals(Set.of("outputs"), registry.require("dap.math_formula").settingKeys());
        assertEquals(Set.of("file"), registry.require("dap.parquet_reader").settingKeys());
        assertEquals(Set.of("file"), registry.require("dap.json_reader").settingKeys());
        assertEquals(Set.of("file", "delimiter", "header"), registry.require("dap.csv_writer").settingKeys());
        assertEquals(Set.of("file", "compression"), registry.require("dap.parquet_writer").settingKeys());
        assertEquals(Set.of("file", "array"), registry.require("dap.json_writer").settingKeys());
    }

    @Test
    void csvReaderSchemaCarriesTypesDefaultsAndRequired() {
        NodeSpec spec = registry.require("dap.csv_reader");
        assertEquals(List.of("file"), spec.requiredKeys());
        JsonNode properties = spec.configSchema().path("properties");
        assertEquals("string", properties.path("file").path("type").asText());
        assertTrue(properties.path("file").path("description").asText().length() > 10);
        assertEquals("string", properties.path("delimiter").path("type").asText());
        assertEquals(",", properties.path("delimiter").path("default").asText());
        assertEquals("boolean", properties.path("header").path("type").asText());
        assertTrue(properties.path("header").path("default").asBoolean());
        assertEquals("array", properties.path("columns").path("type").asText());
        assertEquals("object", properties.path("columns").path("items").path("type").asText());
        JsonNode columnProperties = properties.path("columns").path("items").path("properties");
        assertEquals("string", columnProperties.path("name").path("type").asText());
        assertEquals("string", columnProperties.path("type").path("type").asText());
        assertEquals("boolean", properties.path("emptyStringIsNull").path("type").asText());
        assertFalse(properties.path("emptyStringIsNull").path("default").asBoolean());
    }

    @Test
    void enumVocabulariesMatchTheRoadmap() {
        assertEquals(List.of("and", "or"), enumValues(property("dap.row_filter", "logic")));
        assertEquals(List.of("eq", "ne", "lt", "le", "gt", "ge", "is_null", "not_null"),
                enumValues(property("dap.row_filter", "where").path("items").path("properties").path("op")));
        assertEquals(List.of("asc", "desc"), enumValues(property("dap.sorter", "keys")
                .path("items").path("properties").path("direction")));
        assertEquals(List.of("natural", "binary", "nocase"), enumValues(property("dap.sorter", "collation")));
        assertEquals(List.of("constant", "mean", "mode"), enumValues(property("dap.missing_value", "rules")
                .path("items").path("properties").path("method")));
        assertEquals(List.of("mean", "sum", "count", "min", "max", "count_distinct", "median", "percentile"),
                enumValues(property("dap.group_by", "aggregations").path("items").path("properties").path("fn")));
        assertEquals(List.of("inner", "left", "right", "full", "semi", "anti", "asof", "cross"),
                enumValues(property("dap.joiner", "how")));
        assertEquals(List.of("upper", "lower", "trim", "replace", "concat"),
                enumValues(property("dap.string_manipulation", "op")));
        assertEquals(List.of("replace", "append"), enumValues(property("dap.string_manipulation", "mode")));
    }

    @Test
    void nestedSchemaItemsFollowTheContract() {
        JsonNode rowFilter = property("dap.row_filter", "where").path("items");
        assertTrue(rowFilter.path("properties").has("column"));
        assertTrue(rowFilter.path("properties").has("value"));

        JsonNode sorterKey = property("dap.sorter", "keys").path("items");
        assertEquals("string", sorterKey.path("properties").path("column").path("type").asText());

        JsonNode groupAggregation = property("dap.group_by", "aggregations").path("items");
        assertTrue(groupAggregation.path("properties").has("column"));
        assertTrue(groupAggregation.path("properties").has("fn"));

        JsonNode missingRule = property("dap.missing_value", "rules").path("items");
        assertEquals("string", missingRule.path("properties").path("column").path("type").asText());

        JsonNode rename = property("dap.column_renamer", "renames").path("items");
        assertEquals("string", rename.path("properties").path("from").path("type").asText());
        assertEquals("string", rename.path("properties").path("to").path("type").asText());

        JsonNode output = property("dap.math_formula", "outputs").path("items");
        assertEquals("string", output.path("properties").path("name").path("type").asText());
        assertEquals("string", output.path("properties").path("expression").path("type").asText());

        JsonNode joiner = registry.require("dap.joiner").configSchema().path("properties");
        assertEquals("array", joiner.path("left_keys").path("type").asText());
        assertEquals("string", joiner.path("left_keys").path("items").path("type").asText());
        assertEquals("array", joiner.path("right_keys").path("type").asText());
        assertTrue(joiner.path("merge_keys").path("default").asBoolean());

        JsonNode excel = registry.require("dap.excel_reader").configSchema().path("properties");
        assertEquals("string", excel.path("sheet").path("type").asText());
        assertEquals("boolean", excel.path("emptyStringIsNull").path("type").asText());
    }

    @Test
    void searchFindsCsvNodesBySubstring() {
        List<NodeSpec> hits = registry.search("csv", null, 50);
        assertEquals(2, hits.size());
        assertEquals("dap.csv_reader", hits.get(0).id());
        assertEquals("dap.csv_writer", hits.get(1).id());
        assertEquals(2, registry.search("CSV", null, 50).size(), "search is case-insensitive");
    }

    @Test
    void categoryPrefixFindsAllIoNodes() {
        List<NodeSpec> hits = registry.search(null, "/IO", 50);
        assertEquals(13, hits.size());
        assertEquals(List.of("CSV Reader", "CSV Writer", "Database Reader", "Database Writer",
                "Excel Reader", "Excel Writer", "File List", "File Reader", "JSON Reader",
                "JSON Writer", "Markdown Report", "Parquet Reader", "Parquet Writer"),
                hits.stream().map(NodeSpec::name).toList());
        assertEquals(7, registry.search(null, "/IO/Read", 50).size());
        assertEquals(6, registry.search(null, "/IO/Write", 50).size());
    }

    @Test
    void searchTokensAreCombinedWithAnd() {
        assertEquals(1, registry.search("reader csv", null, 50).size());
        assertEquals(0, registry.search("reader zzz", null, 50).size());
        assertEquals(6, registry.search("reader", "/IO", 50).size());
    }

    @Test
    void limitCapsTheResultInNameOrder() {
        assertEquals(ALL_IDS.size(), registry.search(null, null, -1).size());
        assertEquals(List.of("A/B Test", "Binner", "Calendar"),
                registry.search(null, null, 3).stream().map(NodeSpec::name).toList());
        assertTrue(registry.search(null, null, 0).isEmpty());
    }

    @Test
    void unknownQueryOrCategoryIsEmpty() {
        assertTrue(registry.search("no-such-node", null, 50).isEmpty());
        assertTrue(registry.search(null, "/nope", 50).isEmpty());
    }

    private JsonNode property(String nodeId, String key) {
        return registry.require(nodeId).configSchema().path("properties").path(key);
    }

    private static List<String> enumValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.path("enum").forEach(value -> values.add(value.asText()));
        return values;
    }
}
