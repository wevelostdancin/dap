package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupByExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("grp", "Utf8"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("score", "Float64"));

    private static List<String> row(TableData data, int keyIndex, String key) {
        return data.data().stream()
                .filter(candidate -> Objects.equals(candidate.get(keyIndex), key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for key " + key + " in " + data.data()));
    }

    @Test
    void singleMeanPerGroupNamesColumnsAndValues() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("species", "Utf8"),
                    ColumnSpec.of("sepal_length", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"setosa", 5.1},
                    new Object[]{"setosa", 4.9},
                    new Object[]{"versicolor", 7.0},
                    new Object[]{"virginica", 6.3}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["species"],"aggregations":[{"column":"sepal_length","fn":"mean"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            assertEquals(List.of(ColumnSpec.of("species", "Utf8"),
                            ColumnSpec.of("Mean(sepal_length)", "Float64")),
                    plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("setosa", "5.0"), row(data, 0, "setosa"));
            assertEquals(List.of("versicolor", "7.0"), row(data, 0, "versicolor"));
            assertEquals(List.of("virginica", "6.3"), row(data, 0, "virginica"));
        }
    }

    @Test
    void multipleAggregatesKeepConfigOrderAndTypes() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{"a", 1L, 1.0},
                    new Object[]{"a", 2L, 3.0},
                    new Object[]{"b", 3L, null},
                    new Object[]{"b", null, 5.0}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[
                      {"column":"n","fn":"sum"},
                      {"column":"score","fn":"sum"},
                      {"column":"score","fn":"mean"},
                      {"column":"n","fn":"count"},
                      {"column":"n","fn":"min"},
                      {"column":"score","fn":"max"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            assertEquals(List.of(
                            ColumnSpec.of("grp", "Utf8"),
                            ColumnSpec.of("Sum(n)", "Int64"),
                            ColumnSpec.of("Sum(score)", "Float64"),
                            ColumnSpec.of("Mean(score)", "Float64"),
                            ColumnSpec.of("Count(n)", "Int64"),
                            ColumnSpec.of("Min(n)", "Int64"),
                            ColumnSpec.of("Max(score)", "Float64")),
                    plan.columns());
            assertEquals(List.of("grp", "Sum(n)", "Sum(score)", "Mean(score)", "Count(n)", "Min(n)", "Max(score)"),
                    plan.columns().stream().map(ColumnSpec::name).toList());

            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("a", "3", "4.0", "2.0", "2", "1", "3.0"), row(data, 0, "a"));
            assertEquals(List.of("b", "3", "5.0", "5.0", "1", "3", "5.0"), row(data, 0, "b"));
        }
    }

    @Test
    void nullInputsAreIgnoredAndAllNullGroupIsNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"a", 1.0},
                    new Object[]{"a", null},
                    new Object[]{"b", null},
                    new Object[]{"b", null}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[
                      {"column":"v","fn":"mean"},
                      {"column":"v","fn":"sum"},
                      {"column":"v","fn":"min"},
                      {"column":"v","fn":"max"},
                      {"column":"v","fn":"count"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(List.of("a", "1.0", "1.0", "1.0", "1.0", "1"), row(data, 0, "a"));
            assertEquals(java.util.Arrays.asList("b", null, null, null, null, "0"), row(data, 0, "b"));
        }
    }

    @Test
    void nullGroupKeysCollapseIntoOneGroup() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("k", "Int64"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{null, 1.0},
                    new Object[]{null, 2.0},
                    new Object[]{1L, 5.0}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["k"],"aggregations":[
                      {"column":"v","fn":"count"},
                      {"column":"v","fn":"mean"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(java.util.Arrays.asList(null, "2", "1.5"), row(data, 0, null));
            assertEquals(List.of("1", "1", "5.0"), row(data, 0, "1"));
        }
    }

    @Test
    void countCountsNonNullRowsAndAllNullGroupIsZero() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Int64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"a", 1L},
                    new Object[]{"a", null},
                    new Object[]{"b", null},
                    new Object[]{"b", null}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[{"column":"v","fn":"count"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            assertEquals(List.of(ColumnSpec.of("grp", "Utf8"),
                            ColumnSpec.of("Count(v)", "Int64")),
                    plan.columns());
            assertEquals("Number (Integer)", plan.columns().get(1).knimeType());
            TableData data = plan.preview(10);
            assertEquals(List.of("a", "1"), row(data, 0, "a"));
            assertEquals(List.of("b", "0"), row(data, 0, "b"));
        }
    }

    @Test
    void emptyInputProducesZeroRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of());
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":["grp"],"aggregations":[{"column":"n","fn":"count"}]}
                    """);

            LazyPlan plan = new GroupByExecutor().execute(context, List.of(input));

            assertEquals(List.of("grp", "Count(n)"),
                    plan.columns().stream().map(ColumnSpec::name).toList());
            TableData data = plan.preview(10);
            assertEquals(0, data.rows());
            assertTrue(data.data().isEmpty(), data.data().toString());
        }
    }

    @Test
    void emptyKeysAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.<Object[]>of(
                    new Object[]{"a", 1L, 1.0}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":[],"aggregations":[{"column":"n","fn":"sum"}]}
                            """), List.of(input)));
            assertTrue(failure.getMessage().contains("'keys'"), failure.getMessage());
            assertTrue(failure.getMessage().contains("at least one"), failure.getMessage());
        }
    }

    @Test
    void invalidConfigIsRejectedWithClearMessages() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.<Object[]>of(
                    new Object[]{"a", 1L, 1.0}));

            IllegalArgumentException unknownKey = assertThrows(IllegalArgumentException.class,
                    () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":["nope"],"aggregations":[]}
                            """), List.of(input)));
            assertTrue(unknownKey.getMessage().contains("nope"), unknownKey.getMessage());
            assertTrue(unknownKey.getMessage().contains("key"), unknownKey.getMessage());

            IllegalArgumentException unknownAggregate = assertThrows(IllegalArgumentException.class,
                    () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":["grp"],"aggregations":[{"column":"missing","fn":"mean"}]}
                            """), List.of(input)));
            assertTrue(unknownAggregate.getMessage().contains("missing"), unknownAggregate.getMessage());
            assertTrue(unknownAggregate.getMessage().contains("aggregate"), unknownAggregate.getMessage());

            IllegalArgumentException badFunction = assertThrows(IllegalArgumentException.class,
                    () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":["grp"],"aggregations":[{"column":"n","fn":"median"}]}
                            """), List.of(input)));
            assertTrue(badFunction.getMessage().contains("median"), badFunction.getMessage());

            IllegalArgumentException nonNumericMean = assertThrows(IllegalArgumentException.class,
                    () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":["grp"],"aggregations":[{"column":"grp","fn":"mean"}]}
                            """), List.of(input)));
            assertTrue(nonNumericMean.getMessage().contains("mean"), nonNumericMean.getMessage());
            assertTrue(nonNumericMean.getMessage().contains("grp"), nonNumericMean.getMessage());

            IllegalArgumentException keysNotArray = assertThrows(IllegalArgumentException.class,
                    () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":"grp","aggregations":[]}
                            """), List.of(input)));
            assertTrue(keysNotArray.getMessage().contains("'keys'"), keysNotArray.getMessage());

            IllegalArgumentException aggregationsNotArray = assertThrows(IllegalArgumentException.class,
                    () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":["grp"],"aggregations":"count"}
                            """), List.of(input)));
            assertTrue(aggregationsNotArray.getMessage().contains("'aggregations'"),
                    aggregationsNotArray.getMessage());

            IllegalArgumentException missingAggregationColumn = assertThrows(IllegalArgumentException.class,
                    () -> new GroupByExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":["grp"],"aggregations":[{"fn":"count"}]}
                            """), List.of(input)));
            assertTrue(missingAggregationColumn.getMessage().contains("column"),
                    missingAggregationColumn.getMessage());
        }
    }
}
