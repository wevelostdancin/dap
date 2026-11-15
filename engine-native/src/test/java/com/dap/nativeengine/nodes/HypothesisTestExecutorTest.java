package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dap.hypothesis_test. Reference statistics and p-values are R values (t.test, chisq.test,
 * aov); the p-values are pinned in DistributionsTest, here they are asserted end to end
 * with tolerance 1e-6.
 */
class HypothesisTestExecutorTest {
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("test", "Utf8"),
            ColumnSpec.of("statistic", "Float64"),
            ColumnSpec.of("df", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("n", "Int64"));

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        NodeContext context = NodeTestSupport.context(db, configJson);
        LazyPlan plan = new HypothesisTestExecutor().execute(context, List.of(input));
        assertEquals(OUTPUT, plan.columns());
        return plan.preview(10);
    }

    private static double number(TableData data, int column) {
        return Double.parseDouble(data.data().get(0).get(column));
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

    @Test
    void oneSampleTTestMatchesR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, oneSampleTable(db), """
                    {"test":"t_test_one_sample","value":"v","mu":0}
                    """);

            assertEquals(1, data.rows());
            assertEquals("t_test_one_sample", data.data().get(0).get(0));
            assertEquals(5.744562646538029, number(data, 1), 1e-6);
            assertEquals(9.0, number(data, 2), 1e-6);
            assertEquals(0.0002781960111004622, number(data, 3), 1e-6);
            assertEquals("10", data.data().get(0).get(4));
        }
    }

    @Test
    void oneSampleAlternativesUseTheRequestedTail() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData greater = run(db, oneSampleTable(db), """
                    {"test":"t_test_one_sample","value":"v","mu":0,"alternative":"greater"}
                    """);
            assertEquals(0.0001390980055502, number(greater, 3), 1e-6);

            TableData less = run(db, oneSampleTable(db), """
                    {"test":"t_test_one_sample","value":"v","mu":0,"alternative":"less"}
                    """);
            assertEquals(0.9998609019944498, number(less, 3), 1e-6);
        }
    }

    @Test
    void twoSampleWelchTTestMatchesR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"A", 1.0},
                    new Object[]{"A", 2.0},
                    new Object[]{"A", 3.0},
                    new Object[]{"A", 4.0},
                    new Object[]{"A", 5.0},
                    new Object[]{"B", 2.0},
                    new Object[]{"B", 4.0},
                    new Object[]{"B", 6.0},
                    new Object[]{"B", 8.0},
                    new Object[]{"B", 10.0}));

            TableData data = run(db, input, """
                    {"test":"t_test_two_sample","value":"v","group":"grp","group_a":"A","group_b":"B"}
                    """);

            assertEquals(-1.8973665961010275, number(data, 1), 1e-6);
            assertEquals(5.882352941176471, number(data, 2), 1e-6);
            assertEquals(0.10753119493061747, number(data, 3), 1e-6);
            assertEquals("10", data.data().get(0).get(4));
        }
    }

    @Test
    void twoSampleWithoutGroupLabelsUsesTheTwoDistinctGroups() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("grp", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"A", 1.0},
                    new Object[]{"A", 2.0},
                    new Object[]{"B", 3.0},
                    new Object[]{"B", 4.0}));

            TableData data = run(db, input, """
                    {"test":"t_test_two_sample","value":"v","group":"grp"}
                    """);

            assertEquals(1, data.rows());
            assertEquals("4", data.data().get(0).get(4));
            // R: t.test(c(1,2), c(3,4)) -> t = -2.828427, df = 2, p = 0.1055728
            assertEquals(-2.8284271247461903, number(data, 1), 1e-6);
            assertEquals(2.0, number(data, 2), 1e-6);
            assertEquals(0.10557280900008414, number(data, 3), 1e-6);
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

            assertEquals(-1.5, number(data, 1), 1e-6);
            assertEquals(4.0, number(data, 2), 1e-6);
            assertEquals(0.208, number(data, 3), 1e-6);
            assertEquals("5", data.data().get(0).get(4));
        }
    }

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

            assertEquals(6.666666666666667, number(data, 1), 1e-6);
            assertEquals(1.0, number(data, 2), 1e-6);
            assertEquals(0.009823274488416911, number(data, 3), 1e-6);
            assertEquals("60", data.data().get(0).get(4));
        }
    }

    @Test
    void oneWayAnovaMatchesR() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"g1", 1.0},
                    new Object[]{"g1", 2.0},
                    new Object[]{"g1", 3.0},
                    new Object[]{"g2", 4.0},
                    new Object[]{"g2", 5.0},
                    new Object[]{"g2", 6.0},
                    new Object[]{"g3", 7.0},
                    new Object[]{"g3", 8.0},
                    new Object[]{"g3", 9.0}));

            TableData data = run(db, input, """
                    {"test":"anova","value":"v","group":"g"}
                    """);

            assertEquals(27.0, number(data, 1), 1e-6);
            assertEquals(2.0, number(data, 2), 1e-6);
            assertEquals(0.001, number(data, 3), 1e-6);
            assertEquals("9", data.data().get(0).get(4));
        }
    }

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
            assertEquals(2.0, number(data, 2), 1e-9);
            assertEquals("3", data.data().get(0).get(4));
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

            List<ColumnSpec> grouped = List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan small = NodeTestSupport.table(db, grouped, List.of(
                    new Object[]{"A", 1.0},
                    new Object[]{"B", 1.0},
                    new Object[]{"B", 2.0}));
            IllegalArgumentException twoSample = assertThrows(IllegalArgumentException.class,
                    () -> run(db, small, """
                            {"test":"t_test_two_sample","value":"v","group":"g"}
                            """));
            assertTrue(twoSample.getMessage().contains("at least 2"), twoSample.getMessage());

            LazyPlan oneGroup = NodeTestSupport.table(db, grouped, List.of(
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
                            {"test":"mann_whitney","value":"v"}
                            """));
            assertTrue(unknownTest.getMessage().contains("mann_whitney"), unknownTest.getMessage());
            assertTrue(unknownTest.getMessage().contains("anova"), unknownTest.getMessage());

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

            List<ColumnSpec> grouped = List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("v", "Float64"));
            LazyPlan groups = NodeTestSupport.table(db, grouped, List.of(
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
