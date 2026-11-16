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

    // ------------------------------------------- dbscan / lof / isolation forest

    private static final List<ColumnSpec> CLUSTER_COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("x", "Float64"),
            ColumnSpec.of("y", "Float64"));

    /** Two tight clusters around (0, 0) and (5, 5) plus one far point at (20, 20). */
    private static List<Object[]> clusteredWithFarPoint() {
        List<Object[]> rows = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 10; i++) {
            rows.add(new Object[]{id++, 0.02 * i, -0.01 * i});
        }
        for (int i = 0; i < 10; i++) {
            rows.add(new Object[]{id++, 5.0 + 0.02 * i, 5.0 + 0.01 * i});
        }
        rows.add(new Object[]{id, 20.0, 20.0});
        return rows;
    }

    private static List<String> row(TableData data, String id) {
        return data.data().stream()
                .filter(row -> id.equals(row.get(0)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + id + " in " + data.data()));
    }

    private static double score(TableData data, String id) {
        String value = row(data, id).get(4);
        return value == null ? Double.NaN : Double.parseDouble(value);
    }

    @Test
    void dbscanFlagsTheFarPointAndZeroesCoreScores() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = detect(db, CLUSTER_COLUMNS, clusteredWithFarPoint(), """
                    {"columns":["x","y"],"method":"dbscan","eps":0.5,"min_points":3,"k":5}
                    """);

            assertEquals(List.of(
                    ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("outlier", "Boolean"),
                    ColumnSpec.of("score", "Float64")), plan.columns());
            TableData data = plan.preview(30);
            assertEquals(21L, data.rows());
            assertEquals("false", row(data, "1").get(3));
            assertEquals("false", row(data, "11").get(3));
            assertEquals("true", row(data, "21").get(3));
            assertEquals(0.0, score(data, "1"), 0.0);
            assertTrue(score(data, "21") > 1.0, "far score " + score(data, "21"));
        }
    }

    @Test
    void lofFlagsTheFarPointAndKeepsNormalRowsBelowOne() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = detect(db, CLUSTER_COLUMNS, clusteredWithFarPoint(), """
                    {"columns":["x","y"],"method":"lof","k":5,"contamination":0.05}
                    """).preview(30);

            assertEquals(21L, data.rows());
            assertEquals("false", row(data, "1").get(3));
            assertEquals("false", row(data, "11").get(3));
            assertEquals("true", row(data, "21").get(3));
            assertTrue(score(data, "21") > 1.5, "far LOF " + score(data, "21"));
            assertTrue(score(data, "1") < 1.5, "normal LOF " + score(data, "1"));
        }
    }

    @Test
    void isolationForestIsDeterministicAndFlagsTheFarPoint() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            String config = """
                    {"columns":["x","y"],"method":"isolation_forest","contamination":0.05,"seed":7}
                    """;
            TableData first = detect(db, CLUSTER_COLUMNS, clusteredWithFarPoint(), config).preview(30);
            TableData second = detect(db, CLUSTER_COLUMNS, clusteredWithFarPoint(), config).preview(30);

            assertEquals(21L, first.rows());
            assertEquals("true", row(first, "21").get(3));
            assertEquals("false", row(first, "1").get(3));
            assertEquals(first.data().stream().map(row -> row.get(4)).toList(),
                    second.data().stream().map(row -> row.get(4)).toList());
            for (List<String> entry : first.data()) {
                double parsed = Double.parseDouble(entry.get(4));
                assertTrue(parsed >= 0.0 && parsed <= 1.0, "score " + parsed);
            }
        }
    }

    @Test
    void distanceMethodsKeepNullRowsUnflaggedAndScoredNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{1L, 1.0, 1.0});
            rows.add(new Object[]{2L, 1.1, 1.1});
            rows.add(new Object[]{3L, 1.2, 1.2});
            rows.add(new Object[]{4L, null, 1.0});
            rows.add(new Object[]{5L, 50.0, 50.0});

            TableData data = detect(db, CLUSTER_COLUMNS, rows, """
                    {"columns":["x","y"],"method":"dbscan","eps":0.5,"min_points":2,"k":1}
                    """).preview(10);

            assertEquals("false", row(data, "4").get(3));
            assertEquals(null, row(data, "4").get(4));
        }
    }

    @Test
    void iqrAndZscoreStillEmitOnlyTheOutlierColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan iqr = detect(db, List.of(ColumnSpec.of("v", "Int64")), range(20), """
                    {"columns":["v"],"method":"iqr"}
                    """);
            assertEquals(List.of(ColumnSpec.of("v", "Int64"), ColumnSpec.of("outlier", "Boolean")),
                    iqr.columns());

            LazyPlan zscore = detect(db, List.of(ColumnSpec.of("v", "Float64")), range(20), """
                    {"columns":["v"],"method":"zscore"}
                    """);
            assertEquals(List.of(ColumnSpec.of("v", "Float64"), ColumnSpec.of("outlier", "Boolean")),
                    zscore.columns());
        }
    }

    @Test
    void newMethodsRejectBadSettingsAndExistingOutputColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = clusteredWithFarPoint();

            IllegalArgumentException eps = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, CLUSTER_COLUMNS, rows, """
                            {"columns":["x","y"],"method":"dbscan","eps":0}
                            """));
            assertTrue(eps.getMessage().contains("eps"), eps.getMessage());

            IllegalArgumentException minPoints = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, CLUSTER_COLUMNS, rows, """
                            {"columns":["x","y"],"method":"dbscan","min_points":0}
                            """));
            assertTrue(minPoints.getMessage().contains("min_points"), minPoints.getMessage());

            IllegalArgumentException neighbours = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, CLUSTER_COLUMNS, rows, """
                            {"columns":["x","y"],"method":"lof","k":0}
                            """));
            assertTrue(neighbours.getMessage().contains("k"), neighbours.getMessage());

            IllegalArgumentException contamination = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, CLUSTER_COLUMNS, rows, """
                            {"columns":["x","y"],"method":"isolation_forest","contamination":0.75}
                            """));
            assertTrue(contamination.getMessage().contains("contamination"), contamination.getMessage());

            IllegalArgumentException existing = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, List.of(ColumnSpec.of("x", "Float64"),
                                    ColumnSpec.of("y", "Float64"),
                                    ColumnSpec.of("score", "Float64")),
                            List.<Object[]>of(new Object[]{1.0, 2.0, 3.0}), """
                            {"columns":["x","y"],"method":"dbscan"}
                            """));
            assertTrue(existing.getMessage().contains("score"), existing.getMessage());
        }
    }

    @Test
    void rowCapFailsWithAHint() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> detect(db, CLUSTER_COLUMNS, clusteredWithFarPoint(), """
                            {"columns":["x","y"],"method":"lof","max_rows":5}
                            """));
            assertTrue(error.getMessage().contains("max_rows"), error.getMessage());
            assertTrue(error.getMessage().contains("pre-filter"), error.getMessage());
        }
    }
}
