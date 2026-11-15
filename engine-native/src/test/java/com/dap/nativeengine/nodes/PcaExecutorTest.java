package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcaExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("x", "Float64"),
            ColumnSpec.of("y", "Float64"));

    private static LazyPlan pca(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows,
                                String config) throws Exception {
        return new PcaExecutor().execute(NodeTestSupport.context(db, config),
                List.of(NodeTestSupport.table(db, columns, rows)));
    }

    /** Strongly correlated x and y (y = x + small deterministic wobble). */
    private static List<Object[]> correlated(int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double x = index * 0.1;
            rows.add(new Object[]{(long) index, x, x + 0.05 * Math.sin(index)});
        }
        return rows;
    }

    private static Map<String, Double> loadings(TableData data) {
        Map<String, Double> loadings = new HashMap<>();
        for (List<String> row : data.data()) {
            loadings.put(row.get(0) + "/" + row.get(1), Double.parseDouble(row.get(2)));
        }
        return loadings;
    }

    @Test
    void correlatedDataPutsMostVarianceInTheFirstComponent() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = pca(db, COLUMNS, correlated(100), """
                    {"columns":["x","y"],"components":2,"standardize":true,"mode":"explained"}
                    """);

            assertEquals(List.of(
                    ColumnSpec.of("component", "Int64"),
                    ColumnSpec.of("eigenvalue", "Float64"),
                    ColumnSpec.of("explained_variance", "Float64"),
                    ColumnSpec.of("cumulative", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2L, data.rows());
            double first = Double.parseDouble(data.data().get(0).get(2));
            assertTrue(first > 0.8, "first explained variance " + first);
            double cumulative = Double.parseDouble(data.data().get(1).get(3));
            assertEquals(1.0, cumulative, 1e-9);
            assertEquals(1.0, Double.parseDouble(data.data().get(0).get(2))
                    + Double.parseDouble(data.data().get(1).get(2)), 1e-9);
        }
    }

    @Test
    void loadingsAreOrthonormal() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = pca(db, COLUMNS, correlated(100), """
                    {"columns":["x","y"],"components":2,"standardize":true,"mode":"loadings"}
                    """);
            assertEquals(List.of(
                    ColumnSpec.of("component", "Int64"),
                    ColumnSpec.of("column", "Utf8"),
                    ColumnSpec.of("loading", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(4L, data.rows());
            Map<String, Double> loadings = loadings(data);
            double firstX = loadings.get("1/x");
            double firstY = loadings.get("1/y");
            double secondX = loadings.get("2/x");
            double secondY = loadings.get("2/y");
            assertEquals(1.0, firstX * firstX + firstY * firstY, 1e-9);
            assertEquals(1.0, secondX * secondX + secondY * secondY, 1e-9);
            assertEquals(0.0, firstX * secondX + firstY * secondY, 1e-9);
            // Sign convention: the largest absolute loading of every component is positive.
            assertTrue(Math.abs(firstX) >= Math.abs(firstY) ? firstX > 0 : firstY > 0, "first component sign");
        }
    }

    @Test
    void scoresAddComponentsAndFollowTheSignConvention() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = correlated(100);
            Map<String, Double> loadings = loadings(pca(db, COLUMNS, rows, """
                    {"columns":["x","y"],"components":2,"standardize":true,"mode":"loadings"}
                    """).preview(10));

            LazyPlan plan = pca(db, COLUMNS, rows, """
                    {"columns":["x","y"],"components":2,"standardize":true,"mode":"scores"}
                    """);
            assertEquals(List.of(
                    ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("pc1", "Float64"),
                    ColumnSpec.of("pc2", "Float64")), plan.columns());
            TableData data = plan.preview(200);
            assertEquals(100L, data.rows());

            double meanX = rows.stream().mapToDouble(row -> (double) row[1]).average().orElseThrow();
            double meanY = rows.stream().mapToDouble(row -> (double) row[2]).average().orElseThrow();
            double sdX = Math.sqrt(rows.stream().mapToDouble(row -> Math.pow((double) row[1] - meanX, 2))
                    .sum() / (rows.size() - 1));
            double sdY = Math.sqrt(rows.stream().mapToDouble(row -> Math.pow((double) row[2] - meanY, 2))
                    .sum() / (rows.size() - 1));
            List<String> last = data.data().get(99);
            double zx = ((double) rows.get(99)[1] - meanX) / sdX;
            double zy = ((double) rows.get(99)[2] - meanY) / sdY;
            double expected = loadings.get("1/x") * zx + loadings.get("1/y") * zy;
            assertEquals(expected, Double.parseDouble(last.get(3)), 1e-9);
            assertTrue(Double.parseDouble(last.get(3)) > 0.0, "pc1 of the largest row must be positive");
        }
    }

    @Test
    void nullFeaturesGiveNullScores() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = correlated(20);
            rows.add(new Object[]{20L, null, 1.0});

            TableData data = pca(db, COLUMNS, rows, """
                    {"columns":["x","y"],"components":1,"standardize":true,"mode":"scores"}
                    """).preview(40);

            List<String> nullRow = data.data().stream()
                    .filter(row -> row.get(0).equals("20"))
                    .findFirst()
                    .orElseThrow();
            assertNull(nullRow.get(3));
        }
    }

    @Test
    void invalidConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = correlated(10);

            IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class,
                    () -> pca(db, COLUMNS, rows, """
                            {"columns":["x","y"],"components":3}
                            """));
            assertTrue(tooMany.getMessage().contains("components=3"), tooMany.getMessage());

            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                    () -> pca(db, COLUMNS, rows, """
                            {"columns":["x","y"],"components":0}
                            """));
            assertTrue(zero.getMessage().contains("components"), zero.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> pca(db, COLUMNS, rows, """
                            {"columns":["nope"],"components":1}
                            """));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException notNumeric = assertThrows(IllegalArgumentException.class,
                    () -> pca(db, List.of(ColumnSpec.of("s", "Utf8")),
                            List.<Object[]>of(new Object[]{"x"}), """
                            {"columns":["s"],"components":1}
                            """));
            assertTrue(notNumeric.getMessage().contains("numeric"), notNumeric.getMessage());

            IllegalArgumentException mode = assertThrows(IllegalArgumentException.class,
                    () -> pca(db, COLUMNS, rows, """
                            {"columns":["x","y"],"components":1,"mode":"rotation"}
                            """));
            assertTrue(mode.getMessage().contains("scores"), mode.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> pca(db, List.of(ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64"),
                                    ColumnSpec.of("pc1", "Float64")),
                            List.<Object[]>of(new Object[]{1.0, 2.0, 0.0}), """
                            {"columns":["x","y"],"components":1,"mode":"scores"}
                            """));
            assertTrue(duplicate.getMessage().contains("pc1"), duplicate.getMessage());
        }
    }

    @Test
    void rowCapFailsWithAHint() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> pca(db, COLUMNS, correlated(10), """
                            {"columns":["x","y"],"components":1,"max_rows":5}
                            """));
            assertTrue(error.getMessage().contains("max_rows"), error.getMessage());
            assertTrue(error.getMessage().contains("pre-filter"), error.getMessage());
        }
    }

    @Test
    void tooFewCompleteRowsIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> pca(db, COLUMNS, List.<Object[]>of(new Object[]{1L, 1.0, 2.0}), """
                            {"columns":["x","y"],"components":1}
                            """));
            assertTrue(error.getMessage().contains("2"), error.getMessage());
        }
    }
}
