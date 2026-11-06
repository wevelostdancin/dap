package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DescribeExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("name", "Utf8"),
            ColumnSpec.of("age", "Int64"),
            ColumnSpec.of("score", "Float64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{"ann", 1L, 1.0},
            new Object[]{"bob", 2L, 2.0},
            new Object[]{"cid", 3L, 3.0},
            new Object[]{"dan", null, 4.0});

    private static Map<String, List<String>> rowsByColumn(TableData data) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (List<String> row : data.data()) {
            rows.put(row.get(0), row);
        }
        return rows;
    }

    private static void assertNumber(double expected, String actual) {
        assertEquals(expected, Double.parseDouble(actual), 1e-9);
    }

    private static List<String> names(TableData data) {
        return data.data().stream().map(row -> row.get(0)).toList();
    }

    @Test
    void describesEveryColumnWithNumericStatistics() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan plan = new DescribeExecutor().execute(NodeTestSupport.context(db, "{}"), List.of(input));

            assertEquals(List.of(
                    ColumnSpec.of("column", "Utf8"),
                    ColumnSpec.of("type", "Utf8"),
                    ColumnSpec.of("count", "Int64"),
                    ColumnSpec.of("missing", "Int64"),
                    ColumnSpec.of("distinct", "Int64"),
                    ColumnSpec.of("min", "Float64"),
                    ColumnSpec.of("max", "Float64"),
                    ColumnSpec.of("mean", "Float64"),
                    ColumnSpec.of("stddev", "Float64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("name", "age", "score"), names(data));
            Map<String, List<String>> rows = rowsByColumn(data);

            List<String> name = rows.get("name");
            assertEquals("String", name.get(1));
            assertEquals("4", name.get(2));
            assertEquals("0", name.get(3));
            assertEquals("4", name.get(4));
            assertNull(name.get(5));
            assertNull(name.get(6));
            assertNull(name.get(7));
            assertNull(name.get(8));

            List<String> age = rows.get("age");
            assertEquals("Number (Integer)", age.get(1));
            assertEquals("3", age.get(2));
            assertEquals("1", age.get(3));
            assertEquals("3", age.get(4));
            assertNumber(1.0, age.get(5));
            assertNumber(3.0, age.get(6));
            assertNumber(2.0, age.get(7));
            assertNumber(1.0, age.get(8));

            List<String> score = rows.get("score");
            assertEquals("Number (Float)", score.get(1));
            assertEquals("4", score.get(2));
            assertEquals("0", score.get(3));
            assertEquals("4", score.get(4));
            assertNumber(1.0, score.get(5));
            assertNumber(4.0, score.get(6));
            assertNumber(2.5, score.get(7));
            assertNumber(Math.sqrt(5.0 / 3.0), score.get(8));
        }
    }

    @Test
    void columnSubsetKeepsConfigOrderAndEmptyListMeansAllColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            TableData subset = new DescribeExecutor()
                    .execute(NodeTestSupport.context(db, "{\"columns\":[\"score\",\"name\"]}"), List.of(input))
                    .preview(10);
            assertEquals(List.of("score", "name"), names(subset));

            TableData all = new DescribeExecutor()
                    .execute(NodeTestSupport.context(db, "{\"columns\":[]}"), List.of(input))
                    .preview(10);
            assertEquals(List.of("name", "age", "score"), names(all));
        }
    }

    @Test
    void invalidColumnSelectionIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> new DescribeExecutor().execute(
                            NodeTestSupport.context(db, "{\"columns\":[\"nope\"]}"), List.of(input)));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException notArray = assertThrows(IllegalArgumentException.class,
                    () -> new DescribeExecutor().execute(
                            NodeTestSupport.context(db, "{\"columns\":\"name\"}"), List.of(input)));
            assertTrue(notArray.getMessage().contains("array"), notArray.getMessage());

            IllegalArgumentException notText = assertThrows(IllegalArgumentException.class,
                    () -> new DescribeExecutor().execute(
                            NodeTestSupport.context(db, "{\"columns\":[\"name\",5]}"), List.of(input)));
            assertTrue(notText.getMessage().contains("non-empty"), notText.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> new DescribeExecutor().execute(
                            NodeTestSupport.context(db, "{\"columns\":[\"name\",\"name\"]}"), List.of(input)));
            assertTrue(duplicate.getMessage().contains("duplicate"), duplicate.getMessage());
        }
    }

    @Test
    void allNullAndEmptyInputsStillProduceOneRowPerColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan allNull = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("v", "Int64")),
                    List.of(new Object[]{null}, new Object[]{null}, new Object[]{null}));
            TableData data = new DescribeExecutor()
                    .execute(NodeTestSupport.context(db, "{}"), List.of(allNull))
                    .preview(10);
            assertEquals(1, data.rows());
            List<String> row = data.data().get(0);
            assertEquals("0", row.get(2));
            assertEquals("3", row.get(3));
            assertEquals("0", row.get(4));
            assertNull(row.get(5));
            assertNull(row.get(8));

            LazyPlan empty = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Int64")), List.<Object[]>of());
            TableData emptyData = new DescribeExecutor()
                    .execute(NodeTestSupport.context(db, "{}"), List.of(empty))
                    .preview(10);
            assertEquals(1, emptyData.rows());
            assertEquals("0", emptyData.data().get(0).get(2));
            assertEquals("0", emptyData.data().get(0).get(3));
            assertEquals("0", emptyData.data().get(0).get(4));
        }
    }
}
