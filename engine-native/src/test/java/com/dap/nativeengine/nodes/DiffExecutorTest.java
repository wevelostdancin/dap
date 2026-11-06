package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("ts", "Timestamp"),
            ColumnSpec.of("grp", "Utf8"),
            ColumnSpec.of("v", "Int64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{at(0), "a", 10L},
            new Object[]{at(10), "a", 13L},
            new Object[]{at(20), "a", 18L},
            new Object[]{at(5), "b", 5L},
            new Object[]{at(15), "b", 12L});

    private static LocalDateTime at(int minute) {
        return LocalDateTime.of(2024, 1, 1, 10, minute);
    }

    private static LazyPlan diff(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows, String config)
            throws Exception {
        return new DiffExecutor().execute(NodeTestSupport.context(db, config),
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
    void consecutiveDifferencesStartEachPartitionWithNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = diff(db, COLUMNS, ROWS, """
                    {"column":"v","output":"delta","partitionBy":["grp"],
                     "orderBy":[{"column":"ts","direction":"asc"}]}
                    """);

            assertEquals(List.of(ColumnSpec.of("ts", "Timestamp"),
                    ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Int64"),
                    ColumnSpec.of("delta", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(5L, data.rows());
            assertEquals(Arrays.asList("2024-01-01T10:00", "a", "10", null), row(data, "a", "2024-01-01T10:00"));
            assertEquals(List.of("2024-01-01T10:10", "a", "13", "3"), row(data, "a", "2024-01-01T10:10"));
            assertEquals(List.of("2024-01-01T10:20", "a", "18", "5"), row(data, "a", "2024-01-01T10:20"));
            assertEquals(Arrays.asList("2024-01-01T10:05", "b", "5", null), row(data, "b", "2024-01-01T10:05"));
            assertEquals(List.of("2024-01-01T10:15", "b", "12", "7"), row(data, "b", "2024-01-01T10:15"));
        }
    }

    @Test
    void descendingOrderFlipsTheSign() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = diff(db, COLUMNS, ROWS, """
                    {"column":"v","output":"delta","partitionBy":["grp"],
                     "orderBy":[{"column":"ts","direction":"desc"}]}
                    """).preview(10);

            assertEquals(Arrays.asList("2024-01-01T10:20", "a", "18", null), row(data, "a", "2024-01-01T10:20"));
            assertEquals(List.of("2024-01-01T10:10", "a", "13", "-5"), row(data, "a", "2024-01-01T10:10"));
            assertEquals(List.of("2024-01-01T10:00", "a", "10", "-3"), row(data, "a", "2024-01-01T10:00"));
        }
    }

    @Test
    void floatColumnsKeepTheirType() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("ts", "Timestamp"), ColumnSpec.of("v", "Float64"));
            LazyPlan plan = diff(db, columns, List.of(
                    new Object[]{at(0), 1.0},
                    new Object[]{at(10), 2.5},
                    new Object[]{at(20), 4.0}), """
                    {"column":"v","output":"delta","orderBy":[{"column":"ts","direction":"asc"}]}
                    """);

            assertEquals(ColumnSpec.of("delta", "Float64"), plan.columns().get(2));
            TableData data = plan.preview(10);
            assertEquals(Arrays.asList("2024-01-01T10:00", "1.0", null), data.data().get(0));
            assertEquals(List.of("2024-01-01T10:10", "2.5", "1.5"), data.data().get(1));
            assertEquals(List.of("2024-01-01T10:20", "4.0", "1.5"), data.data().get(2));
        }
    }

    @Test
    void singleRowPartitionStaysNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("ts", "Timestamp"), ColumnSpec.of("v", "Int64"));
            TableData data = diff(db, columns, List.<Object[]>of(new Object[]{at(0), 1L}), """
                    {"column":"v","output":"delta","orderBy":[{"column":"ts","direction":"asc"}]}
                    """).preview(10);
            assertEquals(1L, data.rows());
            assertNull(data.data().get(0).get(2));
        }
    }

    @Test
    void invalidConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = List.<Object[]>of(new Object[]{at(0), "a", 1L});

            IllegalArgumentException noOrder = assertThrows(IllegalArgumentException.class,
                    () -> diff(db, COLUMNS, rows, """
                            {"column":"v","output":"delta"}
                            """));
            assertTrue(noOrder.getMessage().contains("orderBy"), noOrder.getMessage());

            IllegalArgumentException notNumeric = assertThrows(IllegalArgumentException.class,
                    () -> diff(db, COLUMNS, rows, """
                            {"column":"grp","output":"delta","orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(notNumeric.getMessage().contains("numeric"), notNumeric.getMessage());

            IllegalArgumentException column = assertThrows(IllegalArgumentException.class,
                    () -> diff(db, COLUMNS, rows, """
                            {"column":"nope","output":"delta","orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(column.getMessage().contains("nope"), column.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> diff(db, COLUMNS, rows, """
                            {"column":"v","output":"v","orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(duplicate.getMessage().contains("duplicates"), duplicate.getMessage());

            IllegalArgumentException direction = assertThrows(IllegalArgumentException.class,
                    () -> diff(db, COLUMNS, rows, """
                            {"column":"v","output":"delta","orderBy":[{"column":"ts","direction":"up"}]}
                            """));
            assertTrue(direction.getMessage().contains("up"), direction.getMessage());

            IllegalArgumentException output = assertThrows(IllegalArgumentException.class,
                    () -> diff(db, COLUMNS, rows, """
                            {"column":"v","orderBy":[{"column":"ts","direction":"asc"}]}
                            """));
            assertTrue(output.getMessage().contains("output"), output.getMessage());
        }
    }
}
