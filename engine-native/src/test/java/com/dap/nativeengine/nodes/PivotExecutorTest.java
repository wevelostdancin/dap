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

class PivotExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("region", "Utf8"),
            ColumnSpec.of("quarter", "Utf8"),
            ColumnSpec.of("amount", "Float64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{"north", "Q1", 10.0},
            new Object[]{"north", "Q2", 20.0},
            new Object[]{"south", "Q1", 5.0},
            new Object[]{"south", "Q1", 7.0});

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
    void sumPivotsOneColumnPerConfiguredValue() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"index":["region"],"pivotColumn":"quarter","valueColumn":"amount",
                     "values":["Q1","Q2"],"agg":"sum"}
                    """);

            LazyPlan plan = new PivotExecutor().execute(context, List.of(input));

            assertEquals(List.of(ColumnSpec.of("region", "Utf8"),
                    ColumnSpec.of("Q1", "Float64"),
                    ColumnSpec.of("Q2", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("north|10.0|20.0", "south|12.0|<null>"), canonicalRows(data));
        }
    }

    @Test
    void meanAndCountHandleMissingAndDuplicateCells() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan mean = new PivotExecutor().execute(NodeTestSupport.context(db, """
                    {"index":["region"],"pivotColumn":"quarter","valueColumn":"amount",
                     "values":["Q1","Q2"],"agg":"mean"}
                    """), List.of(input));
            assertEquals(List.of("north|10.0|20.0", "south|6.0|<null>"), canonicalRows(mean.preview(10)));

            LazyPlan count = new PivotExecutor().execute(NodeTestSupport.context(db, """
                    {"index":["region"],"pivotColumn":"quarter","valueColumn":"amount",
                     "values":["Q1","Q2"],"agg":"count"}
                    """), List.of(input));
            assertEquals(List.of(ColumnSpec.of("Q1", "Int64"), ColumnSpec.of("Q2", "Int64")),
                    count.columns().subList(1, 3));
            assertEquals(List.of("north|1|1", "south|2|0"), canonicalRows(count.preview(10)));
        }
    }

    @Test
    void valuesAreRequiredAndFixTheSchema() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan plan = new PivotExecutor().execute(NodeTestSupport.context(db, """
                    {"index":["region"],"pivotColumn":"quarter","valueColumn":"amount",
                     "values":["Q3"],"agg":"sum"}
                    """), List.of(input));

            assertEquals(List.of("region", "Q3"),
                    plan.columns().stream().map(ColumnSpec::name).toList());
            assertEquals(List.of("north|<null>", "south|<null>"), canonicalRows(plan.preview(10)));

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new PivotExecutor().execute(NodeTestSupport.context(db, """
                            {"index":["region"],"pivotColumn":"quarter","valueColumn":"amount","agg":"sum"}
                            """), List.of(input)));
            assertTrue(missing.getMessage().contains("'values'"), missing.getMessage());
        }
    }

    @Test
    void invalidConfigIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException unknownAgg = assertThrows(IllegalArgumentException.class,
                    () -> new PivotExecutor().execute(NodeTestSupport.context(db, """
                            {"index":["region"],"pivotColumn":"quarter","valueColumn":"amount",
                             "values":["Q1"],"agg":"median"}
                            """), List.of(input)));
            assertTrue(unknownAgg.getMessage().contains("median"), unknownAgg.getMessage());

            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> new PivotExecutor().execute(NodeTestSupport.context(db, """
                            {"index":["region"],"pivotColumn":"quarter","valueColumn":"quarter",
                             "values":["Q1"],"agg":"sum"}
                            """), List.of(input)));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> new PivotExecutor().execute(NodeTestSupport.context(db, """
                            {"index":["region"],"pivotColumn":"nope","valueColumn":"amount",
                             "values":["Q1"],"agg":"sum"}
                            """), List.of(input)));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException duplicateIndex = assertThrows(IllegalArgumentException.class,
                    () -> new PivotExecutor().execute(NodeTestSupport.context(db, """
                            {"index":["region","region"],"pivotColumn":"quarter","valueColumn":"amount",
                             "values":["Q1"],"agg":"sum"}
                            """), List.of(input)));
            assertTrue(duplicateIndex.getMessage().contains("duplicate"), duplicateIndex.getMessage());

            IllegalArgumentException collision = assertThrows(IllegalArgumentException.class,
                    () -> new PivotExecutor().execute(NodeTestSupport.context(db, """
                            {"index":["region"],"pivotColumn":"quarter","valueColumn":"amount",
                             "values":["region"],"agg":"sum"}
                            """), List.of(input)));
            assertTrue(collision.getMessage().contains("collides"), collision.getMessage());
        }
    }
}
