package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.Distributions;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dap.correlation. Reference values are R/independent double-precision computations:
 * Pearson p-values use the t approximation, Kendall the normal z approximation, the CI
 * Fisher's z transform (Kendall: the tie-corrected asymptotic standard error). Kendall
 * tau and asymptotic p-values are cross-checked against
 * tests/reference/kendall_pvalue.py (scipy); DAP never computes exact permutation p.
 */
class CorrelationExecutorTest {
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("column_a", "Utf8"),
            ColumnSpec.of("column_b", "Utf8"),
            ColumnSpec.of("method", "Utf8"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("correlation", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"),
            ColumnSpec.of("partial", "Utf8"));

    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("x", "Float64"),
            ColumnSpec.of("y", "Float64"),
            ColumnSpec.of("z", "Float64"),
            ColumnSpec.of("label", "Utf8"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{1.0, 2.0, 1.0, "a"},
            new Object[]{2.0, 4.0, 8.0, "b"},
            new Object[]{3.0, 6.0, 27.0, "c"},
            new Object[]{4.0, 8.0, 64.0, "d"});

    // Kendall vectors shared with tests/reference/kendall_pvalue.py (identical literals).
    private static final double[] KENDALL_X4 = {1.0, 2.0, 3.0, 4.0};
    private static final double[] KENDALL_Y4 = {1.0, 2.0, 2.0, 3.0};
    private static final double[] KENDALL_Y4_NO_TIES = {1.0, 2.0, 4.0, 3.0};
    private static final double[] KENDALL_X10 = {
            1.0, 1.0, 2.0, 2.0, 3.0, 4.0, 4.0, 5.0, 6.0, 6.0};
    private static final double[] KENDALL_Y10 = {
            2.0, 3.0, 1.0, 3.0, 5.0, 4.0, 4.0, 2.0, 6.0, 5.0};
    // x = 1..100; y = round(x + U(-120, 120), 6) with random.Random(42), frozen.
    private static final double[] KENDALL_Y100 = {
            34.462432, -111.997419, -50.992964, -62.429423, 61.753091,
            48.407877, 101.123096, -91.13468, -9.738763, -102.848667,
            -56.526886, 13.285269, -100.631367, -58.278964, 50.972265,
            26.785955, -50.094251, 39.423764, 93.26331, -98.440298,
            94.39662, 69.553455, -15.339876, -58.68492, 134.731137,
            -13.217309, -70.740998, -68.78807, 112.398648, 54.894248,
            104.710786, 87.135629, 41.694742, 147.547783, 5.848251,
            48.489752, 116.057119, 66.444741, 125.809656, 58.564515,
            90.097241, -67.002148, -22.304414, -6.546889, -55.849926,
            -18.130187, -48.759657, -5.286335, 81.564267, 17.559723,
            19.843432, -17.718313, -2.925323, 158.797101, 90.528492,
            82.191441, -21.926724, 112.990432, -21.783401, 31.069306,
            178.485604, 95.599942, 76.667939, 108.30742, 147.284461,
            132.239979, 1.971537, -44.295941, 24.708732, 14.25781,
            1.635882, 178.298331, 163.32823, 29.522691, 112.30528,
            50.951656, 176.491422, 68.124445, 22.57124, 19.190602,
            95.728352, 25.057986, 103.300638, 179.477492, 60.856121,
            18.636982, 206.409026, 90.28631, -9.181741, -18.69207,
            -2.684209, 122.58705, 163.099047, 75.318392, -9.753351,
            67.588629, 216.069131, 104.987443, 212.058811, 186.587129};
    // x = 1..50; y = 3 labels from random.Random(1), score = i+U(-40,40), cutoffs 20/35.
    private static final double[] KENDALL_X50 = new double[50];
    private static final double[] KENDALL_Y50 = {
            0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
            2.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 2.0, 2.0, 0.0,
            0.0, 1.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0,
            0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 2.0, 2.0, 0.0,
            2.0, 2.0, 0.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0};

    static {
        for (int i = 0; i < KENDALL_X50.length; i++) {
            KENDALL_X50[i] = i + 1.0;
        }
    }

    private static LazyPlan kendallInput(DuckDbSession db, double[] x, double[] y) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < x.length; i++) {
            rows.add(new Object[]{x[i], y[i]});
        }
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64")), rows);
    }

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        LazyPlan plan = new CorrelationExecutor().execute(
                NodeTestSupport.context(db, configJson), List.of(input));
        return plan.preview(20);
    }

    private static double number(TableData data, int row, int column) {
        return Double.parseDouble(data.data().get(row).get(column));
    }

    private static List<String> pairs(TableData data) {
        return data.data().stream().map(row -> row.get(0) + "|" + row.get(1)).toList();
    }

    private static List<String> row(TableData data, String first, String second) {
        return data.data().stream()
                .filter(row -> first.equals(row.get(0)) && second.equals(row.get(1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + first + "|" + second + " in " + data.data()));
    }

    private static LazyPlan line(DuckDbSession db) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        double[] ys = {2.1, 3.9, 6.2, 8.1, 9.8};
        for (int i = 0; i < ys.length; i++) {
            rows.add(new Object[]{(double) (i + 1), ys[i]});
        }
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64")), rows);
    }

    @Test
    void pearsonCorrelatesOnePair() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan plan = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db, "{\"columns\":[\"x\",\"y\"],\"method\":\"pearson\"}"),
                    List.of(input));

            assertEquals(OUTPUT, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals(List.of("x|y"), pairs(data));
            List<String> pair = row(data, "x", "y");
            assertEquals("pearson", pair.get(2));
            assertEquals("4", pair.get(3));
            assertEquals(1.0, Double.parseDouble(pair.get(4)), 1e-9);
            assertEquals(0.0, Double.parseDouble(pair.get(5)), 1e-9);
            assertEquals(1.0, Double.parseDouble(pair.get(6)), 1e-9);
            assertEquals(1.0, Double.parseDouble(pair.get(7)), 1e-9);
            assertNull(pair.get(8));
        }
    }

    @Test
    void everyPairIsEmittedInColumnOrder() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            TableData data = run(db, input, "{\"columns\":[\"x\",\"y\",\"z\"]}");
            assertEquals(3, data.rows());
            assertEquals(List.of("x|y", "x|z", "y|z"), pairs(data));
        }
    }

    @Test
    void spearmanRanksNonLinearMonotonicDataWhilePearsonDoesNot() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            TableData spearman = run(db, input,
                    "{\"columns\":[\"x\",\"z\"],\"method\":\"spearman\"}");
            assertEquals("spearman", spearman.data().get(0).get(2));
            assertEquals(1.0, number(spearman, 0, 4), 1e-9);

            TableData pearson = run(db, input,
                    "{\"columns\":[\"x\",\"z\"],\"method\":\"pearson\"}");
            double value = number(pearson, 0, 4);
            assertTrue(value > 0.9 && value < 1.0, "expected strong but imperfect pearson, got " + value);
        }
    }

    @Test
    void nullsAreDroppedPerPair() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("a", "Float64"), ColumnSpec.of("b", "Float64")),
                    List.of(
                            new Object[]{1.0, 1.0},
                            new Object[]{2.0, 2.0},
                            new Object[]{null, 3.0},
                            new Object[]{4.0, 4.0}));

            TableData pearson = run(db, input, "{\"columns\":[\"a\",\"b\"]}");
            assertEquals(1.0, number(pearson, 0, 4), 1e-9);
            assertEquals("3", pearson.data().get(0).get(3));

            TableData spearman = run(db, input,
                    "{\"columns\":[\"a\",\"b\"],\"method\":\"spearman\"}");
            assertEquals(1.0, number(spearman, 0, 4), 1e-9);
        }
    }

    @Test
    void pearsonPValueAndFisherIntervalMatchTheReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, line(db), "{\"columns\":[\"x\",\"y\"]}");

            // Independent: r = 0.99880472868807146, t = 35.393440807216663 with df = 3.
            assertEquals(0.99880472868807146, number(data, 0, 4), 1e-12);
            assertEquals(4.9597035775228449e-05, number(data, 0, 5), 1e-12);
            assertEquals(0.98106023547141119, number(data, 0, 6), 1e-9);
            assertEquals(0.99992519526086376, number(data, 0, 7), 1e-9);
        }
    }

    @Test
    void kendallTauBHandlesTies() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = kendallInput(db, KENDALL_X4, KENDALL_Y4);

            TableData data = run(db, input, "{\"columns\":[\"x\",\"y\"],\"method\":\"kendall\"}");
            assertEquals("kendall", data.data().get(0).get(2));
            // scipy method='asymptotic': tau_b = 0.9128709291752769, z = 1.805787796286538,
            // p = 0.07095149242730564 with the tie-corrected SE (ytie = 1). scipy cannot
            // compute an exact p-value when ties are present; DAP only does asymptotic.
            assertEquals(0.9128709291752769, number(data, 0, 4), 1e-12);
            assertEquals(0.07095149242730564, number(data, 0, 5), 1e-9);
            assertEquals(-0.07793992213100365, number(data, 0, 6), 1e-9);
            assertEquals(1.0, number(data, 0, 7), 1e-9);
        }
    }

    @Test
    void kendallWithoutTiesUsesAsymptoticNotExactPValue() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, kendallInput(db, KENDALL_X4, KENDALL_Y4_NO_TIES),
                    "{\"columns\":[\"x\",\"y\"],\"method\":\"kendall\"}");

            // scipy asymptotic: tau = 0.6666666666666669, p = 0.1742313882480251.
            // The exact permutation p-value would be 0.3333333333333333; DAP does not
            // compute it even for this tiny sample.
            assertEquals(0.6666666666666669, number(data, 0, 4), 1e-12);
            assertEquals(0.1742313882480251, number(data, 0, 5), 1e-9);
            assertEquals(-0.2949962898929357, number(data, 0, 6), 1e-9);
        }
    }

    @Test
    void kendallPValueMatchesScipyReferenceForHundredObservations() throws Exception {
        double[] x = new double[KENDALL_Y100.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = i + 1.0;
        }
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, kendallInput(db, x, KENDALL_Y100),
                    "{\"columns\":[\"x\",\"y\"],\"method\":\"kendall\"}");

            assertEquals("100", data.data().get(0).get(3));
            // scipy asymptotic: tau = 0.30020202020202025, z = 4.425481546448939 (no ties).
            assertEquals(0.30020202020202025, number(data, 0, 4), 1e-9);
            assertEquals(9.622738296844866e-06, number(data, 0, 5), 1e-9);
            assertEquals(0.16724809383117076, number(data, 0, 6), 1e-9);
            assertEquals(0.4331559465728697, number(data, 0, 7), 1e-9);
        }
    }

    @Test
    void kendallPValueMatchesScipyReferenceWithTies() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, kendallInput(db, KENDALL_X10, KENDALL_Y10),
                    "{\"columns\":[\"x\",\"y\"],\"method\":\"kendall\"}");

            // scipy asymptotic: tau_b = 0.48780487804878053, z = 1.8461975436542644.
            assertEquals(0.48780487804878053, number(data, 0, 4), 1e-9);
            assertEquals(0.06486352749968662, number(data, 0, 5), 1e-9);
            assertEquals(-0.03005952695207853, number(data, 0, 6), 1e-9);
            assertEquals(1.0, number(data, 0, 7), 1e-9);
        }
    }

    @Test
    void kendallPValueMatchesScipyReferenceWithStrongTies() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, kendallInput(db, KENDALL_X50, KENDALL_Y50),
                    "{\"columns\":[\"x\",\"y\"],\"method\":\"kendall\"}");

            // y has only three distinct values: scipy asymptotic tau = 0.3397105998335,
            // z = 3.0310897778010712 with the tie-corrected variance.
            assertEquals(0.3397105998335, number(data, 0, 4), 1e-9);
            assertEquals(0.0024367278316132506, number(data, 0, 5), 1e-9);
            assertEquals(0.12004685192459333, number(data, 0, 6), 1e-9);
            assertEquals(0.5593743477424067, number(data, 0, 7), 1e-9);
        }
    }

    @Test
    void kendallAsymptoticZMatchesScipy() throws Exception {
        double[] x100 = new double[KENDALL_Y100.length];
        for (int i = 0; i < x100.length; i++) {
            x100[i] = i + 1.0;
        }
        try (DuckDbSession db = new DuckDbSession()) {
            // {x, y, scipy z, scipy tie-corrected SE} from tests/reference/kendall_pvalue.py.
            for (Object[] referenceCase : List.of(
                    new Object[]{KENDALL_X4, KENDALL_Y4, 1.805787796286538, 0.5055250296034367},
                    new Object[]{KENDALL_X4, KENDALL_Y4_NO_TIES, 1.3587324409735153,
                            0.49065338146265813},
                    new Object[]{KENDALL_X10, KENDALL_Y10, 1.8461975436542644,
                            0.26422138829371733},
                    new Object[]{KENDALL_X50, KENDALL_Y50, 3.0310897778010712,
                            0.112075400181629},
                    new Object[]{x100, KENDALL_Y100, 4.425481546448939, 0.06783488238537703})) {
                double[] x = (double[]) referenceCase[0];
                double[] y = (double[]) referenceCase[1];
                double expectedZ = (double) referenceCase[2];
                double expectedSe = (double) referenceCase[3];
                TableData data = run(db, kendallInput(db, x, y),
                        "{\"columns\":[\"x\",\"y\"],\"method\":\"kendall\"}");
                double tau = number(data, 0, 4);
                double p = number(data, 0, 5);
                double zFromP = Distributions.normalQuantile(1.0 - p / 2.0);
                assertEquals(expectedZ, zFromP, 1e-12);
                assertEquals(expectedZ, Math.abs(tau) / expectedSe, 1e-12);
            }
        }
    }

    @Test
    void partialCorrelationControlsForTheGivenColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1.0, 2.0, 1.0, "a"},
                    new Object[]{2.0, 1.0, 3.0, "b"},
                    new Object[]{3.0, 4.0, 2.0, "c"},
                    new Object[]{4.0, 3.0, 6.0, "d"},
                    new Object[]{5.0, 6.0, 4.0, "e"},
                    new Object[]{6.0, 5.0, 5.0, "f"}));

            TableData data = run(db, input,
                    "{\"columns\":[\"x\",\"y\"],\"partial\":[\"z\"]}");

            // r_xy = 0.8285714, r_xz = 0.7714286, r_yz = 0.3714286 -> partial 0.9174772.
            assertEquals(0.91747720319216741, number(data, 0, 4), 1e-9);
            assertEquals("6", data.data().get(0).get(3));
            assertEquals(0.028102475003227489, number(data, 0, 5), 1e-9);
            assertEquals(0.1847940283882554, number(data, 0, 6), 1e-9);
            assertEquals(0.99463060393110303, number(data, 0, 7), 1e-9);
            assertEquals("z", data.data().get(0).get(8));
        }
    }

    @Test
    void matrixOutputHasColumnPlusOneColumnPerVariable() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1.0, 2.0, 1.0, "a"},
                    new Object[]{2.0, 1.0, 3.0, "b"},
                    new Object[]{3.0, 4.0, 2.0, "c"},
                    new Object[]{4.0, 3.0, 6.0, "d"},
                    new Object[]{5.0, 6.0, 4.0, "e"},
                    new Object[]{6.0, 5.0, 5.0, "f"}));

            LazyPlan plan = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db,
                            "{\"columns\":[\"x\",\"y\",\"z\"],\"output\":\"matrix\"}"),
                    List.of(input));
            assertEquals(List.of(
                    ColumnSpec.of("column", "Utf8"),
                    ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("z", "Float64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("x", "y", "z"), data.data().stream().map(r -> r.get(0)).toList());
            for (int i = 0; i < 3; i++) {
                assertEquals(1.0, number(data, i, i + 1), 1e-9);
            }
            assertEquals(0.82857142857142863, number(data, 0, 2), 1e-9);
            assertEquals(0.77142857142857146, number(data, 0, 3), 1e-9);
            assertEquals(0.37142857142857144, number(data, 1, 3), 1e-9);
            assertEquals(number(data, 0, 2), number(data, 1, 1), 1e-9);
        }
    }

    @Test
    void tooFewObservationsLeaveThePValueAndIntervalNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("a", "Float64"), ColumnSpec.of("b", "Float64")),
                    List.of(new Object[]{1.0, 2.0}, new Object[]{2.0, 4.0}));

            TableData data = run(db, input, "{\"columns\":[\"a\",\"b\"]}");
            assertEquals(1.0, number(data, 0, 4), 1e-9);
            assertEquals("2", data.data().get(0).get(3));
            assertNull(data.data().get(0).get(5));
            assertNull(data.data().get(0).get(6));
            assertNull(data.data().get(0).get(7));
        }
    }

    @Test
    void alphaWidensOrNarrowsTheInterval() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData ninety = run(db, line(db), "{\"columns\":[\"x\",\"y\"],\"alpha\":0.1}");
            TableData ninetyNine = run(db, line(db), "{\"columns\":[\"x\",\"y\"],\"alpha\":0.01}");
            assertTrue(number(ninety, 0, 6) > number(ninetyNine, 0, 6));
            assertTrue(number(ninety, 0, 7) < number(ninetyNine, 0, 7));
        }
    }

    @Test
    void invalidColumnsAndMethodAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException single = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\"]}"));
            assertTrue(single.getMessage().contains("two"), single.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"nope\"]}"));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"label\"]}"));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"x\"]}"));
            assertTrue(duplicate.getMessage().contains("duplicate"), duplicate.getMessage());

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"y\"],\"method\":\"distance\"}"));
            assertTrue(method.getMessage().contains("distance"), method.getMessage());

            IllegalArgumentException output = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input,
                            "{\"columns\":[\"x\",\"y\"],\"output\":\"wide\"}"));
            assertTrue(output.getMessage().contains("wide"), output.getMessage());

            IllegalArgumentException partial = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input,
                            "{\"columns\":[\"x\",\"y\"],\"partial\":[\"nope\"]}"));
            assertTrue(partial.getMessage().contains("nope"), partial.getMessage());

            IllegalArgumentException partialNonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input,
                            "{\"columns\":[\"x\",\"y\"],\"partial\":[\"label\"]}"));
            assertTrue(partialNonNumeric.getMessage().contains("numeric"),
                    partialNonNumeric.getMessage());

            IllegalArgumentException partialOverlap = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input,
                            "{\"columns\":[\"x\",\"y\"],\"partial\":[\"x\"]}"));
            assertTrue(partialOverlap.getMessage().contains("x"), partialOverlap.getMessage());

            IllegalArgumentException alpha = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"y\"],\"alpha\":1.5}"));
            assertTrue(alpha.getMessage().contains("alpha"), alpha.getMessage());
        }
    }
}
