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

class NormalizerExecutorTest {
    private static LazyPlan input(DuckDbSession db, double... values) throws Exception {
        List<Object[]> rows = java.util.Arrays.stream(values).mapToObj(value -> new Object[]{value}).toList();
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Float64"), ColumnSpec.of("id", "Int64")),
                rows.stream().map(row -> new Object[]{row[0], 1L}).toList());
    }

    @Test
    void minMaxScalesToUnitRangeAndKeepsSourceColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new NormalizerExecutor().execute(NodeTestSupport.context(db, """
                    {"columns":[{"column":"v"}],"method":"minmax"}
                    """), List.of(input(db, 0, 5, 10)));

            assertEquals(List.of(ColumnSpec.of("v", "Float64"), ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("v_norm", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals("0.0", data.data().get(0).get(2));
            assertEquals("0.5", data.data().get(1).get(2));
            assertEquals("1.0", data.data().get(2).get(2));
        }
    }

    @Test
    void zScoreUsesMeanAndStandardDeviationWithCustomOutput() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new NormalizerExecutor().execute(NodeTestSupport.context(db, """
                    {"columns":[{"column":"v","output":"vz"}],"method":"zscore"}
                    """), List.of(input(db, 1, 2, 3)));

            assertEquals(ColumnSpec.of("vz", "Float64"), plan.columns().get(2));
            TableData data = plan.preview(10);
            assertEquals("-1.0", data.data().get(0).get(2));
            assertEquals("0.0", data.data().get(1).get(2));
            assertEquals("1.0", data.data().get(2).get(2));
        }
    }

    @Test
    void constantColumnHasUndefinedNormalisation() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new NormalizerExecutor().execute(NodeTestSupport.context(db, """
                    {"columns":[{"column":"v"}],"method":"minmax"}
                    """), List.of(input(db, 7, 7, 7)));

            TableData data = plan.preview(10);
            assertNull(data.data().get(0).get(2));
            assertNull(data.data().get(1).get(2));
        }
    }

    @Test
    void invalidColumnsAndMethodAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db, 1, 2);

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> new NormalizerExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[],"method":"minmax"}
                            """), List.of(input)));
            assertTrue(empty.getMessage().contains("columns"), empty.getMessage());

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> new NormalizerExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[{"column":"v"}],"method":"robust"}
                            """), List.of(input)));
            assertTrue(method.getMessage().contains("robust"), method.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> new NormalizerExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[{"column":"v","output":"id"}],"method":"minmax"}
                            """), List.of(input)));
            assertTrue(duplicate.getMessage().contains("id"), duplicate.getMessage());

            LazyPlan text = NodeTestSupport.table(db, List.of(ColumnSpec.of("s", "Utf8")),
                    List.<Object[]>of(new Object[]{"a"}));
            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> new NormalizerExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[{"column":"s"}],"method":"minmax"}
                            """), List.of(text)));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());
        }
    }
}
