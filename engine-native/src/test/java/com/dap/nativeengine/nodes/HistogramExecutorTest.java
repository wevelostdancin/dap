package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistogramExecutorTest {
    private static LazyPlan doubles(DuckDbSession db, Double... values) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (Double value : values) {
            rows.add(new Object[]{value});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Float64")), rows);
    }

    private static List<String> counts(TableData data) {
        return data.data().stream().map(row -> row.get(2)).toList();
    }

    @Test
    void equalWidthBinsClampTheMaximumIntoTheLastBin() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = doubles(db, 0.0, 1.0, 2.0, 3.0, 4.0);

            LazyPlan plan = new HistogramExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"bins\":2}"), List.of(input));

            assertEquals(List.of(
                    ColumnSpec.of("bin_start", "Float64"),
                    ColumnSpec.of("bin_end", "Float64"),
                    ColumnSpec.of("count", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(0.0, Double.parseDouble(data.data().get(0).get(0)), 1e-9);
            assertEquals(2.0, Double.parseDouble(data.data().get(0).get(1)), 1e-9);
            assertEquals(2.0, Double.parseDouble(data.data().get(1).get(0)), 1e-9);
            assertEquals(4.0, Double.parseDouble(data.data().get(1).get(1)), 1e-9);
            assertEquals(List.of("2", "3"), counts(data));
        }
    }

    @Test
    void constantColumnPutsEveryValueIntoTheFirstBin() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = doubles(db, 7.0, 7.0, 7.0);

            TableData data = new HistogramExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"bins\":3}"), List.of(input)).preview(10);
            assertEquals(3, data.rows());
            for (List<String> row : data.data()) {
                assertEquals(7.0, Double.parseDouble(row.get(0)), 1e-9);
                assertEquals(7.0, Double.parseDouble(row.get(1)), 1e-9);
            }
            assertEquals(List.of("3", "0", "0"), counts(data));
        }
    }

    @Test
    void emptyBinsAreEmittedBetweenSparseValues() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = doubles(db, 0.0, 10.0);

            TableData data = new HistogramExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"bins\":5}"), List.of(input)).preview(10);
            assertEquals(5, data.rows());
            assertEquals(List.of("1", "0", "0", "0", "1"), counts(data));
            assertEquals(2.0, Double.parseDouble(data.data().get(1).get(0)), 1e-9);
            assertEquals(4.0, Double.parseDouble(data.data().get(1).get(1)), 1e-9);
        }
    }

    @Test
    void nullsAreExcludedAndEmptyInputGivesNoBins() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = doubles(db, 1.0, 2.0, null, 3.0);

            TableData data = new HistogramExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"bins\":2}"), List.of(input)).preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("1", "2"), counts(data));

            LazyPlan empty = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Float64")), List.<Object[]>of());
            TableData emptyData = new HistogramExecutor().execute(
                    NodeTestSupport.context(db, "{\"column\":\"v\",\"bins\":2}"), List.of(empty)).preview(10);
            assertEquals(0, emptyData.rows());
        }
    }

    @Test
    void invalidBinsAndColumnsAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = doubles(db, 1.0, 2.0, 3.0);

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new HistogramExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"v\"}"), List.of(input)));
            assertTrue(missing.getMessage().contains("bins"), missing.getMessage());

            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                    () -> new HistogramExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"v\",\"bins\":0}"), List.of(input)));
            assertTrue(zero.getMessage().contains("greater than 0"), zero.getMessage());

            IllegalArgumentException fractional = assertThrows(IllegalArgumentException.class,
                    () -> new HistogramExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"v\",\"bins\":2.5}"), List.of(input)));
            assertTrue(fractional.getMessage().contains("integer"), fractional.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> new HistogramExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"nope\",\"bins\":2}"), List.of(input)));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            LazyPlan text = NodeTestSupport.table(db, List.of(ColumnSpec.of("s", "Utf8")),
                    List.<Object[]>of(new Object[]{"a"}));
            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> new HistogramExecutor().execute(
                            NodeTestSupport.context(db, "{\"column\":\"s\",\"bins\":2}"), List.of(text)));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());
        }
    }
}
