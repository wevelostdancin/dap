package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("grp", "Utf8"),
            ColumnSpec.of("v", "Int64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{"a", 1L},
            new Object[]{"a", 2L},
            new Object[]{"a", 3L},
            new Object[]{"b", 10L});

    private static List<String> row(TableData data, String grp, String v) {
        return data.data().stream()
                .filter(candidate -> Objects.equals(candidate.get(0), grp) && Objects.equals(candidate.get(1), v))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + grp + "/" + v + " in " + data.data()));
    }

    @Test
    void allFunctionsShareThePartitionAndOrder() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"partitionBy":["grp"],"orderBy":[{"column":"v","direction":"asc"}],
                     "functions":[
                       {"fn":"row_number","output":"rn"},
                       {"fn":"rank","output":"rk"},
                       {"fn":"dense_rank","output":"dr"},
                       {"fn":"lag","column":"v","output":"prev"},
                       {"fn":"lead","column":"v","output":"next"},
                       {"fn":"sum","column":"v","output":"running"},
                       {"fn":"mean","column":"v","output":"avg"}]}
                    """);

            LazyPlan plan = new WindowExecutor().execute(context, List.of(input));

            assertEquals(List.of(
                            ColumnSpec.of("grp", "Utf8"),
                            ColumnSpec.of("v", "Int64"),
                            ColumnSpec.of("rn", "Int64"),
                            ColumnSpec.of("rk", "Int64"),
                            ColumnSpec.of("dr", "Int64"),
                            ColumnSpec.of("prev", "Int64"),
                            ColumnSpec.of("next", "Int64"),
                            ColumnSpec.of("running", "Int64"),
                            ColumnSpec.of("avg", "Float64")),
                    plan.columns());
            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            assertEquals(java.util.Arrays.asList("a", "1", "1", "1", "1", null, "2", "1", "1.0"), row(data, "a", "1"));
            assertEquals(java.util.Arrays.asList("a", "3", "3", "3", "3", "2", null, "6", "2.0"), row(data, "a", "3"));
            assertEquals(java.util.Arrays.asList("b", "10", "1", "1", "1", null, null, "10", "10.0"),
                    row(data, "b", "10"));
        }
    }

    @Test
    void withoutOrderingRowNumberAndAggregatesWorkOverTheWholeTable() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"functions":[
                       {"fn":"row_number","output":"rn"},
                       {"fn":"sum","column":"v","output":"total"}]}
                    """);

            LazyPlan plan = new WindowExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            for (List<String> row : data.data()) {
                assertEquals("16", row.get(3), row.toString());
            }
        }
    }

    @Test
    void rankAndDenseRankDifferOnTies() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{"a", 1L},
                    new Object[]{"a", 1L},
                    new Object[]{"a", 2L}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"orderBy":[{"column":"v","direction":"asc"}],
                     "functions":[
                       {"fn":"rank","output":"rk"},
                       {"fn":"dense_rank","output":"dr"}]}
                    """);

            LazyPlan plan = new WindowExecutor().execute(context, List.of(input));

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            List<List<String>> tied = data.data().stream()
                    .filter(row -> "1".equals(row.get(1)))
                    .toList();
            assertEquals(2, tied.size());
            for (List<String> row : tied) {
                assertEquals("1", row.get(2), row.toString());
                assertEquals("1", row.get(3), row.toString());
            }
            List<String> last = data.data().stream()
                    .filter(row -> "2".equals(row.get(1)))
                    .findFirst()
                    .orElseThrow();
            assertEquals("3", last.get(2));
            assertEquals("2", last.get(3));
        }
    }

    @Test
    void invalidConfigIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException noOrder = assertThrows(IllegalArgumentException.class,
                    () -> new WindowExecutor().execute(NodeTestSupport.context(db, """
                            {"functions":[{"fn":"rank","output":"rk"}]}
                            """), List.of(input)));
            assertTrue(noOrder.getMessage().contains("orderBy"), noOrder.getMessage());

            IllegalArgumentException noColumn = assertThrows(IllegalArgumentException.class,
                    () -> new WindowExecutor().execute(NodeTestSupport.context(db, """
                            {"orderBy":[{"column":"v","direction":"asc"}],
                             "functions":[{"fn":"lag","output":"prev"}]}
                            """), List.of(input)));
            assertTrue(noColumn.getMessage().contains("column"), noColumn.getMessage());

            IllegalArgumentException unknownFunction = assertThrows(IllegalArgumentException.class,
                    () -> new WindowExecutor().execute(NodeTestSupport.context(db, """
                            {"functions":[{"fn":"ntile","output":"bucket"}]}
                            """), List.of(input)));
            assertTrue(unknownFunction.getMessage().contains("ntile"), unknownFunction.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> new WindowExecutor().execute(NodeTestSupport.context(db, """
                            {"functions":[
                               {"fn":"row_number","output":"rn"},
                               {"fn":"row_number","output":"rn"}]}
                            """), List.of(input)));
            assertTrue(duplicate.getMessage().contains("duplicates"), duplicate.getMessage());

            IllegalArgumentException badOffset = assertThrows(IllegalArgumentException.class,
                    () -> new WindowExecutor().execute(NodeTestSupport.context(db, """
                            {"orderBy":[{"column":"v","direction":"asc"}],
                             "functions":[{"fn":"lag","column":"v","offset":-1,"output":"prev"}]}
                            """), List.of(input)));
            assertTrue(badOffset.getMessage().contains("offset"), badOffset.getMessage());

            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> new WindowExecutor().execute(NodeTestSupport.context(db, """
                            {"functions":[{"fn":"sum","column":"grp","output":"total"}]}
                            """), List.of(input)));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());
        }
    }
}
