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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dap.ab_test. The expectations are the two-proportion z-test and the Fleiss sample-size
 * formula, re-derived inside the test from the definitions (the normal CDF and quantile
 * are the already pinned {@link Distributions} primitives).
 */
class AbTestExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("control_rate", "Float64"),
            ColumnSpec.of("treatment_rate", "Float64"),
            ColumnSpec.of("lift", "Float64"),
            ColumnSpec.of("z_statistic", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("power", "Float64"),
            ColumnSpec.of("required_n_per_group", "Int64"),
            ColumnSpec.of("mde", "Float64"),
            ColumnSpec.of("alpha", "Float64"));

    /** n observations per group, the first successes[i] of them converted. */
    private static LazyPlan conversions(DuckDbSession db, int[] sizes, int[] successes) throws Exception {
        String[] names = {"control", "treatment"};
        List<Object[]> rows = new ArrayList<>();
        for (int group = 0; group < 2; group++) {
            for (int i = 0; i < sizes[group]; i++) {
                rows.add(new Object[]{i < successes[group] ? 1.0 : 0.0, names[group]});
            }
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("converted", "Float64"),
                ColumnSpec.of("variant", "Utf8")), rows);
    }

    private static LazyPlan plan(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return new AbTestExecutor().execute(NodeTestSupport.context(db, configJson), List.of(input));
    }

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return plan(db, input, configJson).preview(10);
    }

    private static double number(List<String> row, int column) {
        return Double.parseDouble(row.get(column));
    }

    private static double requiredPerGroup(double controlRate, double treatmentRate, double alpha,
                                           double power) {
        double pooled = (controlRate + treatmentRate) / 2.0;
        double zAlpha = Distributions.normalQuantile(1.0 - alpha / 2.0);
        double zPower = Distributions.normalQuantile(power);
        double numerator = zAlpha * Math.sqrt(2.0 * pooled * (1.0 - pooled))
                + zPower * Math.sqrt(controlRate * (1.0 - controlRate)
                + treatmentRate * (1.0 - treatmentRate));
        double difference = treatmentRate - controlRate;
        return Math.ceil(numerator * numerator / (difference * difference));
    }

    @Test
    void knownRatesMatchTheTwoProportionZTest() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, conversions(db, new int[]{100, 100}, new int[]{10, 20}), """
                    {"group":"variant","value":"converted"}
                    """);
            assertEquals(COLUMNS, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            List<String> row = data.data().get(0);

            double control = 0.10;
            double treatment = 0.20;
            double pooled = 0.15;
            double standardError = Math.sqrt(pooled * (1.0 - pooled) * (1.0 / 100 + 1.0 / 100));
            double z = (treatment - control) / standardError;
            double pValue = 2.0 * (1.0 - Distributions.normalCdf(z));

            assertEquals(control, number(row, 0), 1e-12);
            assertEquals(treatment, number(row, 1), 1e-12);
            assertEquals(1.0, number(row, 2), 1e-12);
            assertEquals(z, number(row, 3), 1e-9);
            assertEquals(pValue, number(row, 4), 1e-9);
            assertEquals(0.05, number(row, 8), 1e-12);
            // Known values: z = 1.9803, p = 0.0477.
            assertEquals(1.9803, number(row, 3), 1e-3);
            assertEquals(0.0477, number(row, 4), 1e-3);
            assertTrue(number(row, 5) > 0.0 && number(row, 5) < 1.0, row.toString());
        }
    }

    @Test
    void requiredSampleSizeMatchesTheFleissFormula() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, conversions(db, new int[]{100, 100}, new int[]{10, 20}), """
                    {"group":"variant","value":"converted","mde":0.2,"baseline":0.10}
                    """);
            List<String> row = data.data().get(0);
            double expected = requiredPerGroup(0.10, 0.12, 0.05, 0.8);
            assertEquals(expected, number(row, 6), 1e-9);
            assertEquals(3841.0, expected, 1e-9);
            assertEquals(0.2, number(row, 7), 1e-12);
        }
    }

    @Test
    void achievedPowerGrowsWithTheSampleSize() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData small = run(db, conversions(db, new int[]{100, 100}, new int[]{10, 20}), """
                    {"group":"variant","value":"converted"}
                    """);
            TableData large = run(db, conversions(db, new int[]{400, 400}, new int[]{40, 80}), """
                    {"group":"variant","value":"converted"}
                    """);
            assertTrue(number(large.data().get(0), 5) > number(small.data().get(0), 5),
                    "power should grow with n: " + small.data().get(0) + " vs " + large.data().get(0));
        }
    }

    @Test
    void achievedPowerGrowsWithTheLift() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData smallLift = run(db, conversions(db, new int[]{100, 100}, new int[]{10, 11}), """
                    {"group":"variant","value":"converted"}
                    """);
            TableData largeLift = run(db, conversions(db, new int[]{100, 100}, new int[]{10, 20}), """
                    {"group":"variant","value":"converted"}
                    """);
            assertTrue(number(largeLift.data().get(0), 5) > number(smallLift.data().get(0), 5),
                    "power should grow with the lift: " + smallLift.data().get(0) + " vs "
                            + largeLift.data().get(0));
        }
    }

    @Test
    void booleanConversionsAreAccepted() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                rows.add(new Object[]{i < 5, "a"});
                rows.add(new Object[]{i < 10, "b"});
            }
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("paid", "Boolean"),
                    ColumnSpec.of("variant", "Utf8")), rows);

            TableData data = run(db, input, """
                    {"group":"variant","value":"paid"}
                    """);
            List<String> row = data.data().get(0);
            assertEquals(0.1, number(row, 0), 1e-12);
            assertEquals(0.2, number(row, 1), 1e-12);
        }
    }

    @Test
    void missingMdeUsesTheObservedLift() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, conversions(db, new int[]{100, 100}, new int[]{10, 20}), """
                    {"group":"variant","value":"converted"}
                    """);
            List<String> row = data.data().get(0);
            assertEquals(1.0, number(row, 7), 1e-12);
            double expected = requiredPerGroup(0.10, 0.20, 0.05, 0.8);
            assertEquals(expected, number(row, 6), 1e-9);
        }
    }

    @Test
    void invalidConfigurationIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = conversions(db, new int[]{10, 10}, new int[]{1, 2});

            IllegalArgumentException threeGroups = assertThrows(IllegalArgumentException.class,
                    () -> run(db, NodeTestSupport.table(db, List.of(
                                    ColumnSpec.of("v", "Float64"), ColumnSpec.of("g", "Utf8")),
                            List.<Object[]>of(new Object[]{1.0, "a"}, new Object[]{0.0, "b"},
                                    new Object[]{1.0, "c"})), """
                            {"group":"g","value":"v"}
                            """));
            assertTrue(threeGroups.getMessage().contains("two groups"), threeGroups.getMessage());

            IllegalArgumentException unknownValue = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"group":"variant","value":"nope"}
                            """));
            assertTrue(unknownValue.getMessage().contains("nope"), unknownValue.getMessage());

            IllegalArgumentException nonBinary = assertThrows(IllegalArgumentException.class,
                    () -> run(db, NodeTestSupport.table(db, List.of(
                                    ColumnSpec.of("v", "Float64"), ColumnSpec.of("g", "Utf8")),
                            List.<Object[]>of(new Object[]{2.0, "a"}, new Object[]{1.0, "b"})), """
                            {"group":"g","value":"v"}
                            """));
            assertTrue(nonBinary.getMessage().contains("0/1"), nonBinary.getMessage());

            IllegalArgumentException badAlpha = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"group":"variant","value":"converted","alpha":1.5}
                            """));
            assertTrue(badAlpha.getMessage().contains("alpha"), badAlpha.getMessage());

            IllegalArgumentException badPower = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"group":"variant","value":"converted","power":1.5}
                            """));
            assertTrue(badPower.getMessage().contains("power"), badPower.getMessage());

            IllegalArgumentException badMde = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"group":"variant","value":"converted","mde":0}
                            """));
            assertTrue(badMde.getMessage().contains("mde"), badMde.getMessage());

            IllegalArgumentException badBaseline = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"group":"variant","value":"converted","baseline":1.0}
                            """));
            assertTrue(badBaseline.getMessage().contains("baseline"), badBaseline.getMessage());
        }
    }
}
