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

/** dap.joiner asof and cross modes. */
class JoinerAsofTest {
    private static final List<ColumnSpec> LEFT = List.of(
            ColumnSpec.of("symbol", "Utf8"), ColumnSpec.of("ts", "Int64"),
            ColumnSpec.of("price", "Float64"));
    private static final List<Object[]> LEFT_ROWS = List.of(
            new Object[]{"AAA", 600L, 1.0},
            new Object[]{"AAA", 900L, 2.0},
            new Object[]{"BBB", 780L, 9.0});
    private static final List<ColumnSpec> RIGHT = List.of(
            ColumnSpec.of("symbol", "Utf8"), ColumnSpec.of("ts", "Int64"),
            ColumnSpec.of("quote", "Float64"));
    private static final List<Object[]> RIGHT_ROWS = List.of(
            new Object[]{"AAA", 660L, 100.0},
            new Object[]{"AAA", 840L, 200.0},
            new Object[]{"BBB", 600L, 900.0});

    private static String canonical(List<String> row) {
        StringJoiner joiner = new StringJoiner("|");
        for (String value : row) {
            joiner.add(value == null ? "<null>" : value);
        }
        return joiner.toString();
    }

    private static List<String> rows(TableData data) {
        return data.data().stream().map(JoinerAsofTest::canonical).sorted().toList();
    }

    private static LazyPlan join(DuckDbSession db, LazyPlan left, LazyPlan right, String config)
            throws Exception {
        NodeContext context = NodeTestSupport.context(db, config);
        return new JoinerExecutor().execute(context, List.of(left, right));
    }

    @Test
    void asofJoinPicksTheNearestPreviousRow() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, NodeTestSupport.table(db, LEFT, LEFT_ROWS),
                    NodeTestSupport.table(db, RIGHT, RIGHT_ROWS), """
                    {"how":"asof","left_time":"ts","right_time":"ts","asof_inequality":">=",
                     "left_keys":["symbol"],"right_keys":["symbol"]}
                    """);

            assertEquals(List.of(ColumnSpec.of("symbol", "Utf8"), ColumnSpec.of("ts", "Int64"),
                    ColumnSpec.of("price", "Float64"), ColumnSpec.of("symbol (Right)", "Utf8"),
                    ColumnSpec.of("ts (Right)", "Int64"), ColumnSpec.of("quote", "Float64")),
                    plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("AAA|600|1.0|<null>|<null>|<null>",
                    "AAA|900|2.0|AAA|840|200.0",
                    "BBB|780|9.0|BBB|600|900.0"), rows(data));
        }
    }

    @Test
    void asofJoinSupportsTheFollowingRowWithLessOrEqual() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, NodeTestSupport.table(db, LEFT, LEFT_ROWS),
                    NodeTestSupport.table(db, RIGHT, RIGHT_ROWS), """
                    {"how":"asof","left_time":"ts","right_time":"ts","asof_inequality":"<=",
                     "left_keys":["symbol"],"right_keys":["symbol"]}
                    """);

            TableData data = plan.preview(10);
            assertEquals(List.of("AAA|600|1.0|AAA|660|100.0",
                    "AAA|900|2.0|<null>|<null>|<null>",
                    "BBB|780|9.0|<null>|<null>|<null>"), rows(data));
        }
    }

    @Test
    void asofJoinWithoutTimeColumnsIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> join(db, NodeTestSupport.table(db, LEFT, LEFT_ROWS),
                            NodeTestSupport.table(db, RIGHT, RIGHT_ROWS), "{\"how\":\"asof\"}"));
            assertTrue(failure.getMessage().contains("left_time"), failure.getMessage());
        }
    }

    @Test
    void asofJoinRejectsAnUnknownInequality() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> join(db, NodeTestSupport.table(db, LEFT, LEFT_ROWS),
                            NodeTestSupport.table(db, RIGHT, RIGHT_ROWS), """
                            {"how":"asof","left_time":"ts","right_time":"ts","asof_inequality":"="}
                            """));
            assertTrue(failure.getMessage().contains("asof_inequality"), failure.getMessage());
        }
    }

    @Test
    void crossJoinMultipliesBothSides() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, List.<Object[]>of(
                    new Object[]{"AAA", 1L, 1.0}, new Object[]{"BBB", 2L, 2.0}));
            LazyPlan right = NodeTestSupport.table(db, RIGHT, List.<Object[]>of(
                    new Object[]{"X", 10L, 10.0}, new Object[]{"Y", 20L, 20.0},
                    new Object[]{"Z", 30L, 30.0}));

            LazyPlan plan = join(db, left, right, "{\"how\":\"cross\"}");

            assertEquals(6, plan.preview(10).rows());
            assertEquals(List.of(ColumnSpec.of("symbol", "Utf8"), ColumnSpec.of("ts", "Int64"),
                    ColumnSpec.of("price", "Float64"), ColumnSpec.of("symbol (Right)", "Utf8"),
                    ColumnSpec.of("ts (Right)", "Int64"), ColumnSpec.of("quote", "Float64")),
                    plan.columns());
        }
    }

    @Test
    void crossJoinDoesNotNeedKeys() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, NodeTestSupport.table(db, LEFT, List.<Object[]>of(new Object[]{"A", 1L, 1.0})),
                    NodeTestSupport.table(db, RIGHT, List.<Object[]>of(new Object[]{"B", 2L, 2.0})),
                    "{\"how\":\"cross\",\"left_keys\":[\"missing\"]}");

            assertEquals(1, plan.preview(10).rows());
        }
    }
}
