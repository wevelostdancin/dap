package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Phase 1 spike: Arrow -> DuckDB copy semantics and cost. */
class ArrowDuckDbSpikeTest {
    @Test
    void arrowTableRoundTripsNullAndEmptyString() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("id", "Int64"),
                ColumnSpec.of("note", "Utf8"),
                ColumnSpec.of("ratio", "Float64"));
        List<Object[]> rows = List.of(
                new Object[]{1L, "", 0.5},
                new Object[]{2L, null, null},
                new Object[]{3L, "text", 1.25});
        try (ArrowTable table = ArrowTable.of(columns, rows);
             DuckDbSession session = new DuckDbSession()) {
            String name = session.load(table, "spike");
            LazyPlan plan = new LazyPlan(session, "SELECT * FROM \"" + name + "\"", columns);
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals("", data.data().get(0).get(1));
            assertNull(data.data().get(1).get(1));
            assertEquals("text", data.data().get(2).get(1));
            assertEquals("Number (Integer)", data.columns().get(0).knimeType());
            assertEquals("String", data.columns().get(1).knimeType());
            assertEquals("Number (Float)", data.columns().get(2).knimeType());
            ProfileData profile = plan.profile();
            assertEquals(1, profile.columns().get(1).missing());
            assertEquals(2, profile.columns().get(1).distinct());
        }
    }

    @Test
    void arrowToDuckDbCopyThroughputIsMeasured() throws Exception {
        int rows = 200_000;
        List<Object[]> values = new java.util.ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            values.add(new Object[]{(long) i, "value-" + i, i * 0.5});
        }
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("id", "Int64"),
                ColumnSpec.of("label", "Utf8"),
                ColumnSpec.of("value", "Float64"));
        long started = System.nanoTime();
        try (ArrowTable table = ArrowTable.of(columns, values);
             DuckDbSession session = new DuckDbSession()) {
            String name = session.load(table, "bench");
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            LazyPlan plan = new LazyPlan(session, "SELECT * FROM \"" + name + "\"", columns);
            assertEquals(rows, plan.count());
            System.out.printf("spike: Arrow->DuckDB copy %d rows in %d ms (%.0f rows/s)%n",
                    rows, elapsedMillis, rows * 1000.0 / Math.max(1, elapsedMillis));
        }
    }
}
