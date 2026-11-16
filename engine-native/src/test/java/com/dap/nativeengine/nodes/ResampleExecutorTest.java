package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResampleExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("ts", "Timestamp"),
            ColumnSpec.of("v", "Float64"));

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2024, 1, 1, hour, minute);
    }

    private static LazyPlan resample(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows, String config)
            throws Exception {
        return new ResampleExecutor().execute(NodeTestSupport.context(db, config),
                List.of(NodeTestSupport.table(db, columns, rows)));
    }

    @Test
    void hourlyBucketsAggregateByFunction() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = resample(db, COLUMNS, List.of(
                    new Object[]{at(10, 5), 1.0},
                    new Object[]{at(10, 45), 2.0},
                    new Object[]{at(11, 10), 3.0}), """
                    {"timeColumn":"ts","every":"1 hour",
                     "aggregations":[{"column":"v","fn":"mean"},{"column":"v","fn":"count"}]}
                    """);

            assertEquals(List.of(ColumnSpec.of("ts", "Timestamp"),
                    ColumnSpec.of("Mean(v)", "Float64"),
                    ColumnSpec.of("Count(v)", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2L, data.rows());
            assertEquals(List.of("2024-01-01T10:00", "1.5", "2"), data.data().get(0));
            assertEquals(List.of("2024-01-01T11:00", "3.0", "1"), data.data().get(1));
        }
    }

    @Test
    void customOutputNamesKeepTheColumnTypes() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("ts", "Timestamp"), ColumnSpec.of("n", "Int64"));
            LazyPlan plan = resample(db, columns, List.of(
                    new Object[]{at(10, 5), 1L},
                    new Object[]{at(10, 10), 2L},
                    new Object[]{at(11, 5), 10L}), """
                    {"timeColumn":"ts","every":"1 hour","aggregations":[
                       {"column":"n","fn":"sum","output":"total"},
                       {"column":"n","fn":"min","output":"lo"},
                       {"column":"n","fn":"max","output":"hi"}]}
                    """);

            assertEquals(List.of(ColumnSpec.of("ts", "Timestamp"),
                    ColumnSpec.of("total", "Int64"),
                    ColumnSpec.of("lo", "Int64"),
                    ColumnSpec.of("hi", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("2024-01-01T10:00", "3", "1", "2"), data.data().get(0));
            assertEquals(List.of("2024-01-01T11:00", "10", "10", "10"), data.data().get(1));
        }
    }

    @Test
    void aggregatesIgnoreNulls() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = resample(db, COLUMNS, List.of(
                    new Object[]{at(10, 5), null},
                    new Object[]{at(10, 10), 2.0},
                    new Object[]{at(10, 20), 4.0}), """
                    {"timeColumn":"ts","every":"1 hour",
                     "aggregations":[{"column":"v","fn":"mean"},{"column":"v","fn":"count"}]}
                    """).preview(10);

            assertEquals(1L, data.rows());
            assertEquals(List.of("2024-01-01T10:00", "3.0", "2"), data.data().get(0));
        }
    }

    @Test
    void monthlyBucketsUseCalendarMonths() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = resample(db, COLUMNS, List.of(
                    new Object[]{LocalDateTime.of(2024, 1, 15, 10, 0), 1.0},
                    new Object[]{LocalDateTime.of(2024, 1, 20, 10, 0), 2.0},
                    new Object[]{LocalDateTime.of(2024, 2, 3, 10, 0), 3.0}), """
                    {"timeColumn":"ts","every":"1 month","aggregations":[{"column":"v","fn":"sum"}]}
                    """).preview(10);

            assertEquals(2L, data.rows());
            assertEquals(List.of("2024-01-01T00:00", "3.0"), data.data().get(0));
            assertEquals(List.of("2024-02-01T00:00", "3.0"), data.data().get(1));
        }
    }

    @Test
    void emptyInputProducesNoBuckets() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = resample(db, COLUMNS, List.<Object[]>of(), """
                    {"timeColumn":"ts","every":"1 day","aggregations":[{"column":"v","fn":"sum"}]}
                    """).preview(10);
            assertEquals(0L, data.rows());
        }
    }

    @Test
    void invalidConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = List.<Object[]>of(new Object[]{at(10, 5), 1.0});

            IllegalArgumentException timeColumn = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, COLUMNS, rows, """
                            {"timeColumn":"nope","every":"1 hour","aggregations":[{"column":"v","fn":"mean"}]}
                            """));
            assertTrue(timeColumn.getMessage().contains("nope"), timeColumn.getMessage());

            List<ColumnSpec> dates = List.of(ColumnSpec.of("d", "Date32"), ColumnSpec.of("v", "Float64"));
            IllegalArgumentException notTimestamp = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, dates, List.<Object[]>of(new Object[]{java.time.LocalDate.of(2024, 1, 1), 1.0}), """
                            {"timeColumn":"d","every":"1 day","aggregations":[{"column":"v","fn":"mean"}]}
                            """));
            assertTrue(notTimestamp.getMessage().contains("Timestamp"), notTimestamp.getMessage());

            IllegalArgumentException badEvery = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, COLUMNS, rows, """
                            {"timeColumn":"ts","every":"1 fortnight","aggregations":[{"column":"v","fn":"mean"}]}
                            """));
            assertTrue(badEvery.getMessage().contains("fortnight"), badEvery.getMessage());

            IllegalArgumentException zeroEvery = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, COLUMNS, rows, """
                            {"timeColumn":"ts","every":"0 day","aggregations":[{"column":"v","fn":"mean"}]}
                            """));
            assertTrue(zeroEvery.getMessage().contains("positive"), zeroEvery.getMessage());

            TableData defaultEvery = resample(db, COLUMNS, rows, """
                    {"timeColumn":"ts","aggregations":[{"column":"v","fn":"mean"}]}
                    """).preview(10);
            assertEquals(1, defaultEvery.rows(), "every defaults to 1 day");

            IllegalArgumentException noAggregations = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, COLUMNS, rows, """
                            {"timeColumn":"ts","every":"1 hour","aggregations":[]}
                            """));
            assertTrue(noAggregations.getMessage().contains("non-empty"), noAggregations.getMessage());

            IllegalArgumentException fn = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, COLUMNS, rows, """
                            {"timeColumn":"ts","every":"1 hour","aggregations":[{"column":"v","fn":"median"}]}
                            """));
            assertTrue(fn.getMessage().contains("median"), fn.getMessage());

            List<ColumnSpec> text = List.of(ColumnSpec.of("ts", "Timestamp"), ColumnSpec.of("s", "Utf8"));
            IllegalArgumentException notNumeric = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, text, List.<Object[]>of(new Object[]{at(10, 5), "x"}), """
                            {"timeColumn":"ts","every":"1 hour","aggregations":[{"column":"s","fn":"mean"}]}
                            """));
            assertTrue(notNumeric.getMessage().contains("numeric"), notNumeric.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, COLUMNS, rows, """
                            {"timeColumn":"ts","every":"1 hour","aggregations":[
                               {"column":"v","fn":"mean","output":"x"},
                               {"column":"v","fn":"max","output":"x"}]}
                            """));
            assertTrue(duplicate.getMessage().contains("duplicates"), duplicate.getMessage());

            IllegalArgumentException unknownAggregate = assertThrows(IllegalArgumentException.class,
                    () -> resample(db, COLUMNS, rows, """
                            {"timeColumn":"ts","every":"1 hour","aggregations":[{"column":"nope","fn":"mean"}]}
                            """));
            assertTrue(unknownAggregate.getMessage().contains("nope"), unknownAggregate.getMessage());
        }
    }
}
