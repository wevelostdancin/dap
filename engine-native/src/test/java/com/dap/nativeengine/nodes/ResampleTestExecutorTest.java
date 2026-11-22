package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dap.resample_test. The expectations are computed independently: the sample mean,
 * median, sd, Pearson correlation and OLS slope of the fixture, the exact permutation
 * difference of the group means, and the analytic p-value of a completely separated
 * two-group split. Bootstrap intervals are checked for coverage and seed determinism,
 * not pinned to a particular random stream.
 */
class ResampleTestExecutorTest {
    private static final List<ColumnSpec> BOOTSTRAP = List.of(
            ColumnSpec.of("statistic", "Utf8"),
            ColumnSpec.of("estimate", "Float64"),
            ColumnSpec.of("se", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"),
            ColumnSpec.of("bias", "Float64"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("resamples", "Int64"),
            ColumnSpec.of("valid_resamples", "Int64"),
            ColumnSpec.of("warning", "Utf8"));

    private static final List<ColumnSpec> PERMUTATION = List.of(
            ColumnSpec.of("statistic", "Utf8"),
            ColumnSpec.of("observed", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("permutations", "Int64"));

    /** Ten observations 1..10: mean 5.5, median 5.5, sample sd sqrt(82.5/9). */
    private static LazyPlan values(DuckDbSession db) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rows.add(new Object[]{(double) i, 2.0 * i + 1.0});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("x", "Float64"),
                ColumnSpec.of("y", "Float64")), rows);
    }

