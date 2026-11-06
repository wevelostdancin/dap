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

class DateTimeExecutorTest {
    private static LazyPlan text(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("raw", "Utf8"), ColumnSpec.of("id", "Int64")),
                List.of(new Object[]{"2024-01-02", 1L}, new Object[]{"2024-12-31", 2L}));
    }

    private static LazyPlan timestamps(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("ts", "Timestamp")),
                List.of(
                        new Object[]{LocalDateTime.of(2024, 1, 2, 15, 30)},
                        new Object[]{LocalDateTime.of(2024, 4, 1, 0, 0)},
                        new Object[]{LocalDateTime.of(2024, 7, 7, 8, 5)}));
    }

    @Test
    void parseAppendsTimestampAndKeepsTheSourceColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"raw","output":"parsed","op":"parse","format":"%Y-%m-%d"}
                    """), List.of(text(db)));

            assertEquals(List.of(ColumnSpec.of("raw", "Utf8"), ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("parsed", "Timestamp")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("2024-01-02", "1", "2024-01-02T00:00"), data.data().get(0));
            assertEquals(List.of("2024-12-31", "2", "2024-12-31T00:00"), data.data().get(1));
        }
    }

    @Test
    void formatAndExtractUseTheConfiguredPatternAndUnit() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan formatted = new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"ts","output":"label","op":"format","format":"%Y/%m/%d %H:%M"}
                    """), List.of(timestamps(db)));
            assertEquals(ColumnSpec.of("label", "Utf8"), formatted.columns().get(1));
            assertEquals(List.of("2024-01-02T15:30", "2024/01/02 15:30"),
                    formatted.preview(10).data().get(0));

            LazyPlan extracted = new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"ts","output":"month","op":"extract","unit":"month"}
                    """), List.of(timestamps(db)));
            assertEquals(ColumnSpec.of("month", "Int64"), extracted.columns().get(1));
            assertEquals(List.of("2024-01-02T15:30", "1"),
                    extracted.preview(10).data().get(0));
            assertEquals(List.of("2024-04-01T00:00", "4"),
                    extracted.preview(10).data().get(1));
        }
    }

    @Test
    void extractSupportsEveryDocumentedUnitIncludingDayOfWeek() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("d", "Date32")),
                    List.<Object[]>of(new Object[]{LocalDate.of(2024, 4, 1)}));
            // 2024-04-01 is a Monday
            for (String unit : List.of("year", "month", "day", "hour", "dow", "quarter", "week")) {
                LazyPlan plan = new DateTimeExecutor().execute(NodeTestSupport.context(db,
                        "{\"column\":\"d\",\"output\":\"v\",\"op\":\"extract\",\"unit\":\"" + unit + "\"}"),
                        List.of(input));
                assertEquals("2024-04-01", plan.preview(10).data().get(0).get(0), unit);
            }
            LazyPlan dow = new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"d","output":"dow","op":"extract","unit":"dow"}
                    """), List.of(input));
            assertEquals("1", dow.preview(10).data().get(0).get(1));
        }
    }

    @Test
    void invalidOpUnitFormatAndOutputAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan textInput = text(db);
            LazyPlan timestampInput = timestamps(db);

            IllegalArgumentException op = assertThrows(IllegalArgumentException.class,
                    () -> new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"raw","output":"x","op":"explode","format":"%Y"}
                            """), List.of(textInput)));
            assertTrue(op.getMessage().contains("explode"), op.getMessage());

            IllegalArgumentException format = assertThrows(IllegalArgumentException.class,
                    () -> new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"raw","output":"x","op":"parse"}
                            """), List.of(textInput)));
            assertTrue(format.getMessage().contains("format"), format.getMessage());

            IllegalArgumentException unit = assertThrows(IllegalArgumentException.class,
                    () -> new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"ts","output":"x","op":"extract","unit":"fortnight"}
                            """), List.of(timestampInput)));
            assertTrue(unit.getMessage().contains("fortnight"), unit.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"raw","output":"raw","op":"parse","format":"%Y"}
                            """), List.of(textInput)));
            assertTrue(duplicate.getMessage().contains("raw"), duplicate.getMessage());
        }
    }

    @Test
    void parseRejectsNonTextAndFormatRejectsNonTemporalColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan numbers = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("n", "Int64")), List.<Object[]>of(new Object[]{1L}));

            IllegalArgumentException parse = assertThrows(IllegalArgumentException.class,
                    () -> new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"n","output":"x","op":"parse","format":"%Y"}
                            """), List.of(numbers)));
            assertTrue(parse.getMessage().contains("Utf8"), parse.getMessage());

            IllegalArgumentException format = assertThrows(IllegalArgumentException.class,
                    () -> new DateTimeExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"n","output":"x","op":"format","format":"%Y"}
                            """), List.of(numbers)));
            assertTrue(format.getMessage().contains("Timestamp"), format.getMessage());
        }
    }
}