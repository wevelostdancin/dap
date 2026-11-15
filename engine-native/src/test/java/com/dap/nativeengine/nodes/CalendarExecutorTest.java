package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dap.calendar: timezone-aware calendar features. */
class CalendarExecutorTest {
    private static final List<ColumnSpec> DATES = List.of(ColumnSpec.of("day", "Date32"));
    private static final List<ColumnSpec> TIMESTAMPS = List.of(ColumnSpec.of("ts", "Timestamp"));

    private static LazyPlan calendar(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows,
                                     String config) throws Exception {
        LazyPlan input = NodeTestSupport.table(db, columns, rows);
        NodeContext context = NodeTestSupport.context(db, config);
        return new CalendarExecutor().execute(context, List.of(input));
    }

    @Test
    void weekdayFeaturesOfTheFirstOfJanuary2024() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = calendar(db, DATES,
                    List.<Object[]>of(new Object[]{LocalDate.of(2024, 1, 1)}),
                    "{\"column\":\"day\"}");

            assertEquals(List.of("day", "day_year", "day_quarter", "day_month", "day_week",
                    "day_day", "day_dow", "day_doy", "day_is_weekend"),
                    plan.columns().stream().map(ColumnSpec::name).toList());
            TableData data = plan.preview(10);
            assertEquals(List.of("2024-01-01", "2024", "1", "1", "1", "1", "1", "1", "false"),
                    data.data().get(0));
        }
    }

    @Test
    void saturdayIsAWeekend() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = calendar(db, DATES,
                    List.<Object[]>of(new Object[]{LocalDate.of(2024, 1, 6)}),
                    "{\"column\":\"day\"}");

            List<String> row = plan.preview(10).data().get(0);
            assertEquals("6", row.get(6), "dow");
            assertEquals("true", row.get(8), "is_weekend");
        }
    }

    @Test
    void holidaysMarkTheMatchingDays() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = calendar(db, DATES,
                    List.<Object[]>of(new Object[]{LocalDate.of(2024, 1, 1)},
                            new Object[]{LocalDate.of(2024, 1, 2)}),
                    """
                    {"column":"day","holidays":["2024-01-01"],"holiday_column":"is_holiday"}
                    """);

            TableData data = plan.preview(10);
            assertEquals("true", data.data().get(0).get(9));
            assertEquals("false", data.data().get(1).get(9));
            assertEquals("is_holiday", plan.columns().get(9).name());
        }
    }

    @Test
    void timezoneConvertsUtcToLocalWallTime() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = calendar(db, TIMESTAMPS,
                    List.<Object[]>of(new Object[]{LocalDateTime.of(2024, 1, 1, 22, 0)}),
                    "{\"column\":\"ts\",\"timezone\":\"Europe/Moscow\"}");

            List<String> row = plan.preview(10).data().get(0);
            assertEquals("2", row.get(5), "day in Moscow: " + row);
            assertEquals("2", row.get(6), "dow in Moscow: " + row);
        }
    }

    @Test
    void explicitFeatureListSelectsColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = calendar(db, DATES,
                    List.<Object[]>of(new Object[]{LocalDate.of(2024, 3, 31)}),
                    "{\"column\":\"day\",\"features\":[\"year\",\"quarter\",\"month\"]}");

            assertEquals(List.of("day", "day_year", "day_quarter", "day_month"),
                    plan.columns().stream().map(ColumnSpec::name).toList());
        }
    }

    @Test
    void unknownColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> calendar(db, DATES, List.<Object[]>of(new Object[]{LocalDate.of(2024, 1, 1)}),
                            "{\"column\":\"nope\"}"));
            assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
        }
    }

    @Test
    void nonDateColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Int64")),
                    List.<Object[]>of(new Object[]{1L}));
            NodeContext context = NodeTestSupport.context(db, "{\"column\":\"v\"}");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CalendarExecutor().execute(context, List.of(input)));
            assertTrue(failure.getMessage().contains("v"), failure.getMessage());
        }
    }

    @Test
    void unknownTimezoneIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> calendar(db, TIMESTAMPS,
                            List.<Object[]>of(new Object[]{LocalDateTime.of(2024, 1, 1, 0, 0)}),
                            "{\"column\":\"ts\",\"timezone\":\"Mars/Olympus\"}"));
            assertTrue(failure.getMessage().contains("timezone"), failure.getMessage());
        }
    }

    @Test
    void isHolidayWithoutHolidaysIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> calendar(db, DATES, List.<Object[]>of(new Object[]{LocalDate.of(2024, 1, 1)}),
                            "{\"column\":\"day\",\"features\":[\"is_holiday\"]}"));
            assertTrue(failure.getMessage().contains("holidays"), failure.getMessage());
        }
    }

    @Test
    void unknownFeatureIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> calendar(db, DATES, List.<Object[]>of(new Object[]{LocalDate.of(2024, 1, 1)}),
                            "{\"column\":\"day\",\"features\":[\"decade\"]}"));
            assertTrue(failure.getMessage().contains("decade"), failure.getMessage());
        }
    }

    @Test
    void invalidHolidayDateIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> calendar(db, DATES, List.<Object[]>of(new Object[]{LocalDate.of(2024, 1, 1)}),
                            "{\"column\":\"day\",\"holidays\":[\"01.01.2024\"]}"));
            assertTrue(failure.getMessage().contains("holidays"), failure.getMessage());
        }
    }
}
