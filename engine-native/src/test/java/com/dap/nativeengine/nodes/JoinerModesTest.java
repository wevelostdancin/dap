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

/** dap.joiner full, semi and anti modes. */
class JoinerModesTest {
    private static final List<ColumnSpec> LEFT = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("lv", "Utf8"));
    private static final List<Object[]> LEFT_ROWS = List.of(
            new Object[]{1L, "a"},
            new Object[]{2L, "b"},
            new Object[]{3L, "c"});
    private static final List<ColumnSpec> RIGHT = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("rv", "Utf8"));
    private static final List<Object[]> RIGHT_ROWS = List.of(
            new Object[]{1L, "x"},
            new Object[]{1L, "y"},
            new Object[]{3L, "z"},
            new Object[]{5L, "w"});

    private static String canonical(List<String> row) {
        StringJoiner joiner = new StringJoiner("|");
        for (String value : row) {
            joiner.add(value == null ? "<null>" : value);
        }
        return joiner.toString();
    }

    private static List<String> rows(TableData data) {
        return data.data().stream().map(JoinerModesTest::canonical).sorted().toList();
    }

    private static LazyPlan join(DuckDbSession db, LazyPlan left, LazyPlan right, String how, boolean mergeKeys)
            throws Exception {
        NodeContext context = NodeTestSupport.context(db, """
                {"how":"%s","left_keys":["id"],"right_keys":["id"],"merge_keys":%s}
                """.formatted(how, mergeKeys));
        return new JoinerExecutor().execute(context, List.of(left, right));
    }

    @Test
    void fullJoinKeepsUnmatchedRowsFromBothSides() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, LEFT_ROWS);
            LazyPlan right = NodeTestSupport.table(db, RIGHT, RIGHT_ROWS);

            LazyPlan plan = join(db, left, right, "full", true);

            assertEquals(List.of(ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("lv", "Utf8"),
                    ColumnSpec.of("rv", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(5, data.rows(), rows(data).toString());
            assertEquals(List.of("1|a|x", "1|a|y", "2|b|<null>", "3|c|z", "5|<null>|w"), rows(data));
        }
    }

    @Test
    void fullJoinSuffixesClashingRightColumnsAndKeepsUnmatchedNullKeys() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("v", "Utf8"));
            LazyPlan left = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{1L, "L"},
                    new Object[]{null, "Ln"}));
            LazyPlan right = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{1L, "R"},
                    new Object[]{null, "Rn"}));

            LazyPlan plan = join(db, left, right, "full", true);

            assertEquals(List.of(ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("v", "Utf8"),
                    ColumnSpec.of("v (Right)", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("1|L|R", "<null>|<null>|Rn", "<null>|Ln|<null>"), rows(data));
        }
    }

    @Test
    void semiJoinKeepsOnlyMatchedLeftRowsWithLeftColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, NodeTestSupport.table(db, LEFT, LEFT_ROWS),
                    NodeTestSupport.table(db, RIGHT, RIGHT_ROWS), "semi", true);

            assertEquals(LEFT, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("1|a", "3|c"), rows(data));
        }
    }

    @Test
    void antiJoinKeepsOnlyUnmatchedLeftRowsWithLeftColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, NodeTestSupport.table(db, LEFT, LEFT_ROWS),
                    NodeTestSupport.table(db, RIGHT, RIGHT_ROWS), "anti", true);

            assertEquals(LEFT, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals(List.of("2|b"), rows(data));
        }
    }

    @Test
    void nullKeysNeverMatchInFullSemiAndAnti() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> leftRows = List.of(new Object[]{1L, "a"}, new Object[]{null, "b"});
            List<Object[]> rightRows = List.of(new Object[]{1L, "x"}, new Object[]{null, "y"});

            TableData full = join(db, NodeTestSupport.table(db, LEFT, leftRows),
                    NodeTestSupport.table(db, RIGHT, rightRows), "full", true).preview(10);
            assertEquals(List.of("1|a|x", "<null>|<null>|y", "<null>|b|<null>"), rows(full));

            TableData semi = join(db, NodeTestSupport.table(db, LEFT, leftRows),
                    NodeTestSupport.table(db, RIGHT, rightRows), "semi", true).preview(10);
            assertEquals(List.of("1|a"), rows(semi));

            TableData anti = join(db, NodeTestSupport.table(db, LEFT, leftRows),
                    NodeTestSupport.table(db, RIGHT, rightRows), "anti", true).preview(10);
            assertEquals(List.of("<null>|b"), rows(anti));
        }
    }

    @Test
    void duplicateRightKeysMultiplyFullJoinRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, List.<Object[]>of(new Object[]{1L, "a"}));
            LazyPlan right = NodeTestSupport.table(db, RIGHT, List.of(
                    new Object[]{1L, "x"},
                    new Object[]{1L, "y"},
                    new Object[]{1L, "z"}));

            TableData data = join(db, left, right, "full", true).preview(10);

            assertEquals(3, data.rows());
            assertEquals(List.of("1|a|x", "1|a|y", "1|a|z"), rows(data));
        }
    }

    @Test
    void unsupportedModeListsTheSupportedModes() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan left = NodeTestSupport.table(db, LEFT, LEFT_ROWS);
            LazyPlan right = NodeTestSupport.table(db, RIGHT, RIGHT_ROWS);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> join(db, left, right, "outer", true));
            for (String mode : List.of("inner", "left", "right", "full", "semi", "anti")) {
                assertTrue(failure.getMessage().contains(mode), failure.getMessage());
            }
        }
    }
}
