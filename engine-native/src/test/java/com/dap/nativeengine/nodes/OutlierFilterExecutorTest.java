package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutlierFilterExecutorTest {
    private static LazyPlan filter(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows, String config)
            throws Exception {
        return new OutlierFilterExecutor().execute(NodeTestSupport.context(db, config),
                List.of(NodeTestSupport.table(db, columns, rows)));
    }

    private static List<String> values(TableData data, int index) {
        return data.data().stream().map(row -> row.get(index))
                .sorted(Comparator.nullsFirst(Comparator.<String>naturalOrder())).toList();
    }

    @Test
    void iqrDropsTheOutlierByDefault() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = filter(db, List.of(ColumnSpec.of("v", "Float64")), List.of(
                    new Object[]{1.0},
                    new Object[]{2.0},
                    new Object[]{3.0},
                    new Object[]{4.0},
                    new Object[]{100.0}), """
                    {"columns":["v"],"method":"iqr"}
                    """);

            TableData data = plan.preview(10);
            assertEquals(4L, data.rows());
            assertEquals(List.of("1.0", "2.0", "3.0", "4.0"), values(data, 0));
        }
    }

    @Test
    void zscoreThresholdControlsTheKeptRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Float64"));
            List<Object[]> rows = List.of(
                    new Object[]{1.0},
                    new Object[]{2.0},
                    new Object[]{3.0});

            LazyPlan defaulted = filter(db, columns, rows, """
                    {"columns":["v"],"method":"zscore"}
                    """);
            assertEquals(3L, defaulted.preview(10).rows());

            LazyPlan strict = filter(db, columns, rows, """
                    {"columns":["v"],"method":"zscore","threshold":0.5}
                    """);
            TableData data = strict.preview(10);
            assertEquals(1L, data.rows());
            assertEquals(List.of("2.0"), values(data, 0));
        }
    }

    @Test
    void nullValuesPassTheFilter() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = filter(db, List.of(ColumnSpec.of("v", "Int64")), List.of(
                    new Object[]{null},
                    new Object[]{1L},
                    new Object[]{2L},
                    new Object[]{3L},
                    new Object[]{4L},
                    new Object[]{100L}), """
                    {"columns":["v"],"method":"iqr"}
                    """);

            TableData data = plan.preview(10);
            assertEquals(5L, data.rows());
            assertTrue(values(data, 0).contains(null), String.valueOf(values(data, 0)));
        }
    }

    @Test
    void multipleColumnsAreCombinedWithAnd() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                rows.add(new Object[]{(double) i, (double) i});
            }
            rows.add(new Object[]{1000.0, 21.0});
            rows.add(new Object[]{21.0, 2000.0});

            LazyPlan plan = filter(db, List.of(ColumnSpec.of("a", "Float64"), ColumnSpec.of("b", "Float64")),
                    rows, """
                    {"columns":["a","b"],"method":"iqr"}
                    """);

            assertEquals(20L, plan.preview(30).rows());
        }
    }

    @Test
    void thresholdWidensTheIqrFences() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                rows.add(new Object[]{(double) i});
            }
            rows.add(new Object[]{1000.0});

            LazyPlan defaulted = filter(db, List.of(ColumnSpec.of("v", "Float64")), rows, """
                    {"columns":["v"],"method":"iqr"}
                    """);
            assertEquals(20L, defaulted.preview(30).rows());

            LazyPlan widened = filter(db, List.of(ColumnSpec.of("v", "Float64")), rows, """
                    {"columns":["v"],"method":"iqr","threshold":100}
                    """);
            assertEquals(21L, widened.preview(30).rows());
        }
    }

    @Test
    void invalidConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> numeric = List.of(ColumnSpec.of("v", "Float64"));
            List<Object[]> rows = List.<Object[]>of(new Object[]{1.0});

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> filter(db, numeric, rows, """
                            {"columns":["nope"],"method":"iqr"}
                            """));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException notNumeric = assertThrows(IllegalArgumentException.class,
                    () -> filter(db, List.of(ColumnSpec.of("s", "Utf8")), List.<Object[]>of(new Object[]{"x"}), """
                            {"columns":["s"],"method":"iqr"}
                            """));
            assertTrue(notNumeric.getMessage().contains("numeric"), notNumeric.getMessage());

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> filter(db, numeric, rows, """
                            {"columns":["v"],"method":"mad"}
                            """));
            assertTrue(method.getMessage().contains("iqr"), method.getMessage());

            IllegalArgumentException threshold = assertThrows(IllegalArgumentException.class,
                    () -> filter(db, numeric, rows, """
                            {"columns":["v"],"method":"iqr","threshold":0}
                            """));
            assertTrue(threshold.getMessage().contains("positive"), threshold.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> filter(db, numeric, rows, """
                            {"columns":[],"method":"iqr"}
                            """));
            assertTrue(empty.getMessage().contains("non-empty"), empty.getMessage());
        }
    }
}
