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

class SorterExecutorTest {
    private static List<String> column(TableData data, int index) {
        return data.data().stream().map(row -> row.get(index)).toList();
    }

    @Test
    void multiKeyAscendingAndDescending() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"), ColumnSpec.of("n", "Int64"));
            List<Object[]> rows = List.of(
                    new Object[]{"b", 1L},
                    new Object[]{"a", 2L},
                    new Object[]{"a", 1L});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":[{"column":"grp","direction":"asc"},{"column":"n","direction":"desc"}]}
                    """);

            LazyPlan plan = new SorterExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(List.of("a", "a", "b"), column(data, 0));
            assertEquals(List.of("2", "1", "1"), column(data, 1));
            assertEquals(columns, plan.columns());
        }
    }

    @Test
    void nullsSortFirstAscendingAndLastDescending() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Int64"));
            List<Object[]> rows = List.of(
                    new Object[]{2L},
                    new Object[]{null},
                    new Object[]{1L});

            LazyPlan ascending = new SorterExecutor().execute(NodeTestSupport.context(db, """
                    {"keys":[{"column":"v","direction":"asc"}]}
                    """), List.of(NodeTestSupport.table(db, columns, rows)));
            assertEquals(Arrays.asList(null, "1", "2"), column(ascending.preview(10), 0));

            LazyPlan descending = new SorterExecutor().execute(NodeTestSupport.context(db, """
                    {"keys":[{"column":"v","direction":"desc"}]}
                    """), List.of(NodeTestSupport.table(db, columns, rows)));
            assertEquals(Arrays.asList("2", "1", null), column(descending.preview(10), 0));
        }
    }

    @Test
    void nocaseCollationIgnoresCase() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("s", "Utf8"));
            List<Object[]> rows = List.of(
                    new Object[]{"Beta"},
                    new Object[]{"alpha"},
                    new Object[]{"Gamma"});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"keys":[{"column":"s","direction":"asc"}],"collation":"nocase"}
                    """);

            LazyPlan plan = new SorterExecutor().execute(context, List.of(input));

            assertEquals(List.of("alpha", "Beta", "Gamma"), column(plan.preview(10), 0));
        }
    }

    @Test
    void unknownColumnOrDirectionFails() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("s", "Utf8"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.<Object[]>of(new Object[]{"a"}));

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> new SorterExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":[{"column":"nope","direction":"asc"}]}
                            """), List.of(input)));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException badDirection = assertThrows(IllegalArgumentException.class,
                    () -> new SorterExecutor().execute(NodeTestSupport.context(db, """
                            {"keys":[{"column":"s","direction":"sideways"}]}
                            """), List.of(input)));
            assertTrue(badDirection.getMessage().contains("sideways"), badDirection.getMessage());
        }
    }
}
