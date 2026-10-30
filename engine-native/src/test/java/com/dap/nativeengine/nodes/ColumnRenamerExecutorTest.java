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

class ColumnRenamerExecutorTest {
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
    void renamesKeepColumnOrderTypesAndValues() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, """
                    {"renames":[{"from":"note","to":"label"},{"from":"score","to":"rating"}]}
                    """);

            LazyPlan plan = new ColumnRenamerExecutor().execute(context, List.of(input(db)));

            assertEquals(List.of(
                    ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("label", "Utf8"),
                    ColumnSpec.of("rating", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("1", "a", "1.5"), data.data().get(0));
            assertEquals(Arrays.asList("2", "b", null), data.data().get(1));
            assertEquals(2, plan.count());
        }
    }

    @Test
    void simultaneousSwapKeepsBothColumnsInPlace() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, """
                    {"renames":[{"from":"id","to":"note"},{"from":"note","to":"id"}]}
                    """);

            LazyPlan plan = new ColumnRenamerExecutor().execute(context, List.of(input(db)));

            assertEquals(List.of(
                    ColumnSpec.of("note", "Int64"),
                    ColumnSpec.of("id", "Utf8"),
                    ColumnSpec.of("score", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("1", "a", "1.5"), data.data().get(0));
        }
    }

    @Test
    void duplicateSourceIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, """
                    {"renames":[{"from":"note","to":"a"},{"from":"note","to":"b"}]}
                    """);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ColumnRenamerExecutor().execute(context, List.of(input(db))));
            assertTrue(failure.getMessage().contains("note"), failure.getMessage());
        }
    }

    @Test
    void malformedRenamesAreRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            for (String config : List.of(
                    "{\"renames\":\"note\"}",
                    "{\"renames\":null}",
                    "{\"renames\":[\"note\"]}",
                    "{\"renames\":[{\"to\":\"x\"}]}")) {
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> new ColumnRenamerExecutor().execute(
                                NodeTestSupport.context(db, config), List.of(input(db))),
                        config);
                assertTrue(failure.getMessage().contains("renames") || failure.getMessage().contains("from"),
                        config + " -> " + failure.getMessage());
            }
        }
    }

    @Test
    void emptyRenamesIsPassthrough() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, """
                    {"renames":[]}
                    """);

            LazyPlan plan = new ColumnRenamerExecutor().execute(context, List.of(input(db)));

            assertEquals(COLUMNS, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("1", "a", "1.5"), data.data().get(0));
            assertEquals(Arrays.asList("2", "b", null), data.data().get(1));
        }
    }

    @Test
    void unknownSourceColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ColumnRenamerExecutor().execute(NodeTestSupport.context(db, """
                            {"renames":[{"from":"nope","to":"label"}]}
                            """), List.of(input)));
            assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
        }
    }

    @Test
    void duplicateTargetIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ColumnRenamerExecutor().execute(NodeTestSupport.context(db, """
                            {"renames":[{"from":"note","to":"label"},{"from":"score","to":"label"}]}
                            """), List.of(input)));
            assertTrue(failure.getMessage().contains("label"), failure.getMessage());
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate"),
                    failure.getMessage());
        }
    }

    @Test
    void targetThatCollidesWithKeptColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ColumnRenamerExecutor().execute(NodeTestSupport.context(db, """
                            {"renames":[{"from":"note","to":"score"}]}
                            """), List.of(input)));
            assertTrue(failure.getMessage().contains("score"), failure.getMessage());
        }
    }

    @Test
    void blankNamesAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);
            assertThrows(IllegalArgumentException.class,
                    () -> new ColumnRenamerExecutor().execute(NodeTestSupport.context(db, """
                            {"renames":[{"from":"  ","to":"label"}]}
                            """), List.of(input)));
            assertThrows(IllegalArgumentException.class,
                    () -> new ColumnRenamerExecutor().execute(NodeTestSupport.context(db, """
                            {"renames":[{"from":"note","to":""}]}
                            """), List.of(input)));
        }
    }
}
