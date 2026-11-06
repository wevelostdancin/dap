package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueCountsExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(ColumnSpec.of("color", "Utf8"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{"red"},
            new Object[]{"blue"},
            new Object[]{"red"},
            new Object[]{null},
            new Object[]{"blue"},
            new Object[]{"red"},
            new Object[]{"green"});

    private static List<String> column(TableData data, int index) {
        return data.data().stream().map(row -> row.get(index)).toList();
    }

    @Test
    void countsDistinctValuesMostFrequentFirstAndSkipsNulls() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan plan = new ValueCountsExecutor()
                    .execute(NodeTestSupport.context(db, "{\"column\":\"color\"}"), List.of(input));

            assertEquals(List.of(ColumnSpec.of("value", "Utf8"), ColumnSpec.of("count", "Int64")),
                    plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("red", "blue", "green"), column(data, 0));
            assertEquals(List.of("3", "2", "1"), column(data, 1));
        }
    }

    @Test
    void limitKeepsTheMostFrequentValues() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            TableData data = new ValueCountsExecutor()
                    .execute(NodeTestSupport.context(db, "{\"column\":\"color\",\"limit\":2}"), List.of(input))
                    .preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("red", "blue"), column(data, 0));
        }
    }

    @Test
    void tiesAreOrderedByValueAndAllNullColumnGivesNoRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan ties = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{"b"},
                    new Object[]{"a"},
                    new Object[]{"b"},
                    new Object[]{"a"},
                    new Object[]{"c"}));
            TableData data = new ValueCountsExecutor()
                    .execute(NodeTestSupport.context(db, "{\"column\":\"color\"}"), List.of(ties))
                    .preview(10);
            assertEquals(List.of("a", "b", "c"), column(data, 0));
            assertEquals(List.of("2", "2", "1"), column(data, 1));

            LazyPlan allNull = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{null},
                    new Object[]{null}));
            TableData empty = new ValueCountsExecutor()
                    .execute(NodeTestSupport.context(db, "{\"column\":\"color\"}"), List.of(allNull))
                    .preview(10);
            assertEquals(0, empty.rows());
        }
    }

    @Test
    void invalidColumnAndLimitAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new ValueCountsExecutor().execute(NodeTestSupport.context(db, "{}"), List.of(input)));
            assertTrue(missing.getMessage().contains("column"), missing.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> new ValueCountsExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"nope\"}"), List.of(input)));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                    () -> new ValueCountsExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"color\",\"limit\":0}"), List.of(input)));
            assertTrue(zero.getMessage().contains("greater than 0"), zero.getMessage());

            IllegalArgumentException fractional = assertThrows(IllegalArgumentException.class,
                    () -> new ValueCountsExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"color\",\"limit\":2.5}"), List.of(input)));
            assertTrue(fractional.getMessage().contains("integer"), fractional.getMessage());

            IllegalArgumentException text = assertThrows(IllegalArgumentException.class,
                    () -> new ValueCountsExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"color\",\"limit\":\"5\"}"), List.of(input)));
            assertTrue(text.getMessage().contains("integer"), text.getMessage());
        }
    }
}
