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
 * dap.hypothesis_test. Reference statistics and p-values are R values (t.test, aov,
 * wilcox.test, kruskal.test, TukeyHSD), scipy's chi2_contingency for chi_square and
 * independent double-precision computations for the rank tests and the studentized
 * range; p-values are pinned with tolerance 1e-6 end to end (1e-9 for chi_square).
 */
class HypothesisTestExecutorTest {
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("test", "Utf8"),
            ColumnSpec.of("statistic", "Float64"),
            ColumnSpec.of("df", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("comparison", "Utf8"),
            ColumnSpec.of("p_adjusted", "Float64"),
            ColumnSpec.of("effect_size", "Float64"),
            ColumnSpec.of("effect_size_name", "Utf8"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"));

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        NodeContext context = NodeTestSupport.context(db, configJson);
        LazyPlan plan = new HypothesisTestExecutor().execute(context, List.of(input));
        assertEquals(OUTPUT, plan.columns());
        return plan.preview(20);
    }

    private static double number(TableData data, int row, int column) {
        return Double.parseDouble(data.data().get(row).get(column));
    }

    private static double first(TableData data, int column) {
        return number(data, 0, column);
    }

    private static List<Object[]> rows(Object[]... rows) {
        return new ArrayList<>(List.of(rows));
    }

    private static LazyPlan oneSampleTable(DuckDbSession db) throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Float64"));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rows.add(new Object[]{(double) i});
        }
        return NodeTestSupport.table(db, columns, rows);
    }

    private static LazyPlan grouped(DuckDbSession db, List<Object[]> rows) throws Exception {
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("g", "Utf8"), ColumnSpec.of("v", "Float64")), rows);
    }

    /** Expand a contingency matrix into one (v, g) row per observation. */
    private static LazyPlan contingency(DuckDbSession db, String[] values, String[] groups,
                                        int[][] counts) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            for (int j = 0; j < counts[i].length; j++) {
                for (int k = 0; k < counts[i][j]; k++) {
                    rows.add(new Object[]{values[i], groups[j]});
                }
            }
        }
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("v", "Utf8"), ColumnSpec.of("g", "Utf8")), rows);
    }

    private static LazyPlan twoGroups(DuckDbSession db) throws Exception {
        return grouped(db, List.of(
                new Object[]{"A", 1.0}, new Object[]{"A", 2.0}, new Object[]{"A", 3.0},
                new Object[]{"A", 4.0}, new Object[]{"A", 5.0},
                new Object[]{"B", 2.0}, new Object[]{"B", 4.0}, new Object[]{"B", 6.0},
                new Object[]{"B", 8.0}, new Object[]{"B", 10.0}));
    }

    // ------------------------------------------------------------- t tests

    @Test
    void oneSampleTTestMatchesR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, oneSampleTable(db), """
                    {"test":"t_test_one_sample","value":"v","mu":0}
                    """);

            assertEquals(1, data.rows());
            assertEquals("t_test_one_sample", data.data().get(0).get(0));
            assertEquals(5.744562646538029, first(data, 1), 1e-6);
            assertEquals(9.0, first(data, 2), 1e-6);
            assertEquals(0.0002781960111004622, first(data, 3), 1e-6);
            assertEquals("10", data.data().get(0).get(4));
            assertEquals("v vs 0", data.data().get(0).get(5));
            assertEquals(0.0002781960111004622, first(data, 6), 1e-6);
            assertEquals("cohens_d", data.data().get(0).get(8));
            assertEquals(1.8165902124584949, first(data, 7), 1e-9);
            assertEquals(0.65207353901075438, first(data, 9), 1e-6);
            assertEquals(2.9811068859062355, first(data, 10), 1e-6);
        }
    }

    @Test
    void oneSampleAlternativesUseTheRequestedTail() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData greater = run(db, oneSampleTable(db), """
                    {"test":"t_test_one_sample","value":"v","mu":0,"alternative":"greater"}
                    """);
            assertEquals(0.0001390980055502, first(greater, 3), 1e-6);

            TableData less = run(db, oneSampleTable(db), """
                    {"test":"t_test_one_sample","value":"v","mu":0,"alternative":"less"}
                    """);
            assertEquals(0.9998609019944498, first(less, 3), 1e-6);
        }
    }

    @Test
    void twoSampleWelchTTestMatchesR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, twoGroups(db), """
                    {"test":"t_test_two_sample","value":"v","group":"g","group_a":"A","group_b":"B"}
                    """);

            assertEquals(-1.8973665961010275, first(data, 1), 1e-6);
            assertEquals(5.882352941176471, first(data, 2), 1e-6);
            assertEquals(0.10753119493061747, first(data, 3), 1e-6);
            assertEquals("10", data.data().get(0).get(4));
            assertEquals("A vs B", data.data().get(0).get(5));
            // Cohen's d with the pooled sd: -3 / 2.5.
            assertEquals(-1.2, first(data, 7), 1e-9);
            assertEquals("cohens_d", data.data().get(0).get(8));
            assertEquals(-2.7842768677807399, first(data, 9), 1e-6);
            assertEquals(0.38427686778074022, first(data, 10), 1e-6);
        }
    }

    @Test
    void twoSampleWithoutGroupLabelsUsesTheTwoDistinctGroups() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = grouped(db, List.of(
                    new Object[]{"A", 1.0},
                    new Object[]{"A", 2.0},
                    new Object[]{"B", 3.0},
                    new Object[]{"B", 4.0}));

            TableData data = run(db, input, """
                    {"test":"t_test_two_sample","value":"v","group":"g"}
                    """);

            assertEquals(1, data.rows());
            assertEquals("4", data.data().get(0).get(4));
            // R: t.test(c(1,2), c(3,4)) -> t = -2.828427, df = 2, p = 0.1055728
            assertEquals(-2.8284271247461903, first(data, 1), 1e-6);
            assertEquals(2.0, first(data, 2), 1e-6);
            assertEquals(0.10557280900008414, first(data, 3), 1e-6);
            assertEquals("A vs B", data.data().get(0).get(5));
        }
    }

    @Test
    void pairedTTestMatchesR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("a", "Float64"),
                    ColumnSpec.of("b", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{1.0, 1.5},
                    new Object[]{2.0, 2.5},
                    new Object[]{3.0, 2.5},
                    new Object[]{4.0, 4.5},
                    new Object[]{5.0, 5.5}));

            TableData data = run(db, input, """
                    {"test":"t_test_paired","group_a":"a","group_b":"b"}
                    """);

            assertEquals(-1.5, first(data, 1), 1e-6);
            assertEquals(4.0, first(data, 2), 1e-6);
            assertEquals(0.208, first(data, 3), 1e-6);
            assertEquals("5", data.data().get(0).get(4));
            assertEquals("a vs b", data.data().get(0).get(5));
            assertEquals(-0.67082039324993692, first(data, 7), 1e-9);
            assertEquals("cohens_dz", data.data().get(0).get(8));
            assertEquals(-2.0450906062842256, first(data, 9), 1e-6);
            assertEquals(0.70344981978435184, first(data, 10), 1e-6);
        }
    }

    // ---------------------------------------------------------- chi / anova

    @Test
    void chiSquareTestOfIndependenceMatchesR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Utf8"),
                    ColumnSpec.of("g", "Utf8"));
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                rows.add(new Object[]{"a", "g1"});
            }
            for (int i = 0; i < 20; i++) {
                rows.add(new Object[]{"b", "g1"});
            }
            for (int i = 0; i < 20; i++) {
                rows.add(new Object[]{"a", "g2"});
            }
            for (int i = 0; i < 10; i++) {
                rows.add(new Object[]{"b", "g2"});
            }
            LazyPlan input = NodeTestSupport.table(db, columns, rows);

            TableData data = run(db, input, """
                    {"test":"chi_square","value":"v","group":"g"}
                    """);

            // scipy.stats.chi2_contingency(..., correction=False), tests/reference/chi_square.py.
            assertEquals(6.666666666666667, first(data, 1), 1e-9);
            assertEquals(1.0, first(data, 2), 1e-9);
            assertEquals(0.009823274507519249, first(data, 3), 1e-9);
            assertEquals("60", data.data().get(0).get(4));
            assertEquals("v vs g", data.data().get(0).get(5));
            // Cramer's V = sqrt(chi2 / (n * min(r-1, c-1))) = sqrt(6.6667 / 60).
            assertEquals(0.33333333333333337, first(data, 7), 1e-9);
            assertEquals("cramers_v", data.data().get(0).get(8));
            assertNull(data.data().get(0).get(9));
            assertNull(data.data().get(0).get(10));
        }
    }

    @Test
    void chiSquareSparse2x2CountsUnobservedCells() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = contingency(db, new String[]{"a", "b"}, new String[]{"g1", "g2"},
                    new int[][]{{10, 0}, {0, 10}});

            TableData data = run(db, input, """
                    {"test":"chi_square","value":"v","group":"g"}
                    """);

            // All four expected frequencies are 5: two observed cells give (10-5)^2/5
            // each and two unobserved cells give (0-5)^2/5 each, so chi2 = 20.
            assertEquals(1, data.rows());
            assertEquals(20.0, first(data, 1), 1e-9);
            assertEquals(1.0, first(data, 2), 1e-9);
            assertEquals(7.744216431044084e-06, first(data, 3), 1e-9);
            assertEquals("20", data.data().get(0).get(4));
            assertEquals(1.0, first(data, 7), 1e-9);
            assertEquals("cramers_v", data.data().get(0).get(8));
        }
    }

    @Test
    void chiSquareSparse3x3MatchesReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = contingency(db, new String[]{"a", "b", "c"},
                    new String[]{"g1", "g2", "g3"},
                    new int[][]{{5, 0, 5}, {0, 5, 5}, {5, 5, 0}});

            TableData data = run(db, input, """
                    {"test":"chi_square","value":"v","group":"g"}
                    """);

            assertEquals(15.0, first(data, 1), 1e-9);
            assertEquals(4.0, first(data, 2), 1e-9);
            assertEquals(0.004701217146256585, first(data, 3), 1e-9);
            assertEquals("30", data.data().get(0).get(4));
            assertEquals(0.5, first(data, 7), 1e-9);
        }
    }

    @Test
    void chiSquareGoldenP2FixtureMatchesReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // wf-n11-hypothesis: name x category on p2.csv, six rows over 5x3 categories.
            LazyPlan input = contingency(db,
                    new String[]{"alpha", "beta", "gamma", "delta", "epsilon"},
                    new String[]{"A", "B", "C"},
                    new int[][]{{1, 0, 0}, {0, 1, 0}, {2, 0, 0}, {0, 0, 1}, {0, 1, 0}});

            TableData data = run(db, input, """
                    {"test":"chi_square","value":"v","group":"g"}
                    """);

            assertEquals(12.0, first(data, 1), 1e-9);
            assertEquals(8.0, first(data, 2), 1e-9);
            assertEquals(0.15120388277664784, first(data, 3), 1e-9);
            assertEquals("6", data.data().get(0).get(4));
            assertEquals(1.0, first(data, 7), 1e-9);
        }
    }

    @Test
    void oneWayAnovaMatchesR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = grouped(db, List.of(
                    new Object[]{"g1", 1.0}, new Object[]{"g1", 2.0}, new Object[]{"g1", 3.0},
                    new Object[]{"g2", 4.0}, new Object[]{"g2", 5.0}, new Object[]{"g2", 6.0},
                    new Object[]{"g3", 7.0}, new Object[]{"g3", 8.0}, new Object[]{"g3", 9.0}));

            TableData data = run(db, input, """
                    {"test":"anova","value":"v","group":"g"}
                    """);

            assertEquals(27.0, first(data, 1), 1e-6);
            assertEquals(2.0, first(data, 2), 1e-6);
            assertEquals(0.001, first(data, 3), 1e-6);
            assertEquals("9", data.data().get(0).get(4));
            assertEquals("v ~ g", data.data().get(0).get(5));
            assertEquals(0.9, first(data, 7), 1e-9);
            assertEquals("eta_squared", data.data().get(0).get(8));
        }
    }

    // ------------------------------------------------------- nonparametric

    @Test
    void mannWhitneyMatchesTheReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, twoGroups(db), """
                    {"test":"mann_whitney","value":"v","group":"g","group_a":"A","group_b":"B"}
                    """);

            assertEquals(1, data.rows());
            assertEquals("mann_whitney", data.data().get(0).get(0));
            assertEquals("A vs B", data.data().get(0).get(5));
            // U_A = 5, normal approximation with tie correction and continuity: p = 0.141238.
            assertEquals(5.0, first(data, 1), 1e-9);
            assertNull(data.data().get(0).get(2));
            assertEquals(0.1412381638888196, first(data, 3), 1e-6);
            assertEquals("10", data.data().get(0).get(4));
            // rank-biserial = 2U/(n1*n2) - 1 = -0.6.
            assertEquals(-0.6, first(data, 7), 1e-9);
            assertEquals("rank_biserial", data.data().get(0).get(8));
        }
    }

    @Test
    void mannWhitneyAlternativesUseTheRequestedTail() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData less = run(db, twoGroups(db), """
                    {"test":"mann_whitney","value":"v","group":"g","group_a":"A","group_b":"B","alternative":"less"}
                    """);
            assertEquals(0.0706190819444098, first(less, 3), 1e-6);

            TableData greater = run(db, twoGroups(db), """
                    {"test":"mann_whitney","value":"v","group":"g","group_a":"A","group_b":"B","alternative":"greater"}
                    """);
            assertEquals(0.9293809180555902, first(greater, 3), 1e-6);
        }
    }

    @Test
    void kruskalWallisMatchesTheReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = grouped(db, List.of(
                    new Object[]{"A", 1.0}, new Object[]{"A", 2.0}, new Object[]{"A", 3.0},
                    new Object[]{"B", 4.0}, new Object[]{"B", 5.0}, new Object[]{"B", 6.0},
                    new Object[]{"C", 7.0}, new Object[]{"C", 8.0}, new Object[]{"C", 9.0}));

            TableData data = run(db, input, """
                    {"test":"kruskal_wallis","value":"v","group":"g"}
                    """);

            assertEquals("kruskal_wallis", data.data().get(0).get(0));
            assertEquals(7.2, first(data, 1), 1e-9);
            assertEquals(2.0, first(data, 2), 1e-9);
            assertEquals(0.027323722447292531, first(data, 3), 1e-6);
            assertEquals("9", data.data().get(0).get(4));
            assertEquals("v ~ g", data.data().get(0).get(5));
            // epsilon squared = H / (N - 1) = 7.2 / 8.
            assertEquals(0.9, first(data, 7), 1e-9);
            assertEquals("epsilon_squared", data.data().get(0).get(8));
        }
    }

    @Test
    void wilcoxonSignedRankMatchesTheReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("a", "Float64"),
                    ColumnSpec.of("b", "Float64"));
            List<Object[]> rows = new ArrayList<>();
            double[] a = {1, 2, 3, 4, 5, 6, 7, 8};
            double[] b = {2, 1, 3, 2, 6, 5, 8, 7};
            for (int i = 0; i < a.length; i++) {
                rows.add(new Object[]{a[i], b[i]});
            }
            LazyPlan input = NodeTestSupport.table(db, columns, rows);

            TableData data = run(db, input, """
                    {"test":"wilcoxon_signed_rank","group_a":"a","group_b":"b"}
                    """);

            assertEquals("wilcoxon_signed_rank", data.data().get(0).get(0));
            assertEquals("a vs b", data.data().get(0).get(5));
            // W+ = 17.5, W- = 10.5 over 7 non-zero differences (one tie group of 6).
            assertEquals(17.5, first(data, 1), 1e-9);
            assertEquals(0.58774635236895301, first(data, 3), 1e-6);
            assertEquals("7", data.data().get(0).get(4));
            assertEquals(0.25, first(data, 7), 1e-9);
            assertEquals("rank_biserial", data.data().get(0).get(8));
        }
    }

    @Test
    void wilcoxonIgnoresZeroDifferences() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("a", "Float64"),
                    ColumnSpec.of("b", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{1.0, 1.0},
                    new Object[]{2.0, 3.0},
                    new Object[]{4.0, 2.0},
                    new Object[]{6.0, 4.0}));

            TableData data = run(db, input, """
                    {"test":"wilcoxon_signed_rank","group_a":"a","group_b":"b"}
                    """);

            assertEquals("3", data.data().get(0).get(4));
        }
    }

    // ---------------------------------------------------------------- tukey

    @Test
    void tukeyHsdEmitsOneRowPerPairWithStudentizedRangeP() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = grouped(db, List.of(
                    new Object[]{"A", 1.0}, new Object[]{"A", 2.0}, new Object[]{"A", 3.0},
                    new Object[]{"B", 4.0}, new Object[]{"B", 5.0}, new Object[]{"B", 6.0},
                    new Object[]{"C", 7.0}, new Object[]{"C", 8.0}, new Object[]{"C", 9.0}));

            TableData data = run(db, input, """
                    {"test":"tukey_hsd","value":"v","group":"g"}
                    """);

            assertEquals(3, data.rows());
            assertEquals(List.of("A vs B", "A vs C", "B vs C"),
                    data.data().stream().map(row -> row.get(5)).toList());
            // MSE = 1, df = 6; q(A,B) = 3 / sqrt(1/3) = 5.196152.
            assertEquals(5.196152422706632, number(data, 0, 1), 1e-9);
            assertEquals(6.0, number(data, 0, 2), 1e-9);
            assertEquals(0.024229053412426538, number(data, 0, 3), 1e-3);
            assertEquals(10.392304845413264, number(data, 1, 1), 1e-9);
            assertEquals(0.00079421791049805179, number(data, 1, 3), 1e-3);
            assertEquals("9", data.data().get(0).get(4));
            // Cohen's d = diff / sqrt(MSE); CI via t(df=6) on the difference.
            assertEquals(-3.0, number(data, 0, 7), 1e-9);
            assertEquals("cohens_d", data.data().get(0).get(8));
            assertEquals(-4.9978951602913995, number(data, 0, 9), 1e-6);
            assertEquals(-1.0021048397086008, number(data, 0, 10), 1e-6);
        }
    }

    @Test
    void tukeyCorrectionsAdjustThePValues() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = grouped(db, List.of(
                    new Object[]{"A", 1.0}, new Object[]{"A", 2.0}, new Object[]{"A", 3.0},
                    new Object[]{"B", 4.0}, new Object[]{"B", 5.0}, new Object[]{"B", 6.0},
                    new Object[]{"C", 7.0}, new Object[]{"C", 8.0}, new Object[]{"C", 9.0}));

            TableData none = run(db, input, """
                    {"test":"tukey_hsd","value":"v","group":"g"}
                    """);
            for (int i = 0; i < 3; i++) {
                assertEquals(number(none, i, 3), number(none, i, 6), 1e-12);
            }

            TableData bonferroni = run(db, input, """
                    {"test":"tukey_hsd","value":"v","group":"g","correction":"bonferroni"}
                    """);
            assertEquals(Math.min(1.0, number(none, 0, 3) * 3), number(bonferroni, 0, 6), 1e-9);
            assertEquals(Math.min(1.0, number(none, 1, 3) * 3), number(bonferroni, 1, 6), 1e-9);

            TableData holm = run(db, input, """
                    {"test":"tukey_hsd","value":"v","group":"g","correction":"holm"}
                    """);
            // Holm step-down over the sorted p-values, with the running maximum for
            // monotonicity: A vs C is the smallest, A vs B the next, B vs C the largest.
            assertEquals(Math.min(1.0, number(none, 1, 3) * 3), number(holm, 1, 6), 1e-9);
            assertEquals(Math.min(1.0, number(none, 0, 3) * 2), number(holm, 0, 6), 1e-9);
            assertEquals(Math.max(Math.min(1.0, number(none, 2, 3)), number(holm, 0, 6)),
                    number(holm, 2, 6), 1e-9);

            TableData bh = run(db, input, """
                    {"test":"tukey_hsd","value":"v","group":"g","correction":"bh"}
                    """);
            // BH step-up over 3 tests: p(3)*3/3, p(2)*3/2, p(1)*3/1 with monotonicity.
            assertTrue(number(bh, 0, 6) <= number(bh, 2, 6) + 1e-12);
            assertTrue(number(bh, 1, 6) <= number(bh, 2, 6) + 1e-12);
        }
    }

    // ----------------------------------------------------------- degeneracy

    @Test
    void zeroVarianceLeavesTheStatisticUndefined() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{5.0}, new Object[]{5.0}, new Object[]{5.0}));

            TableData data = run(db, input, """
                    {"test":"t_test_one_sample","value":"v","mu":0}
                    """);

            assertEquals(1, data.rows());
            assertNull(data.data().get(0).get(1));
            assertNull(data.data().get(0).get(3));
            assertEquals(2.0, first(data, 2), 1e-9);
            assertEquals("3", data.data().get(0).get(4));
            assertNull(data.data().get(0).get(7));
        }
    }

    @Test
    void samplesSmallerThanTwoAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> one = List.of(ColumnSpec.of("v", "Float64"));
            LazyPlan single = NodeTestSupport.table(db, one, List.<Object[]>of(new Object[]{1.0}));
            IllegalArgumentException oneSample = assertThrows(IllegalArgumentException.class,
                    () -> run(db, single, """
                            {"test":"t_test_one_sample","value":"v"}
                            """));
            assertTrue(oneSample.getMessage().contains("at least 2"), oneSample.getMessage());

            List<ColumnSpec> groupedColumns = List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan small = NodeTestSupport.table(db, groupedColumns, List.of(
                    new Object[]{"A", 1.0},
                    new Object[]{"B", 1.0},
                    new Object[]{"B", 2.0}));
            IllegalArgumentException twoSample = assertThrows(IllegalArgumentException.class,
                    () -> run(db, small, """
                            {"test":"t_test_two_sample","value":"v","group":"g"}
                            """));
            assertTrue(twoSample.getMessage().contains("at least 2"), twoSample.getMessage());

            LazyPlan oneGroup = NodeTestSupport.table(db, groupedColumns, List.of(
                    new Object[]{"only", 1.0},
                    new Object[]{"only", 2.0}));
            IllegalArgumentException chi = assertThrows(IllegalArgumentException.class,
                    () -> run(db, oneGroup, """
                            {"test":"chi_square","value":"v","group":"g"}
                            """));
            assertTrue(chi.getMessage().contains("at least two"), chi.getMessage());

            IllegalArgumentException anova = assertThrows(IllegalArgumentException.class,
                    () -> run(db, single, """
                            {"test":"anova","value":"v","group":"v"}
                            """));
            assertTrue(anova.getMessage().contains("at least two"), anova.getMessage());
        }
    }

    @Test
    void invalidConfigurationIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = oneSampleTable(db);

            IllegalArgumentException unknownTest = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"test":"friedman","value":"v"}
                            """));
            assertTrue(unknownTest.getMessage().contains("friedman"), unknownTest.getMessage());
            assertTrue(unknownTest.getMessage().contains("tukey_hsd"), unknownTest.getMessage());

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"test":"t_test_one_sample","value":"nope"}
                            """));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException badAlternative = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"test":"t_test_one_sample","value":"v","alternative":"both"}
                            """));
            assertTrue(badAlternative.getMessage().contains("both"), badAlternative.getMessage());

            IllegalArgumentException badAlpha = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"test":"t_test_one_sample","value":"v","alpha":2}
                            """));
            assertTrue(badAlpha.getMessage().contains("alpha"), badAlpha.getMessage());

            IllegalArgumentException badCorrection = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"test":"t_test_one_sample","value":"v","correction":"fdr"}
                            """));
            assertTrue(badCorrection.getMessage().contains("fdr"), badCorrection.getMessage());

            List<ColumnSpec> groupedColumns = List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan groups = NodeTestSupport.table(db, groupedColumns, List.of(
                    new Object[]{"A", 1.0},
                    new Object[]{"A", 2.0},
                    new Object[]{"B", 3.0},
                    new Object[]{"B", 4.0},
                    new Object[]{"C", 5.0},
                    new Object[]{"C", 6.0}));
            IllegalArgumentException threeGroups = assertThrows(IllegalArgumentException.class,
                    () -> run(db, groups, """
                            {"test":"t_test_two_sample","value":"v","group":"g"}
                            """));
            assertTrue(threeGroups.getMessage().contains("group_a"), threeGroups.getMessage());

            IllegalArgumentException halfLabels = assertThrows(IllegalArgumentException.class,
                    () -> run(db, groups, """
                            {"test":"t_test_two_sample","value":"v","group":"g","group_a":"A"}
                            """));
            assertTrue(halfLabels.getMessage().contains("group_b"), halfLabels.getMessage());

            IllegalArgumentException textValue = assertThrows(IllegalArgumentException.class,
                    () -> run(db, groups, """
                            {"test":"t_test_one_sample","value":"g"}
                            """));
            assertTrue(textValue.getMessage().contains("numeric"), textValue.getMessage());
        }
    }

    @Test
    void chiSquareIgnoresNullCategories() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Utf8"),
                    ColumnSpec.of("g", "Utf8"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"a", "g1"},
                    new Object[]{"a", "g1"},
                    new Object[]{"b", "g1"},
                    new Object[]{"b", "g2"},
                    new Object[]{"b", "g2"},
                    new Object[]{null, "g2"},
                    new Object[]{"a", null}));

            TableData data = run(db, input, """
                    {"test":"chi_square","value":"v","group":"g"}
                    """);

            assertEquals("5", data.data().get(0).get(4));
        }
    }
}
