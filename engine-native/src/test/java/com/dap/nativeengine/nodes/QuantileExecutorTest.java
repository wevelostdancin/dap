package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantileExecutorTest {
    private static LazyPlan onesToTen(DuckDbSession db) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (long value = 1; value <= 10; value++) {
            rows.add(new Object[]{value});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Int64")), rows);
    }

    private static List<String> values(TableData data) {
        return data.data().stream().map(row -> row.get(1)).toList();
    }

    @Test
    void continuousQuantilesInterpolateBetweenValues() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new QuantileExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"quantiles\":[0.25,0.5,0.75]}"),
                    List.of(onesToTen(db)));

            assertEquals(List.of(ColumnSpec.of("quantile", "Float64"), ColumnSpec.of("value", "Float64")),
                    plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("0.25", "0.5", "0.75"),
                    data.data().stream().map(row -> row.get(0)).toList());
            assertEquals(3.25, Double.parseDouble(values(data).get(0)), 1e-9);
            assertEquals(5.5, Double.parseDouble(values(data).get(1)), 1e-9);
            assertEquals(7.75, Double.parseDouble(values(data).get(2)), 1e-9);
        }
    }

    @Test
    void boundaryQuantilesReturnMinimumAndMaximum() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = new QuantileExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"quantiles\":[0,1]}"),
                    List.of(onesToTen(db))).preview(10);
            assertEquals(2, data.rows());
            assertEquals(1.0, Double.parseDouble(values(data).get(0)), 1e-9);
            assertEquals(10.0, Double.parseDouble(values(data).get(1)), 1e-9);
        }
    }

    @Test
    void singleValueColumnRepeatsThatValueAndEmptyInputGivesNulls() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan single = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Int64")),
                    List.<Object[]>of(new Object[]{5L}));
            TableData data = new QuantileExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"quantiles\":[0,0.5,1]}"),
                    List.of(single)).preview(10);
            assertEquals(List.of("5.0", "5.0", "5.0"), values(data));

            LazyPlan empty = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Int64")), List.<Object[]>of());
            TableData emptyData = new QuantileExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"quantiles\":[0.5]}"),
                    List.of(empty)).preview(10);
            assertEquals(1, emptyData.rows());
            assertNull(emptyData.data().get(0).get(1));
        }
    }

    @Test
    void invalidQuantilesAndColumnsAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = onesToTen(db);

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> new QuantileExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"v\",\"quantiles\":[]}"), List.of(input)));
            assertTrue(empty.getMessage().contains("quantiles"), empty.getMessage());

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new QuantileExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"v\"}"), List.of(input)));
            assertTrue(missing.getMessage().contains("quantiles"), missing.getMessage());

            IllegalArgumentException tooHigh = assertThrows(IllegalArgumentException.class,
                    () -> new QuantileExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"v\",\"quantiles\":[1.5]}"), List.of(input)));
            assertTrue(tooHigh.getMessage().contains("[0, 1]"), tooHigh.getMessage());

            IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                    () -> new QuantileExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"v\",\"quantiles\":[-0.1]}"), List.of(input)));
            assertTrue(negative.getMessage().contains("[0, 1]"), negative.getMessage());

            IllegalArgumentException text = assertThrows(IllegalArgumentException.class,
                    () -> new QuantileExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"v\",\"quantiles\":[\"0.5\"]}"),
                            List.of(input)));
            assertTrue(text.getMessage().contains("numbers"), text.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> new QuantileExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"nope\",\"quantiles\":[0.5]}"),
                            List.of(input)));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            LazyPlan label = NodeTestSupport.table(db, List.of(ColumnSpec.of("s", "Utf8")),
                    List.<Object[]>of(new Object[]{"a"}));
            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> new QuantileExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"s\",\"quantiles\":[0.5]}"),
                            List.of(label)));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());
        }
    }
}
