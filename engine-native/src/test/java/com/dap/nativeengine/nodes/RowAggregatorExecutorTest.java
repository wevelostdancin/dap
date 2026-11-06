package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowAggregatorExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("grp", "Utf8"),
            ColumnSpec.of("a", "Int64"),
            ColumnSpec.of("b", "Int64"),
            ColumnSpec.of("c", "Float64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{"x", 1L, 2L, 3.0},
            new Object[]{"y", null, 4L, 1.0});

    private static List<String> row(TableData data, String grp) {
        return data.data().stream()
                .filter(candidate -> Objects.equals(candidate.get(0), grp))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + grp + " in " + data.data()));
    }

    @Test
    void defaultAggregatesEveryNumericColumnAndKeepsInputColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"aggregations":[
                      {"fn":"sum"},{"fn":"mean"},{"fn":"min"},{"fn":"max"},{"fn":"count"}]}
                    """);

            LazyPlan plan = new RowAggregatorExecutor().execute(context, List.of(input));

            assertEquals(List.of(
                            ColumnSpec.of("grp", "Utf8"),
                            ColumnSpec.of("a", "Int64"),
                            ColumnSpec.of("b", "Int64"),
                            ColumnSpec.of("c", "Float64"),
                            ColumnSpec.of("sum", "Float64"),
                            ColumnSpec.of("mean", "Float64"),
                            ColumnSpec.of("min", "Float64"),
                            ColumnSpec.of("max", "Float64"),
                            ColumnSpec.of("count", "Int64")),
                    plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("x", "1", "2", "3.0", "6.0", "2.0", "1.0", "3.0", "3"), row(data, "x"));
        }
    }

    @Test
    void explicitColumnsRestrictTheAggregateAndIntColumnsStayInt64() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"columns":["a","b"],"aggregations":[
                      {"fn":"sum","output":"total"},{"fn":"mean"}]}
                    """);

            LazyPlan plan = new RowAggregatorExecutor().execute(context, List.of(input));

            assertEquals(List.of(ColumnSpec.of("total", "Int64"), ColumnSpec.of("mean", "Float64")),
                    plan.columns().subList(4, 6));
            TableData data = plan.preview(10);
            assertEquals(List.of("x", "1", "2", "3.0", "3", "1.5"), row(data, "x"));
        }
    }

    @Test
    void nullsPropagateForSumAndMeanButCountIgnoresThem() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"aggregations":[{"fn":"sum"},{"fn":"mean"},{"fn":"count"}]}
                    """);

            LazyPlan plan = new RowAggregatorExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(Arrays.asList("y", null, "4", "1.0", null, null, "2"), row(data, "y"));
        }
    }

    @Test
    void invalidConfigIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException unknownFunction = assertThrows(IllegalArgumentException.class,
                    () -> new RowAggregatorExecutor().execute(NodeTestSupport.context(db, """
                            {"aggregations":[{"fn":"median"}]}
                            """), List.of(input)));
            assertTrue(unknownFunction.getMessage().contains("median"), unknownFunction.getMessage());

            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> new RowAggregatorExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":["grp"],"aggregations":[{"fn":"sum"}]}
                            """), List.of(input)));
            assertTrue(nonNumeric.getMessage().contains("grp"), nonNumeric.getMessage());
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> new RowAggregatorExecutor().execute(NodeTestSupport.context(db, """
                            {"aggregations":[]}
                            """), List.of(input)));
            assertTrue(empty.getMessage().contains("'aggregations'"), empty.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> new RowAggregatorExecutor().execute(NodeTestSupport.context(db, """
                            {"aggregations":[{"fn":"sum"},{"fn":"sum"}]}
                            """), List.of(input)));
            assertTrue(duplicate.getMessage().contains("duplicates"), duplicate.getMessage());

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> new RowAggregatorExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":["nope"],"aggregations":[{"fn":"sum"}]}
                            """), List.of(input)));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());
        }
    }

    @Test
    void inputWithoutNumericColumnsIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("only_text", "Utf8")),
                    List.<Object[]>of(new Object[]{"a"}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new RowAggregatorExecutor().execute(NodeTestSupport.context(db, """
                            {"aggregations":[{"fn":"count"}]}
                            """), List.of(input)));
            assertTrue(failure.getMessage().contains("no numeric columns"), failure.getMessage());
        }
    }
}
