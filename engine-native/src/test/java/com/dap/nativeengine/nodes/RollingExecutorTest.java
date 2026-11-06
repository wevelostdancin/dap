package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollingExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("ts", "Timestamp"),
            ColumnSpec.of("grp", "Utf8"),
            ColumnSpec.of("v", "Int64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{at(0), "a", 1L},
            new Object[]{at(10), "a", 2L},
            new Object[]{at(20), "a", 3L},
            new Object[]{at(30), "a", 4L},
            new Object[]{at(5), "b", 10L},
            new Object[]{at(15), "b", 20L});

    private static LocalDateTime at(int minute) {
        return LocalDateTime.of(2024, 1, 1, 10, minute);
    }

    private static LazyPlan rolling(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows, String config)
            throws Exception {
        return new RollingExecutor().execute(NodeTestSupport.context(db, config),
                List.of(NodeTestSupport.table(db, columns, rows)));
    }

    private static List<String> row(TableData data, String grp, String timestamp) {
        return data.data().stream()
                .filter(candidate -> Objects.equals(candidate.get(0), timestamp)
                        && Objects.equals(candidate.get(1), grp))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + grp + "/" + timestamp + " in " + data.data()));
    }

    @Test
    void meanUsesATrailingWindowPerPartition() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = rolling(db, COLUMNS, ROWS, """
                    {"column":"v","output":"roll","window":3,"fn":"mean",
                     "partitionBy":["grp"],"orderBy":[{"column":"ts","direction":"asc"}]}
                    """);

            assertEquals(List.of(ColumnSpec.of("ts", "Timestamp"),
                    ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Int64"),
                    ColumnSpec.of("roll", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(6L, data.rows());
            assertEquals(List.of("2024-01-01T10:00", "a", "1", "1.0"), row(data, "a", "2024-01-01T10:00"));
            assertEquals(List.of("2024-01-01T10:10", "a", "2", "1.5"), row(data, "a", "2024-01-01T10:10"));
            assertEquals(List.of("2024-01-01T10:20", "a", "3", "2.0"), row(data, "a", "2024-01-01T10:20"));
            assertEquals(List.of("2024-01-01T10:30", "a", "4", "3.0"), row(data, "a", "2024-01-01T10:30"));
            assertEquals(List.of("2024-01-01T10:05", "b", "10", "10.0"), row(data, "b", "2024-01-01T10:05"));
            assertEquals(List.of("2024-01-01T10:15", "b", "20", "15.0"), row(data, "b", "2024-01-01T10:15"));
        }
    }

    @Test
    void sumKeepsTheColumnType() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = rolling(db, COLUMNS, ROWS, """
                    {"column":"v","output":"total","window":2,"fn":"sum",
                     "partitionBy":["grp"],"orderBy":[{"column":"ts","direction":"asc"}]}
                    """);
            assertEquals(ColumnSpec.of("total", "Int64"), plan.columns().get(3));
            TableData data = plan.preview(10);
            assertEquals(List.of("2024-01-01T10:00", "a", "1", "1"), row(data, "a", "2024-01-01T10:00"));
            assertEquals(List.of("2024-01-01T10:10", "a", "2", "3"), row(data, "a", "2024-01-01T10:10"));
            assertEquals(List.of("2024-01-01T10:20", "a", "3", "5"), row(data, "a", "2024-01-01T10:20"));
            assertEquals(List.of("2024-01-01T10:30", "a", "4", "7"), row(data, "a", "2024-01-01T10:30"));
            assertEquals(List.of("2024-01-01T10:05", "b", "10", "10"), row(data, "b", "2024-01-01T10:05"));
            assertEquals(List.of("2024-01-01T10:15", "b", "20", "30"), row(data, "b", "2024-01-01T10:15"));
        }
    }

    @Test
    void minAndMaxUseTheSameTrailingWindow() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = rolling(db, COLUMNS, ROWS, """
                    {"column":"v","output":"lo","window":2,"fn":"min",
                     "partitionBy":["grp"],"orderBy":[{"column":"ts","direction":"asc"}]}
                    """);
            TableData data = plan.preview(10);
            assertEquals(List.of("2024-01-01T10:10", "a", "2", "1"), row(data, "a", "2024-01-01T10:10"));
            assertEquals(List.of("2024-01-01T10:20", "a", "3", "2"), row(data, "a", "2024-01-01T10:20"));
            assertEquals(List.of("2024-01-01T10:15", "b", "20", "10"), row(data, "b", "2024-01-01T10:15"));

            LazyPlan maximum = rolling(db, COLUMNS, ROWS, """
                    {"column":"v","output":"hi","window":2,"fn":"max",
                     "partitionBy":["grp"],"orderBy":[{"column":"ts","direction":"asc"}]}
                    """);
            TableData top = maximum.preview(10);
            assertEquals(List.of("2024-01-01T10:10", "a", "2", "2"), row(top, "a", "2024-01-01T10:10"));
            assertEquals(List.of("2024-01-01T10:20", "a", "3", "3"), row(top, "a", "2024-01-01T10:20"));
            assertEquals(List.of("2024-01-01T10:15", "b", "20", "20"), row(top, "b", "2024-01-01T10:15"));
        }
    }

    @Test
    void descendingOrderAndSmallWindowsAreHonoured() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData descending = rolling(db, COLUMNS, ROWS, """
                    {"column":"v","output":"roll","window":2,"fn":"mean",
                     "partitionBy":["grp"],"orderBy":[{"column":"ts","direction":"desc"}]}
                    """).preview(10);
            assertEquals(List.of("2024-01-01T10:30", "a", "4", "4.0"), row(descending, "a", "2024-01-01T10:30"));
            assertEquals(List.of("2024-01-01T10:20", "a", "3", "3.5"), row(descending, "a", "2024-01-01T10:20"));
            assertEquals(List.of("2024-01-01T10:00", "a", "1", "1.5"), row(descending, "a", "2024-01-01T10:00"));

            TableData single = rolling(db, COLUMNS, ROWS, """
                    {"column":"v","output":"roll","window":1,"fn":"mean",
                     "partitionBy":["grp"],"orderBy":[{"column":"ts","direction":"asc"}]}
                    """).preview(10);
            assertEquals(List.of("2024-01-01T10:10", "a", "2", "2.0"), row(single, "a", "2024-01-01T10:10"));

            TableData wide = rolling(db, COLUMNS, ROWS, """
                    {"column":"v","output":"roll","window":10,"fn":"mean",
                     "partitionBy":["grp"],"orderBy":[{"column":"ts","direction":"asc"}]}
                    """).preview(10);
            assertEquals(List.of("2024-01-01T10:30", "a", "4", "2.5"), row(wide, "a", "2024-01-01T10:30"));
            assertEquals(List.of("2024-01-01T10:15", "b", "20", "15.0"), row(wide, "b", "2024-01-01T10:15"));
        }
    }

    @Test
    void invalidConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = List.<Object[]>of(new Object[]{at(0), "a", 1L});

            IllegalArgumentException zeroWindow = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"v","output":"roll","window":0,"fn":"mean",
                             "orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(zeroWindow.getMessage().contains("positive"), zeroWindow.getMessage());

            IllegalArgumentException fractional = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"v","output":"roll","window":2.5,"fn":"mean",
                             "orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(fractional.getMessage().contains("window"), fractional.getMessage());

            IllegalArgumentException noWindow = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"v","output":"roll","fn":"mean",
                             "orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(noWindow.getMessage().contains("window"), noWindow.getMessage());

            IllegalArgumentException noOrder = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"v","output":"roll","window":2,"fn":"mean"}
                            """));
            assertTrue(noOrder.getMessage().contains("orderBy"), noOrder.getMessage());

            IllegalArgumentException orderColumn = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"v","output":"roll","window":2,"fn":"mean",
                             "orderBy":[{"column":"nope","direction":"asc"}]}
                            """));
            assertTrue(orderColumn.getMessage().contains("nope"), orderColumn.getMessage());

            IllegalArgumentException direction = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"v","output":"roll","window":2,"fn":"mean",
                             "orderBy":[{"column":"ts","direction":"sideways"}]}
                            """));
            assertTrue(direction.getMessage().contains("sideways"), direction.getMessage());

            IllegalArgumentException notNumeric = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"grp","output":"roll","window":2,"fn":"mean",
                             "orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(notNumeric.getMessage().contains("numeric"), notNumeric.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"v","output":"v","window":2,"fn":"mean",
                             "orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(duplicate.getMessage().contains("duplicates"), duplicate.getMessage());

            IllegalArgumentException fn = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"v","output":"roll","window":2,"fn":"median",
                             "orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(fn.getMessage().contains("median"), fn.getMessage());

            IllegalArgumentException column = assertThrows(IllegalArgumentException.class,
                    () -> rolling(db, COLUMNS, rows, """
                            {"column":"nope","output":"roll","window":2,"fn":"mean",
                             "orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(column.getMessage().contains("nope"), column.getMessage());
        }
    }
}
