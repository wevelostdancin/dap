package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinnerExecutorTest {
    private static LazyPlan ints(DuckDbSession db, long... values) throws Exception {
        List<Object[]> rows = java.util.Arrays.stream(values).mapToObj(value -> new Object[]{value}).toList();
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Int64")), rows);
    }

    private static List<String> bins(LazyPlan plan, int index) throws Exception {
        return plan.preview(20).data().stream().map(row -> row.get(index)).toList();
    }

    @Test
    void equalWidthBinsUseMinMaxAndClampTheMaximumIntoTheLastBin() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new BinnerExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"v","output":"bin","method":"width","bins":2}
                    """), List.of(ints(db, 0, 1, 2, 3, 4)));

            assertEquals(List.of(ColumnSpec.of("v", "Int64"), ColumnSpec.of("bin", "Int64")),
                    plan.columns());
            assertEquals(List.of("0", "0", "1", "1", "1"), bins(plan, 1));
        }
    }

    @Test
    void constantColumnFallsIntoTheFirstBinAndNullsStayNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new BinnerExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"v","output":"bin","method":"width","bins":3}
                    """), List.of(NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Int64")),
                    List.of(new Object[]{7L}, new Object[]{7L}, new Object[]{null}))));

            TableData data = plan.preview(10);
            assertEquals("0", data.data().get(0).get(1));
            assertEquals("0", data.data().get(1).get(1));
            assertNull(data.data().get(2).get(1));
        }
    }

    @Test
    void customEdgesDefineHalfOpenBinsAndOutsideValuesClampToTheEdgeBins() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new BinnerExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"v","output":"bin","method":"custom","edges":[0,10,20,30]}
                    """), List.of(ints(db, -5, 5, 15, 25, 35)));

            assertEquals(List.of("0", "0", "1", "2", "2"), bins(plan, 1));
        }
    }

    @Test
    void frequencyBinsUseNtileOverTheOrderedColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new BinnerExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"v","output":"bin","method":"frequency","bins":2}
                    """), List.of(ints(db, 10, 20, 30, 40, 50)));

            assertEquals(List.of("1", "1", "1", "2", "2"), bins(plan, 1));
        }
    }

    @Test
    void frequencyBinsIgnoreNullsSoTheyDoNotShiftTheTiles() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // Non-NULL values 10, 20, 30 split into [10,20] and [30] (numpy.array_split).
            // NULLs are returned as NULL and must not occupy a tile.
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Int64")),
                    List.<Object[]>of(new Object[]{10L}, new Object[]{null}, new Object[]{30L},
                            new Object[]{20L}, new Object[]{null}));
            LazyPlan plan = new BinnerExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"v","output":"bin","method":"frequency","bins":2}
                    """), List.of(input));

            assertEquals(List.of(ColumnSpec.of("v", "Int64"), ColumnSpec.of("bin", "Int64")),
                    plan.columns());
            assertEquals(java.util.Arrays.asList("1", null, "2", "1", null), bins(plan, 1));
        }
    }

    @Test
    void invalidBinsEdgesAndColumnsAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = ints(db, 1, 2, 3);

            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                    () -> new BinnerExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"bin","method":"width","bins":0}
                            """), List.of(input)));
            assertTrue(zero.getMessage().contains("bins"), zero.getMessage());

            IllegalArgumentException missingEdges = assertThrows(IllegalArgumentException.class,
                    () -> new BinnerExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"bin","method":"custom","edges":[0]}
                            """), List.of(input)));
            assertTrue(missingEdges.getMessage().contains("edges"), missingEdges.getMessage());

            IllegalArgumentException unsorted = assertThrows(IllegalArgumentException.class,
                    () -> new BinnerExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"bin","method":"custom","edges":[0,10,5]}
                            """), List.of(input)));
            assertTrue(unsorted.getMessage().contains("ascending"), unsorted.getMessage());

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> new BinnerExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"bin","method":"quantile","bins":2}
                            """), List.of(input)));
            assertTrue(method.getMessage().contains("quantile"), method.getMessage());

            LazyPlan text = NodeTestSupport.table(db, List.of(ColumnSpec.of("s", "Utf8")),
                    List.<Object[]>of(new Object[]{"a"}));
            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> new BinnerExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"s","output":"bin","method":"width","bins":2}
                            """), List.of(text)));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());
        }
    }
}
