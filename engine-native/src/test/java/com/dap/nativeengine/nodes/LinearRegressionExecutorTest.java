package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dap.linear_regression coefficients and predict modes. Reference values are R lm() output. */
class LinearRegressionExecutorTest {
    private static final List<ColumnSpec> COEFFICIENTS = List.of(
            ColumnSpec.of("term", "Utf8"),
            ColumnSpec.of("coefficient", "Float64"),
            ColumnSpec.of("std_error", "Float64"),
            ColumnSpec.of("t_statistic", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("r2", "Float64"),
            ColumnSpec.of("n", "Int64"));

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        NodeContext context = NodeTestSupport.context(db, configJson);
        LazyPlan plan = new LinearRegressionExecutor().execute(context, List.of(input));
        return plan.preview(100);
    }

    private static LazyPlan plan(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        NodeContext context = NodeTestSupport.context(db, configJson);
        return new LinearRegressionExecutor().execute(context, List.of(input));
    }

    private static List<String> termRow(TableData data, int termColumn, String term) {
        return data.data().stream()
                .filter(row -> Objects.equals(row.get(termColumn), term))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for term " + term + " in " + data.data()));
    }

    private static double number(List<String> row, int column) {
        return Double.parseDouble(row.get(column));
    }

    private static LazyPlan line(DuckDbSession db, double[] xs, double[] ys) throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("x", "Float64"),
                ColumnSpec.of("y", "Float64"));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            rows.add(new Object[]{xs[i], ys[i]});
        }
        return NodeTestSupport.table(db, columns, rows);
    }

    @Test
    void exactLineGivesExactCoefficientsAndR2() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = line(db, new double[]{1, 2, 3, 4, 5},
                    new double[]{3, 5, 7, 9, 11});

            LazyPlan plan = plan(db, input, """
                    {"x":["x"],"y":"y"}
                    """);
            assertEquals(COEFFICIENTS, plan.columns());
            TableData data = plan.preview(10);

            assertEquals(2, data.rows());
            List<String> intercept = termRow(data, 0, "intercept");
            assertEquals(1.0, number(intercept, 1), 1e-9);
            assertEquals(0.0, number(intercept, 2), 1e-9);
            assertNull(intercept.get(3));
            assertNull(intercept.get(4));
            assertEquals(1.0, number(intercept, 5), 1e-9);
            assertEquals("5", intercept.get(6));

            List<String> slope = termRow(data, 0, "x");
            assertEquals(2.0, number(slope, 1), 1e-9);
            assertEquals(0.0, number(slope, 2), 1e-9);
            assertNull(slope.get(3));
            assertNull(slope.get(4));
            assertEquals(1.0, number(slope, 5), 1e-9);
        }
    }

    @Test
    void coefficientsMatchR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = line(db, new double[]{1, 2, 3, 4, 5},
                    new double[]{2.1, 3.9, 6.2, 8.1, 9.8});

            TableData data = run(db, input, """
                    {"x":["x"],"y":"y"}
                    """);

            // R: lm(y ~ x) -> intercept 0.14, slope 1.96, R2 0.9976109
            List<String> intercept = termRow(data, 0, "intercept");
            assertEquals(0.13999999999999968, number(intercept, 1), 1e-6);
            assertEquals(0.18366636418607887, number(intercept, 2), 1e-6);
            assertEquals(0.762251709072657, number(intercept, 3), 1e-6);
            assertEquals(0.5013607988784112, number(intercept, 4), 1e-6);
            assertEquals(0.9976108860496521, number(intercept, 5), 1e-6);

            List<String> slope = termRow(data, 0, "x");
            assertEquals(1.9600000000000002, number(slope, 1), 1e-6);
            assertEquals(0.055377492419453854, number(slope, 2), 1e-6);
            assertEquals(35.39344080721614, number(slope, 3), 1e-6);
            assertEquals(4.95970357874409e-05, number(slope, 4), 1e-6);
            assertEquals("5", slope.get(6));
        }
    }

    @Test
    void groupedCoefficientsFitOneLinePerGroup() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"A", 1.0, 3.0},
                    new Object[]{"A", 2.0, 5.0},
                    new Object[]{"A", 3.0, 7.0},
                    new Object[]{"B", 1.0, 2.0},
                    new Object[]{"B", 2.0, 5.0},
                    new Object[]{"B", 3.0, 8.0}));

            LazyPlan plan = plan(db, input, """
                    {"x":["x"],"y":"y","group_by":["g"]}
                    """);
            assertEquals(List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("term", "Utf8"),
                    ColumnSpec.of("coefficient", "Float64"),
                    ColumnSpec.of("std_error", "Float64"),
                    ColumnSpec.of("t_statistic", "Float64"),
                    ColumnSpec.of("p_value", "Float64"),
                    ColumnSpec.of("r2", "Float64"),
                    ColumnSpec.of("n", "Int64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            List<String> aIntercept = data.data().stream()
                    .filter(row -> "A".equals(row.get(0)) && "intercept".equals(row.get(1)))
                    .findFirst().orElseThrow();
            assertEquals(1.0, number(aIntercept, 2), 1e-9);
            List<String> aSlope = data.data().stream()
                    .filter(row -> "A".equals(row.get(0)) && "x".equals(row.get(1)))
                    .findFirst().orElseThrow();
            assertEquals(2.0, number(aSlope, 2), 1e-9);
            List<String> bIntercept = data.data().stream()
                    .filter(row -> "B".equals(row.get(0)) && "intercept".equals(row.get(1)))
                    .findFirst().orElseThrow();
            assertEquals(-1.0, number(bIntercept, 2), 1e-9);
            List<String> bSlope = data.data().stream()
                    .filter(row -> "B".equals(row.get(0)) && "x".equals(row.get(1)))
                    .findFirst().orElseThrow();
            assertEquals(3.0, number(bSlope, 2), 1e-9);
        }
    }

    @Test
    void constantPredictorReturnsNullSlope() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = line(db, new double[]{5, 5, 5, 5},
                    new double[]{1, 2, 3, 4});

            TableData data = run(db, input, """
                    {"x":["x"],"y":"y"}
                    """);

            assertEquals(2, data.rows());
            for (String term : List.of("intercept", "x")) {
                List<String> row = termRow(data, 0, term);
                assertNull(row.get(1), term);
                assertNull(row.get(2), term);
                assertNull(row.get(3), term);
                assertNull(row.get(4), term);
                assertNull(row.get(5), term);
                assertEquals("4", row.get(6), term);
            }
        }
    }

    @Test
    void interceptFreeFitUsesSlopeOnlyModel() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = line(db, new double[]{1, 2, 3, 4, 5},
                    new double[]{2, 4, 6, 8, 10});

            TableData data = run(db, input, """
                    {"x":["x"],"y":"y","intercept":false}
                    """);

            assertEquals(1, data.rows());
            List<String> row = termRow(data, 0, "x");
            assertEquals(2.0, number(row, 1), 1e-9);
            assertEquals(0.0, number(row, 2), 1e-9);
            assertEquals(1.0, number(row, 5), 1e-9);
            assertEquals("5", row.get(6));
        }
    }

    @Test
    void predictModeAddsPredictionAndResidual() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = line(db, new double[]{1, 2, 3},
                    new double[]{3, 5, 7});

            LazyPlan plan = plan(db, input, """
                    {"x":["x"],"y":"y","mode":"predict"}
                    """);
            assertEquals(List.of(ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("prediction", "Float64"),
                    ColumnSpec.of("residual", "Float64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("1.0", "3.0", "3.0", "0.0"), data.data().get(0));
            assertEquals(List.of("2.0", "5.0", "5.0", "0.0"), data.data().get(1));
            assertEquals(List.of("3.0", "7.0", "7.0", "0.0"), data.data().get(2));
        }
    }

    @Test
    void predictModeFitsPerGroupAndHonoursTheOutputName() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"A", 1.0, 2.0},
                    new Object[]{"A", 2.0, 4.0},
                    new Object[]{"B", 1.0, 3.0},
                    new Object[]{"B", 2.0, 6.0}));

            LazyPlan plan = plan(db, input, """
                    {"x":["x"],"y":"y","group_by":["g"],"mode":"predict","output":"fitted"}
                    """);
            assertEquals(List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("fitted", "Float64"),
                    ColumnSpec.of("residual", "Float64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            for (List<String> row : data.data()) {
                assertEquals(number(row, 2), number(row, 3), 1e-9, row.toString());
                assertEquals(0.0, number(row, 4), 1e-9, row.toString());
            }
        }
    }

    @Test
    void predictModeLeavesNullsForMissingPredictorRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{1.0, 3.0},
                    new Object[]{2.0, 5.0},
                    new Object[]{null, 9.0}));

            TableData data = run(db, input, """
                    {"x":["x"],"y":"y","mode":"predict"}
                    """);

            assertEquals(3, data.rows());
            assertNull(data.data().get(2).get(2));
            assertNull(data.data().get(2).get(3));
        }
    }

    @Test
    void invalidConfigurationIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = line(db, new double[]{1, 2}, new double[]{3, 5});
            LazyPlan single = line(db, new double[]{1}, new double[]{3});

            IllegalArgumentException tooFewRows = assertThrows(IllegalArgumentException.class,
                    () -> run(db, single, """
                            {"x":["x"],"y":"y"}
                            """));
            assertTrue(tooFewRows.getMessage().contains("at least 2"), tooFewRows.getMessage());

            IllegalArgumentException twoPredictors = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x","y"],"y":"y"}
                            """));
            assertTrue(twoPredictors.getMessage().contains("one"), twoPredictors.getMessage());

            IllegalArgumentException emptyPredictors = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":[],"y":"y"}
                            """));
            assertTrue(emptyPredictors.getMessage().contains("'x'"), emptyPredictors.getMessage());

            IllegalArgumentException unknownY = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x"],"y":"nope"}
                            """));
            assertTrue(unknownY.getMessage().contains("nope"), unknownY.getMessage());

            IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x"],"y":"y","mode":"cluster"}
                            """));
            assertTrue(badMode.getMessage().contains("cluster"), badMode.getMessage());

            IllegalArgumentException unknownGroup = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x"],"y":"y","group_by":["nope"]}
                            """));
            assertTrue(unknownGroup.getMessage().contains("nope"), unknownGroup.getMessage());

            IllegalArgumentException clashingOutput = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x"],"y":"y","mode":"predict","output":"x"}
                            """));
            assertTrue(clashingOutput.getMessage().contains("already exists"),
                    clashingOutput.getMessage());
        }
    }

    @Test
    void nonNumericColumnsAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("s", "Utf8"),
                    ColumnSpec.of("y", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"a", 1.0},
                    new Object[]{"b", 2.0}));

            IllegalArgumentException textPredictor = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["s"],"y":"y"}
                            """));
            assertTrue(textPredictor.getMessage().contains("numeric"), textPredictor.getMessage());

            IllegalArgumentException textResponse = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["y"],"y":"s"}
                            """));
            assertTrue(textResponse.getMessage().contains("numeric"), textResponse.getMessage());
        }
    }
}
