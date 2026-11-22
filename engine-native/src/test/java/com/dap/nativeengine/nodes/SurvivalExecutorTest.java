package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.Distributions;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dap.survival. The expected Kaplan-Meier curve, its Greenwood log interval and the
 * log-rank statistic are re-derived from the definitions inside the test: the survival is
 * the product of (1 - d/n) over the event times, the interval is exp(log S -/+ z * se)
 * with se = sqrt(sum d/(n(n-d))), and the log-rank statistic is the Mantel-Haenszel
 * (O-E)'V^-1(O-E) for the first k-1 groups. The statistic/p-value assertions also pin
 * lifelines 0.30.3 values frozen in tests/reference/logrank.py (tolerance 1e-9).
 */
class SurvivalExecutorTest {
    /** Same tested primitive the executor uses; pinned by DistributionsTest. */
    private static final double Z = Distributions.normalQuantile(0.975);

    private static final List<ColumnSpec> KM = List.of(
            ColumnSpec.of("time", "Float64"),
            ColumnSpec.of("n_at_risk", "Int64"),
            ColumnSpec.of("events", "Int64"),
            ColumnSpec.of("censored", "Int64"),
            ColumnSpec.of("survival", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"));

    private static final List<ColumnSpec> LOGRANK = List.of(
            ColumnSpec.of("test", "Utf8"),
            ColumnSpec.of("statistic", "Float64"),
            ColumnSpec.of("df", "Int64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("groups", "Int64"));

    private static LazyPlan series(DuckDbSession db, double[] times, int[] events) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            rows.add(new Object[]{times[i], (long) events[i]});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("time", "Float64"),
                ColumnSpec.of("event", "Int64")), rows);
    }

    private static LazyPlan grouped(DuckDbSession db, double[] times, int[] events, String[] groups)
            throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            rows.add(new Object[]{times[i], (long) events[i], groups[i]});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("time", "Float64"),
                ColumnSpec.of("event", "Int64"), ColumnSpec.of("group", "Utf8")), rows);
    }

    private static LazyPlan plan(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return new SurvivalExecutor().execute(NodeTestSupport.context(db, configJson), List.of(input));
    }

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return plan(db, input, configJson).preview(100);
    }

    private static double number(List<String> row, int column) {
        return Double.parseDouble(row.get(column));
    }

    @Test
    void kaplanMeierCurveMatchesTheHandComputedProduct() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, series(db, new double[]{1, 2, 2, 3}, new int[]{1, 1, 0, 1}),
                    "{\"time\":\"time\",\"event\":\"event\"}");
            assertEquals(KM, plan.columns());
            TableData data = plan.preview(100);
            assertEquals(3, data.rows());

            // t=1: 4 at risk, 1 event; t=2: 3 at risk, 1 event and 1 censoring;
            // t=3: the last observation is an event, so S drops to 0.
            assertEquals(1.0, number(data.data().get(0), 0), 1e-12);
            assertEquals("4", data.data().get(0).get(1));
            assertEquals("1", data.data().get(0).get(2));
            assertEquals("0", data.data().get(0).get(3));
            assertEquals(0.75, number(data.data().get(0), 4), 1e-12);

            assertEquals(2.0, number(data.data().get(1), 0), 1e-12);
            assertEquals("3", data.data().get(1).get(1));
            assertEquals("1", data.data().get(1).get(2));
            assertEquals("1", data.data().get(1).get(3));
            assertEquals(0.5, number(data.data().get(1), 4), 1e-12);

            assertEquals(3.0, number(data.data().get(2), 0), 1e-12);
            assertEquals("1", data.data().get(2).get(1));
            assertEquals(0.0, number(data.data().get(2), 4), 1e-12);
            assertEquals(0.0, number(data.data().get(2), 5), 1e-12);
            assertEquals(1.0, number(data.data().get(2), 6), 1e-12);
        }
    }

    @Test
    void greenwoodLogIntervalMatchesTheDefinition() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, series(db, new double[]{1, 2, 2, 3}, new int[]{1, 1, 0, 1}), "{\"time\":\"time\",\"event\":\"event\"}");

            // Running Greenwood sums: t=1 -> 1/(4*3); t=2 -> +1/(3*2).
            double logSurvival = 0.0;
            double variance = 0.0;
            int[][] steps = {{4, 1}, {3, 1}};
            for (int index = 0; index < steps.length; index++) {
                int atRisk = steps[index][0];
                int events = steps[index][1];
                logSurvival += Math.log(1.0 - (double) events / atRisk);
                variance += (double) events / ((double) atRisk * (atRisk - events));
                double expectedLow = Math.exp(logSurvival - Z * Math.sqrt(variance));
                double expectedHigh = Math.min(1.0, Math.exp(logSurvival + Z * Math.sqrt(variance)));
                assertEquals(expectedLow, number(data.data().get(index), 5), 1e-9);
                assertEquals(expectedHigh, number(data.data().get(index), 6), 1e-9);
            }
        }
    }

    @Test
    void groupsGetTheirOwnCurve() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, grouped(db,
                    new double[]{1, 2, 1, 3}, new int[]{1, 1, 1, 1},
                    new String[]{"a", "a", "b", "b"}),
                    "{\"time\":\"time\",\"event\":\"event\",\"group\":\"group\"}");
            assertEquals(List.of(ColumnSpec.of("group", "Utf8"),
                    ColumnSpec.of("time", "Float64"),
                    ColumnSpec.of("n_at_risk", "Int64"),
                    ColumnSpec.of("events", "Int64"),
                    ColumnSpec.of("censored", "Int64"),
                    ColumnSpec.of("survival", "Float64"),
                    ColumnSpec.of("ci_low", "Float64"),
                    ColumnSpec.of("ci_high", "Float64")), plan.columns());

            TableData data = plan.preview(100);
            assertEquals(4, data.rows());
            assertEquals("a", data.data().get(0).get(0));
            assertEquals(0.5, number(data.data().get(0), 5), 1e-12);
            assertEquals(0.0, number(data.data().get(1), 5), 1e-12);
            assertEquals("b", data.data().get(2).get(0));
            assertEquals(0.5, number(data.data().get(2), 5), 1e-12);
            assertEquals(0.0, number(data.data().get(3), 5), 1e-12);
        }
    }

    @Test
    void logRankStatisticMatchesTheMantelHaenszelDefinition() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // a: events at 1,2,3; b: events at 1,2 and a censoring at 4.
            LazyPlan plan = plan(db, grouped(db,
                    new double[]{1, 2, 3, 1, 2, 4}, new int[]{1, 1, 1, 1, 1, 0},
                    new String[]{"a", "a", "a", "b", "b", "b"}), """
                    {"time":"time","event":"event","group":"group","mode":"logrank"}
                    """);
            assertEquals(LOGRANK, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            List<String> row = data.data().get(0);

            // Hand computation over the event times 1, 2, 3:
            //   t=1: n=6 d=2 n_a=3 -> E_a += 2*3/6 = 1;   V_aa += (2*4/5)*(0.5*0.5)
            //   t=2: n=4 d=2 n_a=2 -> E_a += 2*2/4 = 1;   V_aa += (2*2/3)*(0.5*0.5)
            //   t=3: n=2 d=1 n_a=1 -> E_a += 1*1/2 = 0.5; V_aa += (1*1/1)*(0.5*0.5)
            double observed = 3.0;
            double expected = 2.5;
            double variance = 0.4 + 0.3333333333333333 + 0.25;
            double statistic = (observed - expected) * (observed - expected) / variance;
            double pValue = 1.0 - Distributions.chiSquareCdf(statistic, 1);

            assertEquals("logrank", row.get(0));
            assertEquals(statistic, number(row, 1), 1e-9);
            assertEquals("1", row.get(2));
            assertEquals(pValue, number(row, 3), 1e-9);
            assertEquals("2", row.get(4));
            assertTrue(number(row, 3) > 0.5, "the groups do not differ: " + row);
        }
    }

    @Test
    void logRankTwoGroupsMatchesTheLifelinesReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // a: events at 1..5; b: events at 2..6. No censoring.
            LazyPlan plan = plan(db, grouped(db,
                    new double[]{1, 2, 3, 4, 5, 2, 3, 4, 5, 6},
                    new int[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
                    new String[]{"a", "a", "a", "a", "a", "b", "b", "b", "b", "b"}), """
                    {"time":"time","event":"event","group":"group","mode":"logrank"}
                    """);
            List<String> row = plan.preview(10).data().get(0);
            assertEquals(0.9908286871675439, number(row, 1), 1e-9);
            assertEquals("1", row.get(2));
            assertEquals(0.3195399204298757, number(row, 3), 1e-9);
            assertEquals("2", row.get(4));
        }
    }

    @Test
    void logRankCensoringMatchesTheLifelinesReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // b is censored at 4, so it stays in the risk set at the event times 1 and 2.
            LazyPlan plan = plan(db, grouped(db,
                    new double[]{1, 2, 3, 1, 2, 4}, new int[]{1, 1, 1, 1, 1, 0},
                    new String[]{"a", "a", "a", "b", "b", "b"}), """
                    {"time":"time","event":"event","group":"group","mode":"logrank"}
                    """);
            List<String> row = plan.preview(10).data().get(0);
            assertEquals(0.25423728813559315, number(row, 1), 1e-9);
            assertEquals("1", row.get(2));
            assertEquals(0.6141071500553654, number(row, 3), 1e-9);
            assertEquals("2", row.get(4));
        }
    }

    @Test
    void logRankThreeGroupsAccumulateTheOffDiagonalCovariance() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // Group c is censored at 1,2,3,5,7 and never fails; a and b share the
            // event times 1,2,4 so the off-diagonal covariance accumulates over
            // four event times. lifelines and the numpy Mantel-Haenszel oracle both
            // give statistic=4.1432119205298, p=0.125983294989954, df=2.
            LazyPlan plan = plan(db, grouped(db,
                    new double[]{1, 2, 3, 4, 5, 1, 2, 3, 4, 6, 1, 2, 3, 5, 7},
                    new int[]{1, 1, 1, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0},
                    new String[]{"a", "a", "a", "a", "a", "b", "b", "b", "b", "b",
                            "c", "c", "c", "c", "c"}), """
                    {"time":"time","event":"event","group":"group","mode":"logrank"}
                    """);
            List<String> row = plan.preview(10).data().get(0);
            assertEquals(4.1432119205298, number(row, 1), 1e-9);
            assertEquals("2", row.get(2));
            assertEquals(0.12598329498995398, number(row, 3), 1e-9);
            assertEquals("3", row.get(4));
        }
    }

    @Test
    void dateTimesArePreservedInTheOutput() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{LocalDate.of(2024, 1, 1), 1L});
            rows.add(new Object[]{LocalDate.of(2024, 1, 2), 1L});
            rows.add(new Object[]{LocalDate.of(2024, 1, 3), 1L});
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("day", "Date32"),
                    ColumnSpec.of("event", "Int64")), rows);

            LazyPlan plan = plan(db, input, """
                    {"time":"day","event":"event"}
                    """);
            assertEquals("Date32", plan.columns().get(0).arrowType());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals("2024-01-01", data.data().get(0).get(0));
            assertEquals(0.6666666666666666, number(data.data().get(0), 4), 1e-12);
            assertEquals("2024-01-03", data.data().get(2).get(0));
            assertEquals(0.0, number(data.data().get(2), 4), 1e-12);
        }
    }

    @Test
    void booleanEventColumnIsAccepted() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{1.0, true});
            rows.add(new Object[]{2.0, false});
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("t", "Float64"),
                    ColumnSpec.of("event", "Boolean")), rows);

            TableData data = run(db, input, """
                    {"time":"t","event":"event"}
                    """);
            assertEquals(1, data.rows());
            assertEquals(0.5, number(data.data().get(0), 4), 1e-12);
        }
    }

    @Test
    void emptyInputYieldsAnEmptyCurveWithTheContractColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("time", "Float64"),
                    ColumnSpec.of("event", "Int64")), List.of());
            LazyPlan plan = plan(db, input, "{\"time\":\"time\",\"event\":\"event\"}");
            assertEquals(KM, plan.columns());
            assertEquals(0, plan.preview(10).rows());
        }
    }

    @Test
    void kaplanMeierAcceptsNegativeNumericTimes() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, series(db, new double[]{-3, -2, -1}, new int[]{1, 1, 1}),
                    "{\"time\":\"time\",\"event\":\"event\"}");

            assertEquals(3, data.rows());
            assertEquals(-3.0, number(data.data().get(0), 0), 1e-12);
            assertEquals(2.0 / 3.0, number(data.data().get(0), 4), 1e-12);
            assertEquals(-2.0, number(data.data().get(1), 0), 1e-12);
            assertEquals(1.0 / 3.0, number(data.data().get(1), 4), 1e-12);
            assertEquals(-1.0, number(data.data().get(2), 0), 1e-12);
            assertEquals(0.0, number(data.data().get(2), 4), 1e-12);
        }
    }

    @Test
    void datesBefore1970GetNegativeEpochDaysAndTheSameCurve() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{LocalDate.of(1960, 1, 1), 1L});
            rows.add(new Object[]{LocalDate.of(1960, 1, 2), 1L});
            rows.add(new Object[]{LocalDate.of(1960, 1, 3), 1L});
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("day", "Date32"),
                    ColumnSpec.of("event", "Int64")), rows);

            LazyPlan plan = plan(db, input, "{\"time\":\"day\",\"event\":\"event\"}");
            assertEquals("Date32", plan.columns().get(0).arrowType());
            TableData data = plan.preview(10);

            assertEquals(3, data.rows());
            assertEquals("1960-01-01", data.data().get(0).get(0));
            assertEquals(2.0 / 3.0, number(data.data().get(0), 4), 1e-12);
            assertEquals("1960-01-02", data.data().get(1).get(0));
            assertEquals(1.0 / 3.0, number(data.data().get(1), 4), 1e-12);
            assertEquals("1960-01-03", data.data().get(2).get(0));
            assertEquals(0.0, number(data.data().get(2), 4), 1e-12);
        }
    }

    @Test
    void nonFiniteTimesAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("time", "Float64"),
                    ColumnSpec.of("event", "Int64")),
                    List.<Object[]>of(new Object[]{Double.NaN, 1L}, new Object[]{2.0, 0L}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"time\":\"time\",\"event\":\"event\"}"));
            assertTrue(failure.getMessage().contains("finite"), failure.getMessage());
            assertTrue(failure.getMessage().contains("time"), failure.getMessage());
        }
    }

    @Test
    void invalidConfigurationIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = series(db, new double[]{1, 2}, new int[]{1, 0});

            IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"time":"time","event":"event","mode":"cox"}
                            """));
            assertTrue(badMode.getMessage().contains("cox"), badMode.getMessage());

            IllegalArgumentException unknownTime = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"time":"nope","event":"event"}
                            """));
            assertTrue(unknownTime.getMessage().contains("nope"), unknownTime.getMessage());

            IllegalArgumentException nonNumericTime = assertThrows(IllegalArgumentException.class,
                    () -> run(db, NodeTestSupport.table(db, List.of(
                                    ColumnSpec.of("time", "Utf8"), ColumnSpec.of("event", "Int64")),
                            List.<Object[]>of(new Object[]{"a", 1L})), "{\"time\":\"time\",\"event\":\"event\"}"));
            assertTrue(nonNumericTime.getMessage().contains("numeric"), nonNumericTime.getMessage());

            IllegalArgumentException badEvent = assertThrows(IllegalArgumentException.class,
                    () -> run(db, series(db, new double[]{1, 2}, new int[]{1, 2}),
                            "{\"time\":\"time\",\"event\":\"event\"}"));
            assertTrue(badEvent.getMessage().contains("event"), badEvent.getMessage());

            IllegalArgumentException missingGroup = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"time":"time","event":"event","group":"group","mode":"logrank"}
                            """));
            assertTrue(missingGroup.getMessage().contains("group"), missingGroup.getMessage());

            IllegalArgumentException singleGroup = assertThrows(IllegalArgumentException.class,
                    () -> run(db, grouped(db, new double[]{1, 2}, new int[]{1, 1},
                            new String[]{"a", "a"}), """
                            {"time":"time","event":"event","group":"group","mode":"logrank"}
                            """));
            assertTrue(singleGroup.getMessage().contains("group"), singleGroup.getMessage());
        }
    }
}
