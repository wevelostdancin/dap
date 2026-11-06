package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcatenateExecutorTest {
    private static final List<ColumnSpec> LEFT_COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"));
    private static final List<ColumnSpec> RIGHT_COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("score", "Float64"));

    private static List<String> canonicalRows(TableData data) {
        return data.data().stream().map(row -> {
            StringJoiner joiner = new StringJoiner("|");
            for (String value : row) {
                joiner.add(value == null ? "<null>" : value);
            }
            return joiner.toString();
        }).sorted().toList();
    }

    @Test
    void nameModeMatchesColumnsByNameAndKeepsEveryColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT_COLUMNS, List.of(
                    new Object[]{1L, "a"},
                    new Object[]{2L, "b"}));
            LazyPlan right = NodeTestSupport.table(db, RIGHT_COLUMNS,
                    List.<Object[]>of(new Object[]{3L, 1.5}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"mode":"name"}
                    """);

            LazyPlan plan = new ConcatenateExecutor().execute(context, List.of(left, right));

            assertEquals(List.of(ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("name", "Utf8"),
                    ColumnSpec.of("score", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("1|a|<null>", "2|b|<null>", "3|<null>|1.5"), canonicalRows(data));
        }
    }

    @Test
    void positionModeStacksRowsAndKeepsLeftNames() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> leftColumns = List.of(
                    ColumnSpec.of("id", "Int64"), ColumnSpec.of("label", "Utf8"));
            List<ColumnSpec> rightColumns = List.of(
                    ColumnSpec.of("id", "Int64"), ColumnSpec.of("label", "Utf8"));
            LazyPlan left = NodeTestSupport.table(db, leftColumns,
                    List.<Object[]>of(new Object[]{1L, "a"}));
            LazyPlan right = NodeTestSupport.table(db, rightColumns,
                    List.<Object[]>of(new Object[]{2L, "b"}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"mode":"position"}
                    """);

            LazyPlan plan = new ConcatenateExecutor().execute(context, List.of(left, right));

            assertEquals(leftColumns, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("1|a", "2|b"), canonicalRows(data));
        }
    }

    @Test
    void positionModeRequiresTheSameColumnCount() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT_COLUMNS,
                    List.<Object[]>of(new Object[]{1L, "a"}));
            LazyPlan right = NodeTestSupport.table(db, List.of(
                            ColumnSpec.of("id", "Int64"),
                            ColumnSpec.of("score", "Float64"),
                            ColumnSpec.of("extra", "Utf8")),
                    List.<Object[]>of(new Object[]{2L, 1.5, "x"}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ConcatenateExecutor().execute(NodeTestSupport.context(db, """
                            {"mode":"position"}
                            """), List.of(left, right)));
            assertTrue(failure.getMessage().contains("same number of columns"), failure.getMessage());
            assertTrue(failure.getMessage().contains("2"), failure.getMessage());
            assertTrue(failure.getMessage().contains("3"), failure.getMessage());
        }
    }

    @Test
    void missingInputAndUnknownModeAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT_COLUMNS,
                    List.<Object[]>of(new Object[]{1L, "a"}));

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new ConcatenateExecutor().execute(NodeTestSupport.context(db, """
                            {"mode":"name"}
                            """), List.of(left)));
            assertTrue(missing.getMessage().contains("both input ports"), missing.getMessage());

            LazyPlan right = NodeTestSupport.table(db, RIGHT_COLUMNS,
                    List.<Object[]>of(new Object[]{2L, 1.0}));
            IllegalArgumentException mode = assertThrows(IllegalArgumentException.class,
                    () -> new ConcatenateExecutor().execute(NodeTestSupport.context(db, """
                            {"mode":"diagonal"}
                            """), List.of(left, right)));
            assertTrue(mode.getMessage().contains("diagonal"), mode.getMessage());
        }
    }

    @Test
    void emptyRightTableStillYieldsTheLeftRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT_COLUMNS,
                    List.<Object[]>of(new Object[]{1L, "a"}));
            LazyPlan right = NodeTestSupport.table(db, RIGHT_COLUMNS, List.of());
            NodeContext context = NodeTestSupport.context(db, """
                    {"mode":"name"}
                    """);

            LazyPlan plan = new ConcatenateExecutor().execute(context, List.of(left, right));

            assertEquals(1, plan.preview(10).rows());
            assertEquals(List.of("1|a|<null>"), canonicalRows(plan.preview(10)));
        }
    }
}
