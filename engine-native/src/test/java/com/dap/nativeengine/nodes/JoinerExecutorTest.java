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

class JoinerExecutorTest {
    private static final List<ColumnSpec> LEFT = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("lv", "Utf8"));
    private static final List<ColumnSpec> RIGHT = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("rv", "Float64"));
    private static final List<Object[]> LEFT_ROWS = List.of(
            new Object[]{1L, "a"},
            new Object[]{2L, "b"},
            new Object[]{3L, "c"});
    private static final List<Object[]> RIGHT_ROWS = List.of(
            new Object[]{1L, 10.5},
            new Object[]{1L, 11.5},
            new Object[]{3L, 30.0});

    private static List<String> canonicalRows(TableData data) {
        return data.data().stream().map(row -> {
            StringJoiner joiner = new StringJoiner("|");
            for (String value : row) {
                joiner.add(value == null ? "<null>" : value);
            }
            return joiner.toString();
        }).sorted().toList();
    }

    private static String join(int how, boolean mergeKeys) {
        String mode = switch (how) {
            case 0 -> "inner";
            case 1 -> "left";
            default -> "right";
        };
        return """
                {"how":"%s","left_keys":["id"],"right_keys":["id"],"merge_keys":%s}
                """.formatted(mode, mergeKeys);
    }

    private static LazyPlan join(DuckDbSession db, LazyPlan left, LazyPlan right, int how, boolean mergeKeys)
            throws Exception {
        NodeContext context = NodeTestSupport.context(db, join(how, mergeKeys));
        return new JoinerExecutor().execute(context, List.of(left, right));
    }

    @Test
    void innerJoinMergesKeysAndKeepsLeftThenRightColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, LEFT_ROWS);
            LazyPlan right = NodeTestSupport.table(db, RIGHT, RIGHT_ROWS);

            LazyPlan plan = join(db, left, right, 0, true);

            assertEquals(List.of(ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("lv", "Utf8"),
                    ColumnSpec.of("rv", "Float64")), plan.columns());
            assertEquals("Number (Float)", plan.columns().get(2).knimeType());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows(), "id=2 has no right match");
            assertEquals(List.of("1|a|10.5", "1|a|11.5", "3|c|30.0"), canonicalRows(data));
        }
    }

    @Test
    void leftJoinEmitsNullsForTheUnmatchedRightSide() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, LEFT_ROWS);
            LazyPlan right = NodeTestSupport.table(db, RIGHT, RIGHT_ROWS);

            LazyPlan plan = join(db, left, right, 1, true);

            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            assertEquals(List.of("1|a|10.5", "1|a|11.5", "2|b|<null>", "3|c|30.0"), canonicalRows(data));
        }
    }

    @Test
    void rightJoinEmitsNullsForTheUnmatchedLeftSide() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, LEFT_ROWS);
            LazyPlan right = NodeTestSupport.table(db, RIGHT, List.of(
                    new Object[]{1L, 10.5},
                    new Object[]{1L, 11.5},
                    new Object[]{3L, 30.0},
                    new Object[]{5L, 50.0}));

            LazyPlan plan = join(db, left, right, 2, true);

            assertEquals(List.of(ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("lv", "Utf8"),
                    ColumnSpec.of("rv", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            // merge_keys=true keeps one key column: it must carry the right key value for a
            // right-only row (the right key column itself is dropped from the output)
            assertEquals(List.of("1|a|10.5", "1|a|11.5", "3|c|30.0", "5|<null>|50.0"),
                    canonicalRows(data));
        }
    }

    @Test
    void mergeKeysFalseKeepsBothKeyColumnsInOutputOrder() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, List.<Object[]>of(new Object[]{1L, "a"}));
            LazyPlan right = NodeTestSupport.table(db, RIGHT, List.<Object[]>of(new Object[]{1L, 10.5}));

            LazyPlan plan = join(db, left, right, 0, false);

            assertEquals(List.of(ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("lv", "Utf8"),
                    ColumnSpec.of("id (Right)", "Int64"),
                    ColumnSpec.of("rv", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals(List.of("1|a|1|10.5"), canonicalRows(data));
        }
    }

    @Test
    void duplicateRightMatchesMultiplyRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, List.<Object[]>of(new Object[]{1L, "a"}));
            LazyPlan right = NodeTestSupport.table(db, List.of(
                    ColumnSpec.of("id", "Int64"), ColumnSpec.of("rv", "Utf8")), List.of(
                    new Object[]{1L, "x"},
                    new Object[]{1L, "y"},
                    new Object[]{1L, "z"}));

            LazyPlan plan = join(db, left, right, 0, true);

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("1|a|x", "1|a|y", "1|a|z"), canonicalRows(data));
        }
    }

    @Test
    void clashingNonKeyNameGetsRightSuffixOnTheRightColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("v", "Utf8"));
            LazyPlan left = NodeTestSupport.table(db, columns, List.<Object[]>of(new Object[]{1L, "L"}));
            LazyPlan right = NodeTestSupport.table(db, columns, List.<Object[]>of(new Object[]{1L, "R"}));

            LazyPlan plan = join(db, left, right, 0, true);

            assertEquals(List.of(ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("v", "Utf8"),
                    ColumnSpec.of("v (Right)", "Utf8")), plan.columns());
            assertEquals(List.of("1|L|R"), canonicalRows(plan.preview(10)));
        }
    }

    @Test
    void nullKeysNeverMatchInAnyJoinMode() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> leftRows = List.of(new Object[]{1L, "a"}, new Object[]{null, "b"});
            List<ColumnSpec> rightColumns = List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("rv", "Utf8"));
            List<Object[]> rightRows = List.of(new Object[]{1L, "x"}, new Object[]{null, "y"});

            TableData inner = join(db, NodeTestSupport.table(db, LEFT, leftRows),
                    NodeTestSupport.table(db, rightColumns, rightRows), 0, true).preview(10);
            assertEquals(1, inner.rows());
            assertEquals(List.of("1|a|x"), canonicalRows(inner));

            TableData left = join(db, NodeTestSupport.table(db, LEFT, leftRows),
                    NodeTestSupport.table(db, rightColumns, rightRows), 1, true).preview(10);
            assertEquals(List.of("1|a|x", "<null>|b|<null>"), canonicalRows(left));

            TableData right = join(db, NodeTestSupport.table(db, LEFT, leftRows),
                    NodeTestSupport.table(db, rightColumns, rightRows), 2, true).preview(10);
            assertEquals(List.of("1|a|x", "<null>|<null>|y"), canonicalRows(right));
        }
    }

    @Test
    void invalidConfigurationIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, LEFT_ROWS);
            LazyPlan right = NodeTestSupport.table(db, RIGHT, RIGHT_ROWS);

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new JoinerExecutor().execute(NodeTestSupport.context(db, join(0, true)),
                            List.of(left)));
            assertTrue(missing.getMessage().contains("right"), missing.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> new JoinerExecutor().execute(NodeTestSupport.context(db, """
                            {"how":"inner","left_keys":["nope"],"right_keys":["id"]}
                            """), List.of(left, right)));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                    () -> new JoinerExecutor().execute(NodeTestSupport.context(db, """
                            {"how":"inner","left_keys":["id","lv"],"right_keys":["id"]}
                            """), List.of(left, right)));
            assertTrue(mismatch.getMessage().contains("length"), mismatch.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> new JoinerExecutor().execute(NodeTestSupport.context(db, """
                            {"how":"inner","left_keys":[],"right_keys":[]}
                            """), List.of(left, right)));
            assertTrue(empty.getMessage().contains("non-empty"), empty.getMessage());

            IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                    () -> new JoinerExecutor().execute(NodeTestSupport.context(db, """
                            {"how":"outer","left_keys":["id"],"right_keys":["id"]}
                            """), List.of(left, right)));
            assertTrue(unsupported.getMessage().contains("outer"), unsupported.getMessage());
            assertTrue(unsupported.getMessage().contains("inner"), unsupported.getMessage());
        }
    }
}
