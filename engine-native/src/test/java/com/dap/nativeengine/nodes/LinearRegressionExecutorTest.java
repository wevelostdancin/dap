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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dap.linear_regression coefficients, diagnostics and predict modes. Reference values
 * are R lm()/summary() output and independent double-precision OLS computations.
 */
class LinearRegressionExecutorTest {
    private static final List<ColumnSpec> COEFFICIENTS = List.of(
            ColumnSpec.of("term", "Utf8"),
            ColumnSpec.of("coefficient", "Float64"),
            ColumnSpec.of("std_error", "Float64"),
            ColumnSpec.of("t_statistic", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("r2", "Float64"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("adj_r2", "Float64"),
            ColumnSpec.of("f_statistic", "Float64"),
            ColumnSpec.of("f_p_value", "Float64"),
            ColumnSpec.of("rmse", "Float64"),
            ColumnSpec.of("df_residual", "Float64"),
            ColumnSpec.of("vif", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"));

    private static final List<ColumnSpec> DIAGNOSTICS = List.of(
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("df_residual", "Float64"),
            ColumnSpec.of("r2", "Float64"),
            ColumnSpec.of("adj_r2", "Float64"),
            ColumnSpec.of("f_statistic", "Float64"),
            ColumnSpec.of("f_p_value", "Float64"),
            ColumnSpec.of("rmse", "Float64"),
            ColumnSpec.of("durbin_watson", "Float64"),
            ColumnSpec.of("aic", "Float64"),
            ColumnSpec.of("bic", "Float64"));

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

    /** x1, x2 and y with a strong x1/x2 correlation, so VIF is well above 1. */
    private static LazyPlan multiple(DuckDbSession db) throws Exception {
        double[][] rows = {
                {1, 1, 2.1}, {2, 1, 4.2}, {3, 2, 5.9}, {4, 3, 8.2}, {5, 3, 10.1},
                {6, 4, 11.9}, {7, 5, 14.2}, {8, 5, 16.1}, {9, 6, 17.9}, {10, 7, 20.2}};
        List<Object[]> data = new ArrayList<>();
        for (double[] row : rows) {
            data.add(new Object[]{row[0], row[1], row[2]});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("x1", "Float64"),
                ColumnSpec.of("x2", "Float64"), ColumnSpec.of("y", "Float64")), data);
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
            assertEquals(1.0, number(intercept, 7), 1e-9);
            assertNull(intercept.get(8));
            assertNull(intercept.get(9));
            assertEquals(0.0, number(intercept, 10), 1e-9);
            assertEquals(3.0, number(intercept, 11), 1e-9);
            assertNull(intercept.get(12));
            assertEquals(1.0, number(intercept, 13), 1e-9);
            assertEquals(1.0, number(intercept, 14), 1e-9);

            List<String> slope = termRow(data, 0, "x");
            assertEquals(2.0, number(slope, 1), 1e-9);
            assertEquals(0.0, number(slope, 2), 1e-9);
            assertNull(slope.get(3));
            assertNull(slope.get(4));
            assertEquals(1.0, number(slope, 5), 1e-9);
            assertEquals(1.0, number(slope, 12), 1e-9);
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

            // R: lm(y ~ x) -> intercept 0.14, slope 1.96, R2 0.9976109.
            List<String> intercept = termRow(data, 0, "intercept");
            assertEquals(0.13999999999999968, number(intercept, 1), 1e-6);
            assertEquals(0.18366636418607887, number(intercept, 2), 1e-6);
            assertEquals(0.762251709072657, number(intercept, 3), 1e-6);
            assertEquals(0.5013607988784112, number(intercept, 4), 1e-6);
            assertEquals(0.9976108860496521, number(intercept, 5), 1e-6);
            assertEquals("5", intercept.get(6));
            assertEquals(0.99681451473286931, number(intercept, 7), 1e-6);
            assertEquals(1252.6956521739157, number(intercept, 8), 1e-6);
            assertEquals(4.9597035775117426e-05, number(intercept, 9), 1e-9);
            assertEquals(0.17511900715418244, number(intercept, 10), 1e-9);
            assertEquals(3.0, number(intercept, 11), 1e-9);
            assertNull(intercept.get(12));
            assertEquals(-0.44450834210898094, number(intercept, 13), 1e-6);
            assertEquals(0.7245083421089803, number(intercept, 14), 1e-6);

            List<String> slope = termRow(data, 0, "x");
            assertEquals(1.9600000000000002, number(slope, 1), 1e-6);
            assertEquals(0.055377492419453854, number(slope, 2), 1e-6);
            assertEquals(35.39344080721614, number(slope, 3), 1e-6);
            assertEquals(4.95970357874409e-05, number(slope, 4), 1e-6);
            assertEquals(1.0, number(slope, 12), 1e-9);
            assertEquals(1.783764103853802, number(slope, 13), 1e-6);
            assertEquals(2.1362358961461982, number(slope, 14), 1e-6);
        }
    }

    @Test
    void multiplePredictorsMatchTheReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, multiple(db), """
                    {"x":["x1","x2"],"y":"y"}
                    """);

            assertEquals(3, data.rows());
            List<String> intercept = termRow(data, 0, "intercept");
            assertEquals(0.09333333333331618, number(intercept, 1), 1e-9);
            assertEquals(0.09941096359325607, number(intercept, 2), 1e-9);
            assertEquals(0.9388635816386736, number(intercept, 3), 1e-9);
            assertEquals(0.3790578632665329, number(intercept, 4), 1e-9);
            assertEquals(0.99954991530368931, number(intercept, 5), 1e-9);
            assertEquals(0.99942131967617198, number(intercept, 7), 1e-9);
            assertEquals(7772.8141663989718, number(intercept, 8), 1e-6);
            assertEquals(1.9343415758044102e-12, number(intercept, 9), 1e-12);
            assertEquals(0.14552274993381387, number(intercept, 10), 1e-9);
            assertEquals(7.0, number(intercept, 11), 1e-9);
            assertNull(intercept.get(12));
            assertEquals(-0.14173624205350444, number(intercept, 13), 1e-9);
            assertEquals(0.32840290872013683, number(intercept, 14), 1e-9);

            List<String> x1 = termRow(data, 0, "x1");
            assertEquals(1.9319047619048213, number(x1, 1), 1e-9);
            assertEquals(0.11316786067889549, number(x1, 2), 1e-9);
            assertEquals(17.071143258477267, number(x1, 3), 1e-9);
            assertEquals(5.805753062748664e-07, number(x1, 4), 1e-12);
            assertEquals(49.892857142857032, number(x1, 12), 1e-9);
            assertEquals(1.6643052940426317, number(x1, 13), 1e-9);
            assertEquals(2.199504229767011, number(x1, 14), 1e-9);

            List<String> x2 = termRow(data, 0, "x2");
            assertEquals(0.09761904761887763, number(x2, 1), 1e-9);
            assertEquals(0.16652812512037019, number(x2, 2), 1e-9);
            assertEquals(0.5862015653410884, number(x2, 3), 1e-9);
            assertEquals(0.5761459005586544, number(x2, 4), 1e-9);
            assertEquals(49.892857142857032, number(x2, 12), 1e-9);
        }
    }

    @Test
    void robustHc1UsesHeteroskedasticityConsistentStandardErrors() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, multiple(db), """
                    {"x":["x1","x2"],"y":"y","robust":"hc1"}
                    """);

            List<String> intercept = termRow(data, 0, "intercept");
            assertEquals(0.09333333333331618, number(intercept, 1), 1e-9);
            assertEquals(0.09465940589404657, number(intercept, 2), 1e-9);
            assertEquals(0.9859911168023315, number(intercept, 3), 1e-9);
            assertEquals(0.3569839087879898, number(intercept, 4), 1e-9);
            assertEquals(-0.13050059348511128, number(intercept, 13), 1e-9);
            assertEquals(0.3171672601517436, number(intercept, 14), 1e-9);

            List<String> x1 = termRow(data, 0, "x1");
            assertEquals(0.07683365348247694, number(x1, 2), 1e-9);
            assertEquals(25.14399191423874, number(x1, 3), 1e-9);
            assertEquals(4.016578269983029e-08, number(x1, 4), 1e-12);
        }
    }

    @Test
    void diagnosticsModeReportsTheModelFit() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, multiple(db), """
                    {"x":["x1","x2"],"y":"y","mode":"diagnostics"}
                    """);
            assertEquals(DIAGNOSTICS, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            List<String> row = data.data().get(0);
            assertEquals("10", row.get(0));
            assertEquals(7.0, number(row, 1), 1e-9);
            assertEquals(0.99954991530368931, number(row, 2), 1e-9);
            assertEquals(0.99942131967617198, number(row, 3), 1e-9);
            assertEquals(7772.8141663989718, number(row, 4), 1e-6);
            assertEquals(1.9343415758044102e-12, number(row, 5), 1e-12);
            assertEquals(0.14552274993381387, number(row, 6), 1e-9);
            assertEquals(3.1281584140240897, number(row, 7), 1e-9);
            assertEquals(-36.115206392579978, number(row, 8), 1e-9);
            assertEquals(-35.207451113597841, number(row, 9), 1e-9);
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
            List<ColumnSpec> expected = new ArrayList<>();
            expected.add(ColumnSpec.of("g", "Utf8"));
            expected.addAll(COEFFICIENTS);
            assertEquals(expected, plan.columns());

            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            List<String> aIntercept = data.data().stream()
                    .filter(row -> "A".equals(row.get(0)) && "intercept".equals(row.get(1)))
                    .findFirst().orElseThrow();
            assertEquals(1.0, number(aIntercept, 2), 1e-9);
            assertEquals(1.0, number(aIntercept, 8), 1e-9);
            assertEquals(0.0, number(aIntercept, 11), 1e-9);
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
                assertNull(row.get(7), term);
                assertNull(row.get(8), term);
                assertNull(row.get(9), term);
                assertNull(row.get(10), term);
                assertNull(row.get(12), term);
                assertNull(row.get(13), term);
                assertNull(row.get(14), term);
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
            assertEquals(1.0, number(row, 7), 1e-9);
            assertEquals(0.0, number(row, 10), 1e-9);
            assertEquals(4.0, number(row, 11), 1e-9);
            assertNull(row.get(12));
            assertEquals(2.0, number(row, 13), 1e-9);
            assertEquals(2.0, number(row, 14), 1e-9);
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
                    ColumnSpec.of("residual", "Float64"),
                    ColumnSpec.of("prediction_lower", "Float64"),
                    ColumnSpec.of("prediction_upper", "Float64"),
                    ColumnSpec.of("sampled_rows", "Int64"),
                    ColumnSpec.of("total_rows", "Int64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("1.0", "3.0", "3.0", "0.0", "3.0", "3.0", "3", "3"),
                    data.data().get(0));
            assertEquals(List.of("2.0", "5.0", "5.0", "0.0", "5.0", "5.0", "3", "3"),
                    data.data().get(1));
            assertEquals(List.of("3.0", "7.0", "7.0", "0.0", "7.0", "7.0", "3", "3"),
                    data.data().get(2));
        }
    }

    @Test
    void predictWithTwoPredictorsAddsPredictionIntervals() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, multiple(db), """
                    {"x":["x1","x2"],"y":"y","mode":"predict"}
                    """);
            assertEquals(List.of(ColumnSpec.of("x1", "Float64"),
                    ColumnSpec.of("x2", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("prediction", "Float64"),
                    ColumnSpec.of("residual", "Float64"),
                    ColumnSpec.of("prediction_lower", "Float64"),
                    ColumnSpec.of("prediction_upper", "Float64"),
                    ColumnSpec.of("sampled_rows", "Int64"),
                    ColumnSpec.of("total_rows", "Int64")), plan.columns());

            TableData data = plan.preview(20);
            assertEquals(10, data.rows());
            // Row (4, 3): prediction 8.113809523809234, interval from the covariance matrix.
            List<String> row = data.data().stream()
                    .filter(candidate -> "4.0".equals(candidate.get(0)) && "3.0".equals(candidate.get(1)))
                    .findFirst().orElseThrow();
            assertEquals(8.113809523809234, Double.parseDouble(row.get(3)), 1e-9);
            assertEquals(8.2, Double.parseDouble(row.get(2)), 1e-9);
            assertEquals(0.08619047619076552, Double.parseDouble(row.get(4)), 1e-9);
            assertEquals(7.7287203943610461, Double.parseDouble(row.get(5)), 1e-6);
            assertEquals(8.498898653257422, Double.parseDouble(row.get(6)), 1e-6);
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
                    new Object[]{"A", 3.0, 6.0},
                    new Object[]{"B", 1.0, 3.0},
                    new Object[]{"B", 2.0, 6.0},
                    new Object[]{"B", 3.0, 9.0}));

            LazyPlan plan = plan(db, input, """
                    {"x":["x"],"y":"y","group_by":["g"],"mode":"predict","output":"fitted"}
                    """);
            assertEquals(List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("fitted", "Float64"),
                    ColumnSpec.of("residual", "Float64"),
                    ColumnSpec.of("prediction_lower", "Float64"),
                    ColumnSpec.of("prediction_upper", "Float64"),
                    ColumnSpec.of("sampled_rows", "Int64"),
                    ColumnSpec.of("total_rows", "Int64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(6, data.rows());
            for (List<String> row : data.data()) {
                assertEquals(number(row, 2), number(row, 3), 1e-9, row.toString());
                assertEquals(0.0, number(row, 4), 1e-9, row.toString());
                assertEquals(number(row, 3), number(row, 5), 1e-9, row.toString());
                assertEquals(number(row, 3), number(row, 6), 1e-9, row.toString());
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
                    new Object[]{3.0, 7.0},
                    new Object[]{null, 9.0}));

            TableData data = run(db, input, """
                    {"x":["x"],"y":"y","mode":"predict"}
                    """);

            assertEquals(4, data.rows());
            assertNull(data.data().get(3).get(2));
            assertNull(data.data().get(3).get(3));
            assertNull(data.data().get(3).get(4));
            assertNull(data.data().get(3).get(5));
        }
    }

    @Test
    void tooFewRowsForParametersIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(
                            ColumnSpec.of("x1", "Float64"), ColumnSpec.of("x2", "Float64"),
                            ColumnSpec.of("y", "Float64")),
                    List.of(
                            new Object[]{1.0, 2.0, 3.0},
                            new Object[]{2.0, 4.0, 5.0},
                            new Object[]{3.0, 6.0, 7.0}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x1","x2"],"y":"y"}
                            """));
            assertTrue(failure.getMessage().contains("more rows than parameters"),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("3"), failure.getMessage());
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
            assertTrue(tooFewRows.getMessage().contains("more rows than parameters"),
                    tooFewRows.getMessage());

            IllegalArgumentException duplicatePredictor = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x","x"],"y":"y"}
                            """));
            assertTrue(duplicatePredictor.getMessage().contains("duplicate"),
                    duplicatePredictor.getMessage());

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

            IllegalArgumentException badRobust = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x"],"y":"y","robust":"hc3"}
                            """));
            assertTrue(badRobust.getMessage().contains("hc3"), badRobust.getMessage());

            IllegalArgumentException badSeed = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"x":["x"],"y":"y","seed":-1}
                            """));
            assertTrue(badSeed.getMessage().contains("seed"), badSeed.getMessage());

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

    @Test
    void sampleRowsFitsAReservoirSampleInsteadOfFailing() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            int n = 150_000;
            List<ColumnSpec> columns = List.of(ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"));
            List<Object[]> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                double x = i % 1000;
                rows.add(new Object[]{x, 2.0 * x + 1.0});
            }
            LazyPlan input = NodeTestSupport.table(db, columns, rows);

            TableData data = run(db, input, """
                    {"x":["x"],"y":"y","sample_rows":1000}
                    """);

            assertEquals(2, data.rows());
            List<String> slope = termRow(data, 0, "x");
            assertEquals(2.0, number(slope, 1), 1e-9);
            assertEquals(1000, Integer.parseInt(slope.get(6)));
        }
    }

    /**
     * Insertion order is a permutation of x and y has two regimes (y = x below 20,
     * y = 10x otherwise), so the fitted coefficients depend on which rows are sampled.
     */
    private static LazyPlan scrambled(DuckDbSession db) throws Exception {
        int n = 100;
        List<Object[]> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double x = (i * 37) % n;
            rows.add(new Object[]{x, x < 20 ? x : 10.0 * x});
        }
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64")), rows);
    }

    @Test
    void reservoirSampleIsRepeatableAcrossRuns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = scrambled(db);
            String config = """
                    {"x":["x"],"y":"y","sample_rows":20,"seed":42}
                    """;

            TableData first = run(db, input, config);
            TableData second = run(db, input, config);
            TableData otherSeed = run(db, input, """
                    {"x":["x"],"y":"y","sample_rows":20,"seed":43}
                    """);
            assertEquals(first.data(), second.data());
            assertEquals("20", termRow(first, 0, "x").get(6));
            assertNotEquals(first.data(), otherSeed.data(), "seed must change the sample");
        }
    }

    @Test
    void sampleRowsAtLeastTotalFitsEveryRow() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = scrambled(db);
            TableData full = run(db, input, """
                    {"x":["x"],"y":"y"}
                    """);
            TableData sampled = run(db, input, """
                    {"x":["x"],"y":"y","sample_rows":200,"seed":42}
                    """);
            assertEquals(full.data(), sampled.data());
            assertEquals("100", termRow(sampled, 0, "x").get(6));

            LazyPlan small = line(db, new double[]{1, 2, 3, 4, 5},
                    new double[]{3, 5, 7, 9, 11});
            TableData predictions = run(db, small, """
                    {"x":["x"],"y":"y","mode":"predict","sample_rows":50,"seed":42}
                    """);
            assertEquals("5", predictions.data().get(0).get(6));
            assertEquals("5", predictions.data().get(0).get(7));
        }
    }

    @Test
    void predictReportsSampledAndTotalRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = scrambled(db);
            TableData data = run(db, input, """
                    {"x":["x"],"y":"y","mode":"predict","sample_rows":20}
                    """);
            assertEquals(100, data.rows());
            for (List<String> row : data.data()) {
                assertEquals("20", row.get(6), row.toString());
                assertEquals("100", row.get(7), row.toString());
            }
        }
    }
}
