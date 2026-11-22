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

/**
 * dap.logistic_regression. Reference values are an independent double-precision IRLS
 * implementation (Newton-Raphson, Wald p-values from the normal tail, McFadden pseudo R2
 * and the rank-based AUC).
 */
class LogisticRegressionExecutorTest {
    private static final List<ColumnSpec> COEFFICIENTS = List.of(
            ColumnSpec.of("term", "Utf8"),
            ColumnSpec.of("coefficient", "Float64"),
            ColumnSpec.of("std_error", "Float64"),
            ColumnSpec.of("z_statistic", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"),
            ColumnSpec.of("odds_ratio", "Float64"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("pseudo_r2", "Float64"),
            ColumnSpec.of("auc", "Float64"),
            ColumnSpec.of("iterations", "Int64"),
            ColumnSpec.of("converged", "Boolean"));

    private static final double[] X1 = {-0.797, 0.644, 0.79, -1.231, 1.515, -1.4, 1.483, -0.654,
            -0.54, 0.179, 1.702, 1.582, 1.235, 1.078, -0.221, 0.966, -1.548, 1.207, -0.948, 1.357,
            0.753, 0.158, -1.508, 0.428, 0.665, 0.864, -1.777, -1.246, 1.047, 1.81, -0.407, 0.235,
            0.418, -0.256, -1.62, -1.91, -1.888, 0.585, 0.739, 0.379};
    private static final double[] X2 = {-0.097, 0.42, 1.118, 1.795, -1.041, 0.175, 1.301, 1.407,
            -0.534, 0.443, -0.803, -0.586, -1.652, -1.862, 1.854, 1.435, -0.762, -0.599, -0.751,
            1.743, -0.741, -1.343, 1.304, -0.835, 1.715, -0.454, -0.221, 1.436, 1.536, -0.882,
            0.86, 0.191, 0.417, 1.24, -0.388, -1.053, 1.895, 0.148, -1.996, -0.37};
    private static final double[] Y = {0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 1, 0, 1, 0, 0,
            1, 1, 0, 1, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0};

    private static LazyPlan sample(DuckDbSession db) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < Y.length; i++) {
            rows.add(new Object[]{X1[i], X2[i], Y[i]});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("x1", "Float64"),
                ColumnSpec.of("x2", "Float64"), ColumnSpec.of("y", "Float64")), rows);
    }

    private static LazyPlan plan(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        NodeContext context = NodeTestSupport.context(db, configJson);
        return new LogisticRegressionExecutor().execute(context, List.of(input));
    }

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return plan(db, input, configJson).preview(100);
    }

    private static List<String> termRow(TableData data, String term) {
        return data.data().stream()
                .filter(row -> Objects.equals(row.get(0), term))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for term " + term + " in " + data.data()));
    }

    private static double number(List<String> row, int column) {
        return Double.parseDouble(row.get(column));
    }

    @Test
    void coefficientsMatchTheReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, sample(db), """
                    {"x":["x1","x2"],"y":"y"}
                    """);
            assertEquals(COEFFICIENTS, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());

            List<String> intercept = termRow(data, "intercept");
            assertEquals(-1.1469445277449382, number(intercept, 1), 1e-9);
            assertEquals(0.54587632972155498, number(intercept, 2), 1e-9);
            assertEquals(-2.1011069088303955, number(intercept, 3), 1e-9);
            assertEquals(0.035631582566907793, number(intercept, 4), 1e-9);
            assertEquals(-2.2168424740120973, number(intercept, 5), 1e-9);
            assertEquals(-0.077046581477778986, number(intercept, 6), 1e-9);
            assertEquals(0.31760572379565227, number(intercept, 7), 1e-9);
            assertEquals("40", intercept.get(8));
            assertEquals(0.38590274867475649, number(intercept, 9), 1e-9);
            assertEquals(0.8928571428571429, number(intercept, 10), 1e-9);
            assertEquals("7", intercept.get(11));
            assertEquals("true", intercept.get(12));

            List<String> x1 = termRow(data, "x1");
            assertEquals(1.4903859931336136, number(x1, 1), 1e-9);
            assertEquals(0.57578682296314088, number(x1, 2), 1e-9);
            assertEquals(2.588433659290291, number(x1, 3), 1e-9);
            assertEquals(0.0096413516450284398, number(x1, 4), 1e-9);
            assertEquals(0.3618645573531174, number(x1, 5), 1e-9);
            assertEquals(2.6189074289141097, number(x1, 6), 1e-9);
            assertEquals(4.4388085379927027, number(x1, 7), 1e-9);

            List<String> x2 = termRow(data, "x2");
            assertEquals(-0.92499830065503186, number(x2, 1), 1e-9);
            assertEquals(0.42996425587600295, number(x2, 2), 1e-9);
            assertEquals(-2.1513376705476452, number(x2, 3), 1e-9);
            assertEquals(0.031449556970450265, number(x2, 4), 1e-9);
            assertEquals(0.39653209291923713, number(x2, 7), 1e-9);
        }
    }

    @Test
    void nonFinitePredictorRowsAreExcludedInsteadOfPoisoningCoefficients() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            String config = "{\"x\":[\"x1\"],\"y\":\"y\"}";
            TableData clean = run(db, sample(db), config);

            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < X1.length; i++) {
                rows.add(new Object[]{X1[i], Y[i]});
            }
            rows.add(new Object[]{Double.POSITIVE_INFINITY, 0});
            LazyPlan dirty = NodeTestSupport.table(db, List.of(ColumnSpec.of("x1", "Float64"),
                    ColumnSpec.of("y", "Float64")), rows);
            TableData filtered = run(db, dirty, config);

            assertEquals("40", termRow(filtered, "intercept").get(8),
                    "the Infinity row must be dropped before the fit");
            assertEquals(termRow(clean, "intercept").get(1), termRow(filtered, "intercept").get(1));
            for (List<String> row : filtered.data()) {
                for (int column = 1; column <= 10; column++) {
                    assertTrue(Double.isFinite(number(row, column)), "non-finite coefficient: " + row);
                }
            }
        }
    }

    @Test
    void robustHc1UsesTheSandwichEstimator() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, sample(db), """
                    {"x":["x1","x2"],"y":"y","robust":"hc1"}
                    """);

            List<String> intercept = termRow(data, "intercept");
            assertEquals(-1.1469445277449382, number(intercept, 1), 1e-9);
            assertEquals(0.43692079332501921, number(intercept, 2), 1e-9);
            assertEquals(-2.6250628151994184, number(intercept, 3), 1e-9);
            assertEquals(0.0086632983138019704, number(intercept, 4), 1e-9);
            assertEquals(-2.0032935467586444, number(intercept, 5), 1e-9);
            assertEquals(-0.29059550873123208, number(intercept, 6), 1e-9);

            List<String> x2 = termRow(data, "x2");
            assertEquals(0.48626566801662457, number(x2, 2), 1e-9);
            assertEquals(-1.90224883534942, number(x2, 3), 1e-9);
            assertEquals(0.057138631144340923, number(x2, 4), 1e-9);
        }
    }

    @Test
    void predictModeAddsProbabilityAndPrediction() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, sample(db), """
                    {"x":["x1","x2"],"y":"y","mode":"predict"}
                    """);
            assertEquals(List.of(ColumnSpec.of("x1", "Float64"),
                    ColumnSpec.of("x2", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("probability", "Float64"),
                    ColumnSpec.of("prediction", "Int64")), plan.columns());

            TableData data = plan.preview(100);
            assertEquals(40, data.rows());
            assertEquals(0.09577666308, Double.parseDouble(data.data().get(0).get(3)), 1e-9);
            assertEquals(0.359937543341, Double.parseDouble(data.data().get(1).get(3)), 1e-9);
            for (List<String> row : data.data()) {
                double probability = Double.parseDouble(row.get(3));
                assertTrue(probability >= 0.0 && probability <= 1.0, row.toString());
                int prediction = Integer.parseInt(row.get(4));
                assertEquals(probability >= 0.5 ? 1 : 0, prediction, row.toString());
            }
        }
    }

    @Test
    void predictModeLeavesNullsForMissingPredictorRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < Y.length; i++) {
                rows.add(new Object[]{X1[i], X2[i], Y[i]});
            }
            rows.add(new Object[]{null, 0.5, 1});
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("x1", "Float64"),
                    ColumnSpec.of("x2", "Float64"), ColumnSpec.of("y", "Float64")), rows);

            TableData data = run(db, input, """
                    {"x":["x1","x2"],"y":"y","mode":"predict"}
                    """);

            assertEquals(41, data.rows());
            List<String> last = data.data().get(40);
            assertNull(last.get(3));
            assertNull(last.get(4));
        }
    }

    @Test
    void booleanResponseIsAccepted() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < Y.length; i++) {
                rows.add(new Object[]{X1[i], Y[i] == 1.0});
            }
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("x1", "Float64"),
                    ColumnSpec.of("y", "Boolean")), rows);

            TableData data = run(db, input, """
                    {"x":["x1"],"y":"y"}
                    """);
            assertEquals(2, data.rows());
            assertEquals("true", data.data().get(0).get(12));
        }
    }

    @Test
    void l2RegularisationHandlesPerfectSeparation() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (double x : new double[]{-2.0, -1.5, -1.0, -0.5, 0.5, 1.0, 1.5, 2.0, -1.2, 1.2,
                    0.8, -0.8}) {
                rows.add(new Object[]{x, x > 0 ? 1.0 : 0.0});
            }
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64")), rows);

            TableData penalised = run(db, input, """
                    {"x":["x"],"y":"y","l2":0.5,"max_iterations":200,"tolerance":1e-8}
                    """);
            List<String> slope = termRow(penalised, "x");
            assertEquals("true", slope.get(12));
            assertTrue(number(slope, 1) > 0.0, slope.toString());
            assertTrue(number(slope, 1) < 50.0, slope.toString());
            assertEquals(1.0, number(slope, 10), 1e-9);

            TableData unpenalised = run(db, input, """
                    {"x":["x"],"y":"y","max_iterations":25}
                    """);
            List<String> unpenalisedSlope = termRow(unpenalised, "x");
            assertEquals("false", unpenalisedSlope.get(12));
        }
    }

    @Test
    void nonBinaryResponseIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{1.0, 0.0},
                    new Object[]{2.0, 1.0},
                    new Object[]{3.0, 2.0}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x"],"y":"y"}
                            """));
            assertTrue(failure.getMessage().contains("binary"), failure.getMessage());
        }
    }

    @Test
    void singleClassResponseIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{1.0, 1.0},
                    new Object[]{2.0, 1.0},
                    new Object[]{3.0, 1.0}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x"],"y":"y"}
                            """));
            assertTrue(failure.getMessage().contains("both"), failure.getMessage());
        }
    }

    @Test
    void tooFewRowsForParametersIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(
                            ColumnSpec.of("x1", "Float64"), ColumnSpec.of("x2", "Float64"),
                            ColumnSpec.of("y", "Float64")),
                    List.of(
                            new Object[]{1.0, 2.0, 0.0},
                            new Object[]{2.0, 4.0, 1.0},
                            new Object[]{3.0, 6.0, 0.0}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1","x2"],"y":"y"}
                            """));
            assertTrue(failure.getMessage().contains("more rows than parameters"),
                    failure.getMessage());
        }
    }

    @Test
    void invalidConfigurationIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = sample(db);

            IllegalArgumentException unknownPredictor = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["nope"],"y":"y"}
                            """));
            assertTrue(unknownPredictor.getMessage().contains("nope"),
                    unknownPredictor.getMessage());

            IllegalArgumentException duplicatePredictor = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1","x1"],"y":"y"}
                            """));
            assertTrue(duplicatePredictor.getMessage().contains("duplicate"),
                    duplicatePredictor.getMessage());

            IllegalArgumentException unknownResponse = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1"],"y":"nope"}
                            """));
            assertTrue(unknownResponse.getMessage().contains("nope"), unknownResponse.getMessage());

            IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1"],"y":"y","mode":"cluster"}
                            """));
            assertTrue(badMode.getMessage().contains("cluster"), badMode.getMessage());

            IllegalArgumentException badRobust = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1"],"y":"y","robust":"hc3"}
                            """));
            assertTrue(badRobust.getMessage().contains("hc3"), badRobust.getMessage());

            IllegalArgumentException badIterations = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1"],"y":"y","max_iterations":0}
                            """));
            assertTrue(badIterations.getMessage().contains("max_iterations"),
                    badIterations.getMessage());

            IllegalArgumentException badTolerance = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1"],"y":"y","tolerance":0}
                            """));
            assertTrue(badTolerance.getMessage().contains("tolerance"), badTolerance.getMessage());

            IllegalArgumentException badL2 = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1"],"y":"y","l2":-1}
                            """));
            assertTrue(badL2.getMessage().contains("l2"), badL2.getMessage());

            IllegalArgumentException clashingOutput = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1"],"y":"y","mode":"predict","output":"x1"}
                            """));
            assertTrue(clashingOutput.getMessage().contains("already exists"),
                    clashingOutput.getMessage());
        }
    }

    @Test
    void nonNumericPredictorsAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(
                            ColumnSpec.of("s", "Utf8"), ColumnSpec.of("y", "Float64")),
                    List.of(new Object[]{"a", 0.0}, new Object[]{"b", 1.0},
                            new Object[]{"c", 0.0}, new Object[]{"d", 1.0}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["s"],"y":"y"}
                            """));
            assertTrue(failure.getMessage().contains("numeric"), failure.getMessage());
        }
    }
}
