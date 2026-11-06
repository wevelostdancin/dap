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

class OutlierDetectExecutorTest {
    private static LazyPlan detect(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows, String config)
            throws Exception {
        return new OutlierDetectExecutor().execute(NodeTestSupport.context(db, config),
                List.of(NodeTestSupport.table(db, columns, rows)));
    }

    private static List<Object[]> range(int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(new Object[]{(long) i});
        }
        return rows;
    }

    private static String flag(TableData data, String value) {
        return data.data().stream()
                .filter(row -> value.equals(row.get(0)))
                .map(row -> row.get(1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + value + " in " + data.data()));
    }

    @Test
    void iqrFlagsOnlyTheExtremeValue() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = range(20);
            rows.add(new Object[]{1000L});

            LazyPlan plan = detect(db, List.of(ColumnSpec.of("v", "Int64")), rows, """
                    {"columns":["v"],"method":"iqr"}
                    """);

            assertEquals(List.of(ColumnSpec.of("v", "Int64"), ColumnSpec.of("outlier", "Boolean")),
                    plan.columns());
            TableData data = plan.preview(30);
            assertEquals(21L, data.rows());
            List<String> flagged = data.data().stream()
                    .filter(row -> "true".equals(row.get(1)))
                    .map(row -> row.get(0))
                    .toList();
            assertEquals(List.of("1000"), flagged);
            assertEquals("false", flag(data, "1"));
        }
    }

    @Test
    void zscoreThresholdFlagsBothSidesAndIgnoresNulls() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = detect(db, List.of(ColumnSpec.of("v", "Float64")), List.of(
                    new Object[]{1.0},
                    new Object[]{2.0},
                    new Object[]{3.0},
                    new Object[]{null}), """
                    {"columns":["v"],"method":"zscore","threshold":0.5}
                    """);

            TableData data = plan.preview(10);
            assertEquals(4L, data.rows());
            assertEquals("true", flag(data, "1.0"));
            assertEquals("false", flag(data, "2.0"));
            assertEquals("true", flag(data, "3.0"));
            List<String> nullRow = data.data().stream()
                    .filter(row -> row.get(0) == null)
                    .findFirst()
                    .orElseThrow();
            assertEquals("false", nullRow.get(1));
        }
    }

    @Test
    void defaultThresholdsKeepOrdinaryRowsQuiet() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData iqr = detect(db, List.of(ColumnSpec.of("v", "Int64")), range(20), """
                    {"columns":["v"],"method":"iqr"}
                    """).preview(30);
            assertTrue(iqr.data().stream().allMatch(row -> "false".equals(row.get(1))), iqr.data().toString());

            TableData zscore = detect(db, List.of(ColumnSpec.of("v", "Float64")), List.of(
                    new Object[]{1.0},
                    new Object[]{2.0},
                    new Object[]{3.0}), """
                    {"columns":["v"],"method":"zscore"}
                    """).preview(10);
            assertTrue(zscore.data().stream().allMatch(row -> "false".equals(row.get(1))),
                    zscore.data().toString());

            List<Object[]> rows = range(20);
            rows.add(new Object[]{1000L});
            TableData widened = detect(db, List.of(ColumnSpec.of("v", "Int64")), rows, """
                    {"columns":["v"],"method":"iqr","threshold":100}
                    """).preview(30);
            assertTrue(widened.data().stream().allMatch(row -> "false".equals(row.get(1))),
                    widened.data().toString());
        }
    }

    @Test
    void anyOutlierColumnFlagsTheWholeRow() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                rows.add(new Object[]{(long) i, (long) i});
            }
            rows.add(new Object[]{1000L, 21L});
            rows.add(new Object[]{21L, 1000L});

            TableData data = detect(db, List.of(ColumnSpec.of("a", "Int64"), ColumnSpec.of("b", "Int64")),
                    rows, """
                    {"columns":["a","b"],"method":"iqr"}
                    """).preview(30);

            assertEquals(22L, data.rows());
            List<String> flagged = data.data().stream()
                    .filter(row -> "true".equals(row.get(2)))
                    .map(row -> row.get(0) + "/" + row.get(1))
                    .toList();
            assertEquals(List.of("1000/21", "21/1000"), flagged);
        }
    }

    @Test
    void constantAndSingleRowInputsHaveNoOutliers() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData constant = detect(db, List.of(ColumnSpec.of("v", "Float64")), List.of(
                    new Object[]{5.0},
                    new Object[]{5.0},
                    new Object[]{5.0}), """
                    {"columns":["v"],"method":"iqr"}
                    """).preview(10);
            assertEquals(3L, constant.rows());
            assertTrue(constant.data().stream().allMatch(row -> "false".equals(row.get(1))),
                    constant.data().toString());

            TableData single = detect(db, List.of(ColumnSpec.of("v", "Float64")), List.<Object[]>of(
                    new Object[]{42.0}), """
                    {"columns":["v"],"method":"zscore"}
                    """).preview(10);
            assertEquals("false", single.data().get(0).get(1));
        }
    }

    @Test
    void invalidConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = List.<Object[]>of(new Object[]{1.0});

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, List.of(ColumnSpec.of("v", "Float64")), rows, """
                            {"columns":["nope"],"method":"iqr"}
                            """));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException notNumeric = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, List.of(ColumnSpec.of("s", "Utf8")), List.<Object[]>of(new Object[]{"x"}), """
                            {"columns":["s"],"method":"iqr"}
                            """));
            assertTrue(notNumeric.getMessage().contains("numeric"), notNumeric.getMessage());

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, List.of(ColumnSpec.of("v", "Float64")), rows, """
                            {"columns":["v"],"method":"mad"}
                            """));
            assertTrue(method.getMessage().contains("iqr"), method.getMessage());

            IllegalArgumentException threshold = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, List.of(ColumnSpec.of("v", "Float64")), rows, """
                            {"columns":["v"],"method":"zscore","threshold":0}
                            """));
            assertTrue(threshold.getMessage().contains("positive"), threshold.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, List.of(ColumnSpec.of("v", "Float64")), rows, """
                            {"columns":[],"method":"iqr"}
                            """));
            assertTrue(empty.getMessage().contains("non-empty"), empty.getMessage());

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, List.of(ColumnSpec.of("v", "Float64")), rows, """
                            {"method":"iqr"}
                            """));
            assertTrue(missing.getMessage().contains("columns"), missing.getMessage());

            IllegalArgumentException existing = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, List.of(ColumnSpec.of("v", "Float64"), ColumnSpec.of("outlier", "Utf8")),
                            List.<Object[]>of(new Object[]{1.0, "no"}), """
                            {"columns":["v"],"method":"iqr"}
                            """));
            assertTrue(existing.getMessage().contains("outlier"), existing.getMessage());
        }
    }
}
