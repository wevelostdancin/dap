package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RowFilterExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("note", "Utf8"),
            ColumnSpec.of("score", "Float64"));

    private static List<Object[]> rows() {
        return List.of(
                new Object[]{1L, null, 1.5},
                new Object[]{2L, "", 2.5},
                new Object[]{3L, "x", null});
    }

    private static List<String> ids(TableData data) {
        return data.data().stream().map(row -> row.get(0)).toList();
    }

    @Test
    void numericComparisonFiltersRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows());
            NodeContext context = NodeTestSupport.context(db, """
                    {"where":[{"column":"id","op":"gt","value":1}]}
                    """);

            LazyPlan plan = new RowFilterExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(List.of("2", "3"), ids(data));
            assertEquals(COLUMNS, plan.columns());
        }
    }

    @Test
    void isNullNotNilAndEmptyStringAreDistinct() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows());

            LazyPlan missing = new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                    {"where":[{"column":"note","op":"is_null"}]}
                    """), List.of(input));
            assertEquals(List.of("1"), ids(missing.preview(10)));

            LazyPlan present = new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                    {"where":[{"column":"note","op":"not_null"}]}
                    """), List.of(input));
            assertEquals(List.of("2", "3"), ids(present.preview(10)));

            LazyPlan empty = new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                    {"where":[{"column":"note","op":"eq","value":""}]}
                    """), List.of(input));
            assertEquals(List.of("2"), ids(empty.preview(10)));

            // three-valued logic: NULL <> '' is unknown, so the NULL row is not returned
            LazyPlan notEmpty = new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                    {"where":[{"column":"note","op":"ne","value":""}]}
                    """), List.of(input));
            assertEquals(List.of("3"), ids(notEmpty.preview(10)));
        }
    }

    @Test
    void andOrCombinationsChangeMatchSet() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows());

            LazyPlan andPlan = new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                    {"logic":"and","where":[
                      {"column":"note","op":"eq","value":"x"},
                      {"column":"id","op":"lt","value":3}]}
                    """), List.of(input));
            assertEquals(0, andPlan.count());

            LazyPlan orPlan = new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                    {"logic":"or","where":[
                      {"column":"note","op":"eq","value":"x"},
                      {"column":"id","op":"lt","value":3}]}
                    """), List.of(input));
            assertEquals(List.of("1", "2", "3"), ids(orPlan.preview(10)));
        }
    }

    @Test
    void emptyWhereIsPassThrough() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows());

            LazyPlan plan = new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                    {"where":[]}
                    """), List.of(input));

            assertEquals(3, plan.count());
            assertEquals(COLUMNS, plan.columns());
            assertEquals(List.of("1", "2", "3"), ids(plan.preview(10)));
        }
    }

    @Test
    void unknownColumnAndUnknownOperatorFail() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows());

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                            {"where":[{"column":"nope","op":"eq","value":1}]}
                            """), List.of(input)));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException unknownOp = assertThrows(IllegalArgumentException.class,
                    () -> new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                            {"where":[{"column":"id","op":"like","value":1}]}
                            """), List.of(input)));
            assertTrue(unknownOp.getMessage().contains("like"), unknownOp.getMessage());
        }
    }

    @Test
    void nonFiniteFloatValuesAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows());

            IllegalArgumentException nan = assertThrows(IllegalArgumentException.class,
                    () -> new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                            {"where":[{"column":"score","op":"eq","value":"NaN"}]}
                            """), List.of(input)));
            assertTrue(nan.getMessage().contains("finite"), nan.getMessage());
            assertTrue(nan.getMessage().contains("score"), nan.getMessage());

            IllegalArgumentException infinity = assertThrows(IllegalArgumentException.class,
                    () -> new RowFilterExecutor().execute(NodeTestSupport.context(db, """
                            {"where":[{"column":"score","op":"gt","value":"Infinity"}]}
                            """), List.of(input)));
            assertTrue(infinity.getMessage().contains("finite"), infinity.getMessage());
        }
    }
}
