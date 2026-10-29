package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MissingValueExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("comment", "Utf8"),
            ColumnSpec.of("score", "Float64"));

    @Test
    void constantReplacesOnlyTargetColumnNulls() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1L, "keep", null},
                    new Object[]{2L, null, 2.5}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"rules":[{"column":"comment","method":"constant","value":"n/a"}]}
                    """);

            LazyPlan plan = new MissingValueExecutor().execute(context, List.of(input));

            assertEquals(COLUMNS, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(Arrays.asList("1", "keep", null), data.data().get(0));
            assertEquals(List.of("2", "n/a", "2.5"), data.data().get(1));
        }
    }

    @Test
    void meanFillsWithAverageOfTheColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1L, "a", null},
                    new Object[]{2L, "b", 2.5},
                    new Object[]{3L, "c", 1.0}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"rules":[{"column":"score","method":"mean"}]}
                    """);

            LazyPlan plan = new MissingValueExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals("1.75", data.data().get(0).get(2));
            assertEquals("2.5", data.data().get(1).get(2));
            assertEquals(List.of("1", "a"), data.data().get(0).subList(0, 2));
        }
    }

    @Test
    void modeUsesMostFrequentNonNullValueWithAscendingTieBreak() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan repeated = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1L, "b", null},
                    new Object[]{2L, "a", null},
                    new Object[]{3L, "b", null},
                    new Object[]{4L, null, null}));
            LazyPlan byMode = new MissingValueExecutor().execute(NodeTestSupport.context(db, """
                    {"rules":[{"column":"comment","method":"mode"}]}
                    """), List.of(repeated));
            TableData modeData = byMode.preview(10);
            assertEquals("b", modeData.data().get(3).get(1));
            assertEquals(COLUMNS, byMode.columns());

            LazyPlan tied = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1L, "b", null},
                    new Object[]{2L, "a", null},
                    new Object[]{3L, null, null}));
            LazyPlan byTie = new MissingValueExecutor().execute(NodeTestSupport.context(db, """
                    {"rules":[{"column":"comment","method":"mode"}]}
                    """), List.of(tied));
            assertEquals("a", byTie.preview(10).data().get(2).get(1));
        }
    }

    @Test
    void rulesApplyInOrderAndOtherColumnsStayUntouched() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1L, null, null},
                    new Object[]{2L, "x", 4.0}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"rules":[
                      {"column":"comment","method":"constant","value":"filled"},
                      {"column":"score","method":"constant","value":0.0}]}
                    """);

            LazyPlan plan = new MissingValueExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(List.of("1", "filled", "0.0"), data.data().get(0));
            assertEquals(List.of("2", "x", "4.0"), data.data().get(1));
            assertEquals(COLUMNS, plan.columns());
        }
    }

    @Test
    void unknownColumnInvalidMethodAndMeanOnIntegerFail() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.<Object[]>of(
                    new Object[]{1L, "a", 1.0}));

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> new MissingValueExecutor().execute(NodeTestSupport.context(db, """
                            {"rules":[{"column":"nope","method":"constant","value":1}]}
                            """), List.of(input)));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException badMethod = assertThrows(IllegalArgumentException.class,
                    () -> new MissingValueExecutor().execute(NodeTestSupport.context(db, """
                            {"rules":[{"column":"comment","method":"median"}]}
                            """), List.of(input)));
            assertTrue(badMethod.getMessage().contains("median"), badMethod.getMessage());

            IllegalArgumentException meanOnInteger = assertThrows(IllegalArgumentException.class,
                    () -> new MissingValueExecutor().execute(NodeTestSupport.context(db, """
                            {"rules":[{"column":"id","method":"mean"}]}
                            """), List.of(input)));
            assertTrue(meanOnInteger.getMessage().contains("mean"), meanOnInteger.getMessage());
            assertTrue(meanOnInteger.getMessage().contains("id"), meanOnInteger.getMessage());
        }
    }
}
