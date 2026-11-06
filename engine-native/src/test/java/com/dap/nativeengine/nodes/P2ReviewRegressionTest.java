package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the issues found in the P2 independent review. */
class P2ReviewRegressionTest {
    @Test
    void rowAggregatorMinAndMaxPropagateNulls() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("a", "Int64"), ColumnSpec.of("b", "Int64"));
            List<Object[]> rows = List.of(
                    new Object[]{"x", 1L, 2L},
                    new Object[]{"y", null, 4L});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"aggregations":[{"fn":"min"},{"fn":"max"}]}
                    """);

            LazyPlan plan = new RowAggregatorExecutor().execute(context, List.of(input));
            TableData data = plan.preview(10);
            List<String> y = data.data().stream()
                    .filter(row -> "y".equals(row.get(0)))
                    .findFirst()
                    .orElseThrow();
            assertEquals(Arrays.asList("y", null, "4", null, null), y,
                    "min/max must be NULL when any selected column is NULL");
        }
    }

    @Test
    void ruleEngineRejectsAggregateConditionAtBuildTime() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("x", "Int64"));
            List<Object[]> rows = List.<Object[]>of(new Object[]{1L}, new Object[]{2L});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"output":"flag","rules":[{"condition":"avg(x) > 1","value":1}],"elseValue":0}
                    """);

            assertThrows(IllegalArgumentException.class,
                    () -> new RuleEngineExecutor().execute(context, List.of(input)));
        }
    }

    @Test
    void dateTimeTime64ExtractOnlyAllowsHour() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("t", "Time64"));
            List<Object[]> rows = List.<Object[]>of(new Object[]{LocalTime.of(12, 30)});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"column":"t","output":"y","op":"extract","unit":"year"}
                    """);

            assertThrows(IllegalArgumentException.class,
                    () -> new DateTimeExecutor().execute(context, List.of(input)));
        }
    }

    @Test
    void pivotMatchesNumericValuesByTypedLiteral() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("id", "Utf8"),
                    ColumnSpec.of("bucket", "Int64"), ColumnSpec.of("v", "Float64"));
            List<Object[]> rows = List.of(
                    new Object[]{"a", 1L, 2.0},
                    new Object[]{"a", 2L, 3.0});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"index":["id"],"pivotColumn":"bucket","valueColumn":"v",
                     "values":["1","2"],"agg":"sum"}
                    """);

            LazyPlan plan = new PivotExecutor().execute(context, List.of(input));
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals(Arrays.asList("a", "2.0", "3.0"), data.data().get(0));
        }
    }

    @Test
    void binnerFrequencyKeepsNullAsNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Int64"));
            List<Object[]> rows = List.of(new Object[]{1L}, new Object[]{null}, new Object[]{3L});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"column":"v","output":"bin","method":"frequency","bins":2}
                    """);

            LazyPlan plan = new BinnerExecutor().execute(context, List.of(input));
            TableData data = plan.preview(10);
            assertTrue(data.data().stream().anyMatch(row -> row.get(1) == null),
                    "frequency bins must stay NULL for NULL input");
        }
    }

    @Test
    void typeMapNormalisesDecimalSpacing() {
        assertEquals("Decimal128(18,4)", TypeMap.canonical("Decimal128(18, 4)"));
        assertEquals("Decimal128(18,4)", TypeMap.canonical("Decimal128(18,4)"));
    }
}