    /** Two groups a = 1..5 and b = 10..14; the observed difference of the means is -9. */
    private static LazyPlan groups(DuckDbSession db) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            rows.add(new Object[]{(double) i, "a"});
        }
        for (int i = 10; i <= 14; i++) {
            rows.add(new Object[]{(double) i, "b"});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Float64"),
                ColumnSpec.of("g", "Utf8")), rows);
    }

    private static LazyPlan plan(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return new ResampleTestExecutor().execute(NodeTestSupport.context(db, configJson), List.of(input));
    }

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return plan(db, input, configJson).preview(10);
    }

    private static double number(List<String> row, int column) {
        return Double.parseDouble(row.get(column));
    }

    @Test
    void bootstrapMeanReportsTheEstimateAndAContainingInterval() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, values(db), """
                    {"mode":"bootstrap","statistic":"mean","columns":["x"],"resamples":1000,"seed":42}
                    """);
            assertEquals(BOOTSTRAP, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            List<String> row = data.data().get(0);

            assertEquals("mean", row.get(0));
            assertEquals(5.5, number(row, 1), 1e-12);
            assertEquals("10", row.get(6));
            assertEquals("1000", row.get(7));
            assertTrue(number(row, 2) > 0.0, row.toString());
            assertTrue(number(row, 3) <= 5.5, row.toString());
            assertTrue(number(row, 4) >= 5.5, row.toString());
            assertTrue(Math.abs(number(row, 5)) < 0.5, "bias should be small: " + row);
        }
    }

    @Test
    void degenerateResamplesAreReportedSeparatelyFromTheConfiguredCount() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // x is constant except for one row: a resample that never draws that row
            // has zero variance and cannot define a slope. The true slope of y on x is 3.
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                rows.add(new Object[]{1.0, (double) i});
            }
            rows.add(new Object[]{2.0, 6.0});
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64")), rows);

            List<String> row = run(db, input, """
                    {"mode":"bootstrap","statistic":"slope","columns":["x","y"],
                     "resamples":200,"seed":42}
                    """).data().get(0);

            assertEquals("200", row.get(7), "resamples stays the configured count");
            int valid = Integer.parseInt(row.get(8));
            assertTrue(valid > 0 && valid < 200, "some draws are degenerate: " + row);
            assertNotNull(row.get(9), "a large skip rate must warn in the result: " + row);
            assertTrue(row.get(9).contains("valid"), row.get(9));
            assertEquals(3.0, number(row, 1), 1e-9);
            assertTrue(Double.isFinite(number(row, 2)), row.toString());
        }
    }

    @Test
    void bootstrapMedianAndSdMatchTheSampleStatistics() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData median = run(db, values(db), """
                    {"mode":"bootstrap","statistic":"median","columns":["x"],"resamples":200}
                    """);
            assertEquals(5.5, number(median.data().get(0), 1), 1e-12);

            TableData sd = run(db, values(db), """
                    {"mode":"bootstrap","statistic":"sd","columns":["x"],"resamples":200}
                    """);
            assertEquals(Math.sqrt(82.5 / 9.0), number(sd.data().get(0), 1), 1e-12);
            assertTrue(number(sd.data().get(0), 2) > 0.0, sd.data().get(0).toString());
        }
    }

    @Test
    void bootstrapCorrelationAndSlopeUseBothColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData correlation = run(db, values(db), """
                    {"mode":"bootstrap","statistic":"correlation","columns":["x","y"],"resamples":100}
                    """);
            assertEquals("correlation", correlation.data().get(0).get(0));
            assertEquals(1.0, number(correlation.data().get(0), 1), 1e-12);
            assertEquals("100", correlation.data().get(0).get(8),
                    "a perfect line has no degenerate resamples (golden fixture)");

            TableData slope = run(db, values(db), """
                    {"mode":"bootstrap","statistic":"slope","columns":["x","y"],"resamples":100}
                    """);
            assertEquals("slope", slope.data().get(0).get(0));
            assertEquals(2.0, number(slope.data().get(0), 1), 1e-12);
            assertEquals(2.0, number(slope.data().get(0), 3), 1e-12);
            assertEquals(2.0, number(slope.data().get(0), 4), 1e-12);
        }
    }

    @Test
    void bootstrapIsDeterministicForASeedAndMovesForAnotherSeed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            String config = """
                    {"mode":"bootstrap","statistic":"mean","columns":["x"],"resamples":500,"seed":7}
                    """;
            TableData first = run(db, values(db), config);
            TableData second = run(db, values(db), config);
            assertEquals(first.data(), second.data());

            TableData other = run(db, values(db), """
                    {"mode":"bootstrap","statistic":"mean","columns":["x"],"resamples":500,"seed":8}
                    """);
            assertNotEquals(first.data().get(0).get(2), other.data().get(0).get(2),
                    "a different seed should give a different standard error");
        }
    }

    @Test
    void nonFiniteRowsAreExcludedFromBootstrapAndPermutation() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                rows.add(new Object[]{(double) i, 2.0 * i + 1.0});
            }
            rows.add(new Object[]{Double.POSITIVE_INFINITY, 100.0});
            LazyPlan dirty = NodeTestSupport.table(db, List.of(ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64")), rows);

            List<String> slope = run(db, dirty, """
                    {"mode":"bootstrap","statistic":"slope","columns":["x","y"],"resamples":100}
                    """).data().get(0);
            assertEquals("10", slope.get(6), "the Infinity row must be dropped");
            assertEquals(2.0, number(slope, 1), 1e-9);
            assertTrue(Double.isFinite(number(slope, 1)), slope.toString());
            assertEquals("100", slope.get(8), "no degenerate resamples when the row is excluded");

            List<Object[]> groupedRows = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                groupedRows.add(new Object[]{(double) i, "a"});
            }
            for (int i = 10; i <= 14; i++) {
                groupedRows.add(new Object[]{(double) i, "b"});
            }
            groupedRows.add(new Object[]{Double.NEGATIVE_INFINITY, "a"});
            LazyPlan dirtyGroups = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Float64"),
                    ColumnSpec.of("g", "Utf8")), groupedRows);
            List<String> permutation = run(db, dirtyGroups, """
                    {"mode":"permutation","statistic":"difference_mean","value":"v","group":"g",
                     "permutations":100,"seed":42}
                    """).data().get(0);
            assertEquals("10", permutation.get(3));
            assertEquals(-9.0, number(permutation, 1), 1e-9);
            assertTrue(Double.isFinite(number(permutation, 2)), permutation.toString());
        }
    }

    @Test
    void permutationSeparatedGroupsAreSignificant() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, groups(db), """
                    {"mode":"permutation","statistic":"difference_mean","value":"v","group":"g",
                     "permutations":1000,"seed":42}
                    """);
            assertEquals(PERMUTATION, plan.columns());
            TableData data = plan.preview(10);
            List<String> row = data.data().get(0);

            assertEquals("difference_mean", row.get(0));
            assertEquals(-9.0, number(row, 1), 1e-12);
            assertEquals("10", row.get(3));
            assertEquals("1000", row.get(4));
            assertTrue(number(row, 2) < 0.05, "two-sided p should be significant: " + row);
        }
    }

    @Test
    void permutationAlternativeSelectsTheTail() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData less = run(db, groups(db), """
                    {"mode":"permutation","statistic":"difference_mean","value":"v","group":"g",
                     "permutations":1000,"seed":42,"alternative":"less"}
                    """);
            assertTrue(number(less.data().get(0), 2) < 0.05, less.data().get(0).toString());

            TableData greater = run(db, groups(db), """
                    {"mode":"permutation","statistic":"difference_mean","value":"v","group":"g",
                     "permutations":1000,"seed":42,"alternative":"greater"}
                    """);
            assertTrue(number(greater.data().get(0), 2) > 0.95, greater.data().get(0).toString());
        }
    }

    @Test
    void permutationOnIdenticalGroupsHasPValueOne() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                rows.add(new Object[]{(double) i, "a"});
                rows.add(new Object[]{(double) i, "b"});
            }
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Float64"),
                    ColumnSpec.of("g", "Utf8")), rows);

            TableData data = run(db, input, """
                    {"mode":"permutation","statistic":"difference_mean","value":"v","group":"g",
                     "permutations":500,"seed":42}
                    """);
            List<String> row = data.data().get(0);
            assertEquals(0.0, number(row, 1), 1e-12);
            assertEquals(1.0, number(row, 2), 1e-12);
        }
    }

    @Test
    void permutationMedianDifferenceUsesTheMedian() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, groups(db), """
                    {"mode":"permutation","statistic":"difference_median","value":"v","group":"g",
                     "permutations":500,"seed":3}
                    """);
            List<String> row = data.data().get(0);
            assertEquals("difference_median", row.get(0));
            assertEquals(-9.0, number(row, 1), 1e-12);
            assertTrue(number(row, 2) < 0.05, row.toString());
        }
    }

    @Test
    void invalidConfigurationIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = values(db);

            IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"jackknife","statistic":"mean","columns":["x"]}
                            """));
            assertTrue(badMode.getMessage().contains("jackknife"), badMode.getMessage());

            IllegalArgumentException badStatistic = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"bootstrap","statistic":"difference_mean","columns":["x"]}
                            """));
            assertTrue(badStatistic.getMessage().contains("difference_mean"), badStatistic.getMessage());

            IllegalArgumentException missingColumns = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"bootstrap","statistic":"mean"}
                            """));
            assertTrue(missingColumns.getMessage().contains("columns"), missingColumns.getMessage());

            IllegalArgumentException oneColumnForCorrelation = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"bootstrap","statistic":"correlation","columns":["x"]}
                            """));
            assertTrue(oneColumnForCorrelation.getMessage().contains("two columns"),
                    oneColumnForCorrelation.getMessage());

            IllegalArgumentException unknownColumn = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"bootstrap","statistic":"mean","columns":["nope"]}
                            """));
            assertTrue(unknownColumn.getMessage().contains("nope"), unknownColumn.getMessage());

            IllegalArgumentException badConfidence = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"bootstrap","statistic":"mean","columns":["x"],"confidence":1.5}
                            """));
            assertTrue(badConfidence.getMessage().contains("confidence"), badConfidence.getMessage());

            IllegalArgumentException badSeed = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"bootstrap","statistic":"mean","columns":["x"],"seed":1.5}
                            """));
            assertTrue(badSeed.getMessage().contains("seed"), badSeed.getMessage());
        }
    }

    @Test
    void permutationNeedsExactlyTwoGroupsAndAValueColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{1.0, "a"});
            rows.add(new Object[]{2.0, "b"});
            rows.add(new Object[]{3.0, "c"});
            LazyPlan threeGroups = NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Float64"),
                    ColumnSpec.of("g", "Utf8")), rows);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(db, threeGroups, """
                            {"mode":"permutation","statistic":"difference_mean","value":"v","group":"g"}
                            """));
            assertTrue(failure.getMessage().contains("two groups"), failure.getMessage());

            IllegalArgumentException noValue = assertThrows(IllegalArgumentException.class,
                    () -> run(db, groups(db), """
                            {"mode":"permutation","statistic":"difference_mean","group":"g"}
                            """));
            assertTrue(noValue.getMessage().contains("value"), noValue.getMessage());
        }
    }

    @Test
    void maxRowsCapsTheSampleAndReportsTheLimit() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> run(db, values(db), """
                            {"mode":"bootstrap","statistic":"mean","columns":["x"],"max_rows":5}
                            """));
            assertTrue(failure.getMessage().contains("max_rows"), failure.getMessage());
        }
    }

    @Test
    void boundarySamplesAreRejectedWithTheReason() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan single = NodeTestSupport.table(db, List.of(ColumnSpec.of("x", "Float64")),
                    List.<Object[]>of(new Object[]{1.0}));

            IllegalArgumentException sd = assertThrows(IllegalArgumentException.class,
                    () -> run(db, single, """
                            {"mode":"bootstrap","statistic":"sd","columns":["x"]}
                            """));
            assertTrue(sd.getMessage().contains("at least 2"), sd.getMessage());

            IllegalArgumentException correlation = assertThrows(IllegalArgumentException.class,
                    () -> run(db, single, """
                            {"mode":"bootstrap","statistic":"correlation","columns":["x","x"]}
                            """));
            assertTrue(correlation.getMessage().contains("distinct"), correlation.getMessage());

            LazyPlan constant = NodeTestSupport.table(db, List.of(ColumnSpec.of("x", "Float64"),
                            ColumnSpec.of("y", "Float64")),
                    List.<Object[]>of(new Object[]{1.0, 2.0}, new Object[]{1.0, 3.0},
                            new Object[]{1.0, 4.0}));
            IllegalArgumentException slope = assertThrows(IllegalArgumentException.class,
                    () -> run(db, constant, """
                            {"mode":"bootstrap","statistic":"slope","columns":["x","y"]}
                            """));
            assertTrue(slope.getMessage().contains("variance"), slope.getMessage());
        }
    }
}
