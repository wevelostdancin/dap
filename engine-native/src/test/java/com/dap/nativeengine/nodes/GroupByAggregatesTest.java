package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dap.group_by count_distinct, median and percentile aggregates. */
class GroupByAggregatesTest {
    private static List<String> row(TableData data, int keyIndex, String key) {
        return data.data().stream()
                .filter(candidate -> Objects.equals(candidate.get(keyIndex), key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for key " + key + " in " + data.data()));
    }

    @Test
    void countDistinctIgnoresNullsAndReturnsZeroForAllNullGroup() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Int64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"a", 1L},
                    new Object[]{"a", 2L},
                    new Object[]{"a", null},
                    new Object[]{"a", 2L},
                    new Object[]{"b", null},
                    new Object[]{"b", null},
                    new Object[]{"c", 7L}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[{"column":"v","fn":"count_distinct"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            assertEquals(List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("CountDistinct(v)", "Int64")), plan.columns());
            assertEquals("Number (Integer)", plan.columns().get(1).knimeType());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("a", "2"), row(data, 0, "a"));
            assertEquals(List.of("b", "0"), row(data, 0, "b"));
            assertEquals(List.of("c", "1"), row(data, 0, "c"));
        }
    }

    @Test
    void countDistinctWorksOnTextColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("s", "Utf8"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"a", "x"},
                    new Object[]{"a", "y"},
                    new Object[]{"a", "x"},
                    new Object[]{"a", null}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[{"column":"s","fn":"count_distinct"}]}
                    """);

            TableData data = new GroupByExecutor().execute(context, List.of(input)).preview(10);

            assertEquals(List.of("a", "2"), row(data, 0, "a"));
        }
    }

    @Test
    void medianAveragesTheTwoMiddleValuesAndIgnoresNulls() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"even", 4.0},
                    new Object[]{"even", 1.0},
                    new Object[]{"even", 3.0},
                    new Object[]{"even", 2.0},
                    new Object[]{"odd", 10.0},
                    new Object[]{"odd", 100.0},
                    new Object[]{"odd", 20.0},
                    new Object[]{"odd", null},
                    new Object[]{"single", 5.0},
                    new Object[]{"empty", null},
                    new Object[]{"empty", null}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[{"column":"v","fn":"median"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            assertEquals(List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("Median(v)", "Float64")), plan.columns());
            assertEquals("Number (Float)", plan.columns().get(1).knimeType());
            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            assertEquals(List.of("even", "2.5"), row(data, 0, "even"));
            assertEquals(List.of("odd", "20.0"), row(data, 0, "odd"));
            assertEquals(List.of("single", "5.0"), row(data, 0, "single"));
            assertNull(row(data, 0, "empty").get(1));
        }
    }

    @Test
    void percentileInterpolatesAndIsNamedWithItsProbability() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            List<Object[]> rows = new java.util.ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                rows.add(new Object[]{"a", (double) i});
            }
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[
                      {"column":"v","fn":"percentile","p":0.9},
                      {"column":"v","fn":"percentile","p":0.0},
                      {"column":"v","fn":"percentile","p":1.0}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            assertEquals(List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("Percentile(v, 0.9)", "Float64"),
                    ColumnSpec.of("Percentile(v, 0.0)", "Float64"),
                    ColumnSpec.of("Percentile(v, 1.0)", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals(List.of("a", "9.1", "1.0", "10.0"), row(data, 0, "a"));
        }
    }

    @Test
    void percentileRejectsMissingAndOutOfBoundsProbabilities() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.<Object[]>of(
                    new Object[]{"a", 1.0}));

            for (String aggregation : List.of(
                    "{\"column\":\"v\",\"fn\":\"percentile\"}",
                    "{\"column\":\"v\",\"fn\":\"percentile\",\"p\":-0.1}",
                    "{\"column\":\"v\",\"fn\":\"percentile\",\"p\":1.1}",
                    "{\"column\":\"v\",\"fn\":\"percentile\",\"p\":\"0.5\"}")) {
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> new GroupByExecutor().execute(NodeTestSupport.context(db,
                                "{\"keys\":[\"grp\"],\"aggregations\":[" + aggregation + "]}"),
                                List.of(input)), aggregation);
                assertTrue(failure.getMessage().contains("p"), failure.getMessage());
                assertTrue(failure.getMessage().contains("percentile"), failure.getMessage());
            }
        }
    }

    @Test
    void decimalInputIsCastToDoubleForMedianAndPercentile() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("d", "Decimal128(10,2)"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"a", new BigDecimal("1.25")},
                    new Object[]{"a", new BigDecimal("2.50")},
                    new Object[]{"a", new BigDecimal("3.00")}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[
                      {"column":"d","fn":"median"},
                      {"column":"d","fn":"percentile","p":0.5},
                      {"column":"d","fn":"count_distinct"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            assertEquals(List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("Median(d)", "Float64"),
                    ColumnSpec.of("Percentile(d, 0.5)", "Float64"),
                    ColumnSpec.of("CountDistinct(d)", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("a", "2.5", "2.5", "3"), row(data, 0, "a"));
        }
    }

    @Test
    void medianAndPercentileRequireNumericColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("s", "Utf8"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.<Object[]>of(
                    new Object[]{"a", "x"}));

            for (String fn : List.of("median", "percentile")) {
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                                {"keys":["grp"],"aggregations":[
                                  {"column":"s","fn":"%s","p":0.5}]}
                                """.formatted(fn)), List.of(input)), fn);
                assertTrue(failure.getMessage().contains(fn), failure.getMessage());
                assertTrue(failure.getMessage().contains("numeric"), failure.getMessage());
            }
        }
    }
}
