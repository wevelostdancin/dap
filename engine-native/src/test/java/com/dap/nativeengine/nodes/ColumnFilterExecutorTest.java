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

class ColumnFilterExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("note", "Utf8"),
            ColumnSpec.of("score", "Float64"));

    private static LazyPlan input(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, COLUMNS, List.of(
                new Object[]{1L, "a", 1.5},
                new Object[]{2L, "b", null}));
    }

    @Test
    void includeKeepsInputSchemaOrder() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, """
                    {"include":["score","id"]}
                    """);

            LazyPlan plan = new ColumnFilterExecutor().execute(context, List.of(input(db)));

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("score", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("1", "1.5"), data.data().get(0));
            assertEquals(Arrays.asList("2", null), data.data().get(1));
        }
    }

    @Test
    void excludeDropsRequestedColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, """
                    {"exclude":["note"]}
                    """);

            LazyPlan plan = new ColumnFilterExecutor().execute(context, List.of(input(db)));

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("score", "Float64")), plan.columns());
            assertEquals(2, plan.count());
        }
    }

    @Test
    void includeAndExcludeTogetherAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, """
                    {"include":["id"],"exclude":["note"]}
                    """);
            LazyPlan input = input(db);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ColumnFilterExecutor().execute(context, List.of(input)));
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("include")
                    || failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("exclude"), failure.getMessage());
        }
    }

    @Test
    void neitherIncludeNorExcludeIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);
            assertThrows(IllegalArgumentException.class,
                    () -> new ColumnFilterExecutor().execute(NodeTestSupport.context(db, "{}"), List.of(input)));
        }
    }

    @Test
    void unknownColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ColumnFilterExecutor().execute(NodeTestSupport.context(db, """
                            {"include":["nope"]}
                            """), List.of(input)));
            assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
        }
    }

    @Test
    void removingEveryColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ColumnFilterExecutor().execute(NodeTestSupport.context(db, """
                            {"exclude":["id","note","score"]}
                            """), List.of(input)));
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("column"), failure.getMessage());
        }
    }
}
