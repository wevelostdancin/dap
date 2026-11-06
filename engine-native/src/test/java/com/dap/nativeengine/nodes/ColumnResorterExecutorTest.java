package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnResorterExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("a", "Utf8"), ColumnSpec.of("b", "Int64"), ColumnSpec.of("c", "Float64"));

    private static LazyPlan resort(DuckDbSession db, String config) throws Exception {
        LazyPlan input = NodeTestSupport.table(db, COLUMNS,
                List.<Object[]>of(new Object[]{"x", 1L, 1.5}));
        return new ColumnResorterExecutor().execute(NodeTestSupport.context(db, config), List.of(input));
    }

    @Test
    void reordersColumnsAndValues() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = resort(db, """
                    {"columns":["c","a","b"]}
                    """);

            assertEquals(List.of("c", "a", "b"), plan.columns().stream().map(ColumnSpec::name).toList());
            assertEquals(List.of("Float64", "Utf8", "Int64"),
                    plan.columns().stream().map(ColumnSpec::arrowType).toList());
            assertEquals(List.of("1.5", "x", "1"), plan.preview(10).data().get(0));
        }
    }

    @Test
    void inputOrderIsAnAcceptedPermutation() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = resort(db, """
                    {"columns":["a","b","c"]}
                    """);

            assertEquals(COLUMNS, plan.columns());
            assertEquals(List.of("x", "1", "1.5"), plan.preview(10).data().get(0));
        }
    }

    @Test
    void subsetUnknownAndDuplicateColumnsAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException subset = assertThrows(IllegalArgumentException.class,
                    () -> resort(db, """
                            {"columns":["a","b"]}
                            """));
            assertTrue(subset.getMessage().contains("permutation"), subset.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> resort(db, """
                            {"columns":["a","b","d"]}
                            """));
            assertTrue(unknown.getMessage().contains("d"), unknown.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> resort(db, """
                            {"columns":["a","a","b"]}
                            """));
            assertTrue(duplicate.getMessage().contains("duplicate column 'a'"), duplicate.getMessage());
        }
    }

    @Test
    void emptyListAndMissingConfigAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> resort(db, """
                            {"columns":[]}
                            """));
            assertTrue(empty.getMessage().contains("permutation"), empty.getMessage());

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> resort(db, "{}"));
            assertTrue(missing.getMessage().contains("columns"), missing.getMessage());
        }
    }
}
