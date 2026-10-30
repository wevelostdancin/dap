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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringManipulationExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("name", "Utf8"),
            ColumnSpec.of("note", "Utf8"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{1L, "ada", "  padded  "},
            new Object[]{2L, "Bob", null},
            new Object[]{3L, "", ""},
            new Object[]{4L, null, "keep"});

    @Test
    void upperReplaceKeepsColumnInPlaceWithSameTypeAndNulls() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"column":"name","op":"upper","mode":"replace"}
                    """);

            LazyPlan plan = new StringManipulationExecutor().execute(context, List.of(input));

            assertEquals(COLUMNS, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(Arrays.asList("1", "ADA", "  padded  "), data.data().get(0));
            assertEquals(Arrays.asList("2", "BOB", null), data.data().get(1));
            assertEquals(Arrays.asList("3", "", ""), data.data().get(2));
            assertNull(data.data().get(3).get(1));
            assertEquals("keep", data.data().get(3).get(2));
        }
    }

    @Test
    void lowerAndTrimTransformOnlyTargetColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1L, "MiXeD Case", "  padded  "},
                    new Object[]{2L, null, "   "},
                    new Object[]{3L, "unchanged", ""}));

            LazyPlan lowered = new StringManipulationExecutor().execute(
                    NodeTestSupport.context(db, """
                            {"column":"name","op":"lower"}
                            """), List.of(input));
            TableData lowerData = lowered.preview(10);
            assertEquals(Arrays.asList("1", "mixed case", "  padded  "), lowerData.data().get(0));
            assertNull(lowerData.data().get(1).get(1));
            assertEquals("unchanged", lowerData.data().get(2).get(1));
            assertEquals(COLUMNS, lowered.columns());

            LazyPlan trimmed = new StringManipulationExecutor().execute(
                    NodeTestSupport.context(db, """
                            {"column":"note","op":"trim","mode":"replace"}
                            """), List.of(input));
            TableData trimData = trimmed.preview(10);
            assertEquals("padded", trimData.data().get(0).get(2));
            assertEquals("", trimData.data().get(1).get(2));
            assertEquals("", trimData.data().get(2).get(2));
            assertEquals("MiXeD Case", trimData.data().get(0).get(1));
        }
    }

    @Test
    void replaceRewritesEveryOccurrenceOfTheSearchText() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1L, "banana", "a-b-a"},
                    new Object[]{2L, "no match", null},
                    new Object[]{3L, "", ""}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"column":"name","op":"replace","search":"an","value":"X"}
                    """);

            LazyPlan plan = new StringManipulationExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(Arrays.asList("1", "bXXa", "a-b-a"), data.data().get(0));
            assertEquals(Arrays.asList("2", "no match", null), data.data().get(1));
            assertEquals(Arrays.asList("3", "", ""), data.data().get(2));
            assertEquals(COLUMNS, plan.columns());
        }
    }

    @Test
    void concatAppendAddsNewColumnLastWithKnimeNameAndStringType() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1L, "ada", "x"},
                    new Object[]{2L, "", null},
                    new Object[]{3L, null, "y"}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"column":"name","op":"concat","mode":"append","value":"!"}
                    """);

            LazyPlan plan = new StringManipulationExecutor().execute(context, List.of(input));

            assertEquals(4, plan.columns().size());
            assertEquals(COLUMNS, plan.columns().subList(0, 3));
            ColumnSpec appended = plan.columns().get(3);
            assertEquals("new column", appended.name());
            assertEquals("Utf8", appended.arrowType());
            assertEquals("String", appended.knimeType());

            TableData data = plan.preview(10);
            assertEquals(Arrays.asList("1", "ada", "x", "ada!"), data.data().get(0));
            assertEquals(Arrays.asList("2", "", null, "!"), data.data().get(1));
            assertNull(data.data().get(2).get(3));
        }
    }

    @Test
    void appendAlsoWorksForUpperAndLeavesSourceColumnIntact() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.<Object[]>of(
                    new Object[]{1L, "ada", null}));
            LazyPlan plan = new StringManipulationExecutor().execute(
                    NodeTestSupport.context(db, """
                            {"column":"name","op":"upper","mode":"append"}
                            """), List.of(input));

            TableData data = plan.preview(10);
            assertEquals(Arrays.asList("1", "ada", null, "ADA"), data.data().get(0));
        }
    }

    @Test
    void invalidConfigIsRejectedWithCauseInMessage() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.<Object[]>of(
                    new Object[]{1L, "ada", null}));

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"nope","op":"upper"}
                            """), List.of(input)));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException badOp = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"name","op":"capitalize"}
                            """), List.of(input)));
            assertTrue(badOp.getMessage().contains("capitalize"), badOp.getMessage());

            IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"name","op":"upper","mode":"prepend"}
                            """), List.of(input)));
            assertTrue(badMode.getMessage().contains("prepend"), badMode.getMessage());

            IllegalArgumentException missingValue = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"name","op":"concat","mode":"append"}
                            """), List.of(input)));
            assertTrue(missingValue.getMessage().contains("value"), missingValue.getMessage());

            IllegalArgumentException blankValue = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"name","op":"concat","mode":"append","value":"   "}
                            """), List.of(input)));
            assertTrue(blankValue.getMessage().contains("value"), blankValue.getMessage());

            IllegalArgumentException missingSearch = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"name","op":"replace","value":"X"}
                            """), List.of(input)));
            assertTrue(missingSearch.getMessage().contains("search"), missingSearch.getMessage());

            IllegalArgumentException missingColumn = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"op":"upper"}
                            """), List.of(input)));
            assertTrue(missingColumn.getMessage().contains("column"), missingColumn.getMessage());

            IllegalArgumentException nonString = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"id","op":"upper"}
                            """), List.of(input)));
            assertTrue(nonString.getMessage().contains("id"), nonString.getMessage());
            assertTrue(nonString.getMessage().contains("Int64"), nonString.getMessage());
        }
    }

    @Test
    void missingInputPlanIsRejected() {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new StringManipulationExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"name","op":"upper"}
                            """), List.of()));
            assertTrue(failure.getMessage().contains("input"), failure.getMessage());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
