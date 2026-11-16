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

class UnpivotExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("jan", "Int64"),
            ColumnSpec.of("feb", "Int64"),
            ColumnSpec.of("note", "Utf8"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{1L, 10L, 20L, "a"},
            new Object[]{2L, 30L, 40L, "b"});

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
    void selectedColumnsBecomeNameValueRowsAndOtherColumnsAreKept() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"columns":["jan","feb"]}
                    """);

            LazyPlan plan = new UnpivotExecutor().execute(context, List.of(input));

            assertEquals(List.of(
                            ColumnSpec.of("id", "Int64"),
                            ColumnSpec.of("note", "Utf8"),
                            ColumnSpec.of("variable", "Utf8"),
                            ColumnSpec.of("value", "Int64")),
                    plan.columns());
            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            assertEquals(List.of("1|a|feb|20", "1|a|jan|10", "2|b|feb|40", "2|b|jan|30"),
                    canonicalRows(data));
        }
    }

    @Test
    void customNameAndValueColumnsAreHonoured() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"columns":["jan"],"nameColumn":"month","valueColumn":"sales"}
                    """);

            LazyPlan plan = new UnpivotExecutor().execute(context, List.of(input));

            assertEquals(List.of("id", "feb", "note", "month", "sales"),
                    plan.columns().stream().map(ColumnSpec::name).toList());
            assertEquals(List.of(ColumnSpec.of("month", "Utf8"), ColumnSpec.of("sales", "Int64")),
                    plan.columns().subList(3, 5));
            assertEquals(List.of("1|20|a|jan|10", "2|40|b|jan|30"), canonicalRows(plan.preview(10)));
        }
    }

    @Test
    void singleRowStillProducesOneRowPerSelectedColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS,
                    List.<Object[]>of(new Object[]{7L, 1L, 2L, "z"}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"columns":["jan","feb"]}
                    """);

            LazyPlan plan = new UnpivotExecutor().execute(context, List.of(input));

            assertEquals(2, plan.preview(10).rows());
            assertEquals(List.of("7|z|feb|2", "7|z|jan|1"), canonicalRows(plan.preview(10)));
        }
    }

    @Test
    void invalidConfigIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> new UnpivotExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":["nope"]}
                            """), List.of(input)));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> new UnpivotExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[]}
                            """), List.of(input)));
            assertTrue(empty.getMessage().contains("'columns'"), empty.getMessage());

            IllegalArgumentException mixed = assertThrows(IllegalArgumentException.class,
                    () -> new UnpivotExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":["jan","note"]}
                            """), List.of(input)));
            assertTrue(mixed.getMessage().contains("share one type"), mixed.getMessage());

            IllegalArgumentException collision = assertThrows(IllegalArgumentException.class,
                    () -> new UnpivotExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":["jan"],"nameColumn":"id"}
                            """), List.of(input)));
            assertTrue(collision.getMessage().contains("collides"), collision.getMessage());
        }
    }
}
