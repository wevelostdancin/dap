package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
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
 * dap.time_series. The fixtures are synthetic series with an exact structure, so the
 * expectations are the series definition itself: a linear trend is recovered exactly by
 * the centred moving average, the seasonal indices of a known pattern are recovered by
 * the per-position means, ACF(0) is 1 by definition, and a random walk is not
 * stationary while white noise is.
 */
class TimeSeriesExecutorTest {
    private static LazyPlan series(DuckDbSession db, double[] values) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            rows.add(new Object[]{(double) i, values[i]});
        }
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("t", "Float64"),
                ColumnSpec.of("v", "Float64")), rows);
    }

    private static LazyPlan plan(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return new TimeSeriesExecutor().execute(NodeTestSupport.context(db, configJson), List.of(input));
    }

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        return plan(db, input, configJson).preview(1000);
    }

    private static double number(List<String> row, int column) {
        return Double.parseDouble(row.get(column));
    }

    private static double[] trendAndSeason(int n, double intercept, double slope, double[] seasonal) {
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = intercept + slope * i + seasonal[i % seasonal.length];
        }
        return values;
    }

    @Test
    void additiveDecompositionRecoversTheTrendAndTheSeason() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            double[] values = trendAndSeason(24, 10.0, 0.5, new double[]{0.0, 1.0, 0.0, -1.0});
            LazyPlan plan = plan(db, series(db, values), """
                    {"mode":"decompose","time":"t","value":"v","period":4,"method":"additive"}
                    """);
            assertEquals(List.of(ColumnSpec.of("time", "Float64"),
                    ColumnSpec.of("observed", "Float64"),
                    ColumnSpec.of("trend", "Float64"),
                    ColumnSpec.of("seasonal", "Float64"),
                    ColumnSpec.of("residual", "Float64")), plan.columns());

            TableData data = plan.preview(1000);
            assertEquals(24, data.rows());
            for (int i = 0; i < 24; i++) {
                assertEquals((double) i, number(data.data().get(i), 0), 1e-12);
                assertEquals(values[i], number(data.data().get(i), 1), 1e-12);
            }
            // The centred moving average of a linear function is the function itself.
            for (int i = 2; i <= 21; i++) {
                assertEquals(10.0 + 0.5 * i, number(data.data().get(i), 2), 1e-9);
                assertEquals(0.0, number(data.data().get(i), 4), 1e-9);
            }
            // Edges have no trend, hence no residual.
            assertNull(data.data().get(0).get(2));
            assertNull(data.data().get(0).get(4));
            assertNull(data.data().get(23).get(2));
            // Seasonal indices are normalised to sum to zero.
            assertEquals(0.0, number(data.data().get(4), 3), 1e-9);
            assertEquals(1.0, number(data.data().get(5), 3), 1e-9);
            assertEquals(-1.0, number(data.data().get(7), 3), 1e-9);
        }
    }

    @Test
    void multiplicativeDecompositionRecoversTheFactors() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // A constant level keeps the multiplicative decomposition exact: the centred
            // moving average of 10 * factor is 10 and the residual is 1.
            double[] factors = {1.0, 1.1, 1.0, 0.9};
            double[] values = new double[24];
            for (int i = 0; i < 24; i++) {
                values[i] = 10.0 * factors[i % 4];
            }
            TableData data = run(db, series(db, values), """
                    {"mode":"decompose","time":"t","value":"v","period":4,"method":"multiplicative"}
                    """);
            assertEquals(24, data.rows());
            for (int i = 2; i <= 21; i++) {
                assertEquals(10.0, number(data.data().get(i), 2), 1e-9);
                assertEquals(1.0, number(data.data().get(i), 4), 1e-9);
            }
            assertEquals(1.1, number(data.data().get(5), 3), 1e-9);
            assertEquals(0.9, number(data.data().get(7), 3), 1e-9);
        }
    }

    @Test
    void acfStartsAtOneAndStaysInRange() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            double[] values = new double[40];
            for (int i = 0; i < values.length; i++) {
                values[i] = Math.sin(i * 0.7) + 0.3 * Math.cos(i * 2.1);
            }
            LazyPlan plan = plan(db, series(db, values), """
                    {"mode":"acf","time":"t","value":"v","max_lag":5}
                    """);
            assertEquals(List.of(ColumnSpec.of("lag", "Int64"),
                    ColumnSpec.of("acf", "Float64"),
                    ColumnSpec.of("pacf", "Float64")), plan.columns());

            TableData data = plan.preview(100);
            assertEquals(6, data.rows());
            assertEquals("0", data.data().get(0).get(0));
            assertEquals(1.0, number(data.data().get(0), 1), 1e-12);
            assertEquals(1.0, number(data.data().get(0), 2), 1e-12);
            for (int lag = 0; lag <= 5; lag++) {
                assertEquals(String.valueOf(lag), data.data().get(lag).get(0));
                assertTrue(number(data.data().get(lag), 1) >= -1.0
                        && number(data.data().get(lag), 1) <= 1.0, data.data().get(lag).toString());
                assertTrue(number(data.data().get(lag), 2) >= -1.0
                        && number(data.data().get(lag), 2) <= 1.0, data.data().get(lag).toString());
            }
        }
    }

    @Test
    void lagModeAddsTheRequestedLagColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                rows.add(new Object[]{(double) i, 10.0 * (i + 1)});
            }
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("t", "Float64"),
                    ColumnSpec.of("v", "Float64")), rows);

            LazyPlan plan = plan(db, input, """
                    {"mode":"lag","time":"t","value":"v","max_lag":2}
                    """);
            assertEquals(List.of(ColumnSpec.of("t", "Float64"),
                    ColumnSpec.of("v", "Float64"),
                    ColumnSpec.of("value_lag_1", "Float64"),
                    ColumnSpec.of("value_lag_2", "Float64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(5, data.rows());
            assertNull(data.data().get(0).get(2));
            assertNull(data.data().get(0).get(3));
            assertEquals(10.0, number(data.data().get(1), 2), 1e-12);
            assertNull(data.data().get(1).get(3));
            assertEquals(20.0, number(data.data().get(2), 2), 1e-12);
            assertEquals(10.0, number(data.data().get(2), 3), 1e-12);
        }
    }

    @Test
    void naiveForecastRepeatsTheLastValueWithIntervals() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            // Varying differences keep the one-step residual sd positive, so the interval
            // is non-degenerate.
            double[] values = {1, 2, 4, 7, 11, 16, 22, 29, 37, 46};
            LazyPlan plan = plan(db, series(db, values), """
                    {"mode":"forecast","time":"t","value":"v","method":"naive","horizon":3}
                    """);
            assertEquals(List.of(ColumnSpec.of("time", "Float64"),
                    ColumnSpec.of("forecast", "Float64"),
                    ColumnSpec.of("lower", "Float64"),
                    ColumnSpec.of("upper", "Float64"),
                    ColumnSpec.of("method", "Utf8")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            for (int h = 0; h < 3; h++) {
                List<String> row = data.data().get(h);
                assertEquals(9.0 + h + 1, number(row, 0), 1e-12);
                assertEquals(46.0, number(row, 1), 1e-12);
                assertEquals("naive", row.get(4));
                assertTrue(number(row, 2) < 46.0, row.toString());
                assertTrue(number(row, 3) > 46.0, row.toString());
            }
            assertTrue(number(data.data().get(2), 3) - 46.0
                    > number(data.data().get(0), 3) - 46.0, "intervals widen with the horizon");
        }
    }

    @Test
    void seasonalNaiveRepeatsTheLastSeason() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            double[] values = new double[12];
            for (int i = 0; i < values.length; i++) {
                values[i] = new double[]{1, 2, 3, 4}[i % 4];
            }
            TableData data = run(db, series(db, values), """
                    {"mode":"forecast","time":"t","value":"v","method":"seasonal_naive",
                     "period":4,"horizon":4}
                    """);
            assertEquals(4, data.rows());
            double[] expected = {1, 2, 3, 4};
            for (int h = 0; h < 4; h++) {
                assertEquals(expected[h], number(data.data().get(h), 1), 1e-12);
                assertEquals("seasonal_naive", data.data().get(h).get(4));
            }
        }
    }

    @Test
    void driftForecastExtrapolatesTheAverageSlope() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, series(db, new double[]{1, 3, 5, 7, 9}), """
                    {"mode":"forecast","time":"t","value":"v","method":"drift","horizon":2}
                    """);
            assertEquals(2, data.rows());
            assertEquals(9.0 + 2.0, number(data.data().get(0), 1), 1e-12);
            assertEquals(9.0 + 4.0, number(data.data().get(1), 1), 1e-12);
        }
    }

    @Test
    void randomWalkIsNotStationaryWhileWhiteNoiseIs() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            long seed = 12345L;
            double[] steps = new double[200];
            for (int i = 0; i < steps.length; i++) {
                seed = (seed * 1103515245L + 12345L) & 0x7fffffffL;
                steps[i] = (seed % 200) / 100.0 - 1.0;
            }
            double[] walk = new double[steps.length];
            walk[0] = steps[0];
            for (int i = 1; i < walk.length; i++) {
                walk[i] = walk[i - 1] + steps[i];
            }

            TableData noise = run(db, series(db, steps), """
                    {"mode":"stationarity","time":"t","value":"v"}
                    """);
            List<String> noiseRow = noise.data().get(0);
            assertEquals("adf", noiseRow.get(0));
            assertEquals(-3.43, number(noiseRow, 3), 1e-12);
            assertEquals(-2.86, number(noiseRow, 4), 1e-12);
            assertEquals(-2.57, number(noiseRow, 5), 1e-12);
            assertTrue(number(noiseRow, 1) < -2.86, "white noise should be stationary: " + noiseRow);
            assertTrue("true".equals(noiseRow.get(6)), noiseRow.toString());
            assertTrue(number(noiseRow, 2) < 0.05, noiseRow.toString());

            TableData randomWalk = run(db, series(db, walk), """
                    {"mode":"stationarity","time":"t","value":"v"}
                    """);
            List<String> walkRow = randomWalk.data().get(0);
            assertTrue(number(walkRow, 1) > -2.86, "a random walk is not stationary: " + walkRow);
            assertTrue("false".equals(walkRow.get(6)), walkRow.toString());
            assertTrue(number(walkRow, 2) > 0.05, walkRow.toString());
        }
    }

    @Test
    void groupsGetTheirOwnRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                rows.add(new Object[]{"a", (double) i, 10.0 + i});
                rows.add(new Object[]{"b", (double) i, 20.0 + 2 * i});
            }
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("t", "Float64"), ColumnSpec.of("v", "Float64")), rows);

            LazyPlan plan = plan(db, input, """
                    {"mode":"forecast","time":"t","value":"v","group_by":["g"],"method":"naive",
                     "horizon":2}
                    """);
            assertEquals(List.of(ColumnSpec.of("g", "Utf8"),
                    ColumnSpec.of("time", "Float64"),
                    ColumnSpec.of("forecast", "Float64"),
                    ColumnSpec.of("lower", "Float64"),
                    ColumnSpec.of("upper", "Float64"),
                    ColumnSpec.of("method", "Utf8")), plan.columns());

            TableData data = plan.preview(100);
            assertEquals(4, data.rows());
            assertEquals("a", data.data().get(0).get(0));
            assertEquals(17.0, number(data.data().get(0), 2), 1e-12);
            assertEquals("b", data.data().get(2).get(0));
            assertEquals(34.0, number(data.data().get(2), 2), 1e-12);
        }
    }

    @Test
    void invalidConfigurationIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = series(db, new double[]{1, 2, 3, 4, 5, 6});

            IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"spectral","time":"t","value":"v"}
                            """));
            assertTrue(badMode.getMessage().contains("spectral"), badMode.getMessage());

            IllegalArgumentException missingTime = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"acf","value":"v"}
                            """));
            assertTrue(missingTime.getMessage().contains("time"), missingTime.getMessage());

            IllegalArgumentException unknownValue = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"acf","time":"t","value":"nope"}
                            """));
            assertTrue(unknownValue.getMessage().contains("nope"), unknownValue.getMessage());

            IllegalArgumentException nonNumericValue = assertThrows(IllegalArgumentException.class,
                    () -> run(db, NodeTestSupport.table(db, List.of(ColumnSpec.of("t", "Float64"),
                                    ColumnSpec.of("v", "Utf8")),
                            List.<Object[]>of(new Object[]{1.0, "a"})), """
                            {"mode":"acf","time":"t","value":"v"}
                            """));
            assertTrue(nonNumericValue.getMessage().contains("numeric"),
                    nonNumericValue.getMessage());

            IllegalArgumentException badPeriod = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"decompose","time":"t","value":"v","period":1}
                            """));
            assertTrue(badPeriod.getMessage().contains("period"), badPeriod.getMessage());

            IllegalArgumentException shortSeries = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"decompose","time":"t","value":"v","period":4}
                            """));
            assertTrue(shortSeries.getMessage().contains("period"), shortSeries.getMessage());

            IllegalArgumentException shortAcf = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"acf","time":"t","value":"v","max_lag":10}
                            """));
            assertTrue(shortAcf.getMessage().contains("max_lag"), shortAcf.getMessage());

            IllegalArgumentException badMethod = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, """
                            {"mode":"forecast","time":"t","value":"v","method":"arima"}
                            """));
            assertTrue(badMethod.getMessage().contains("arima"), badMethod.getMessage());

            IllegalArgumentException nonPositive = assertThrows(IllegalArgumentException.class,
                    () -> run(db, series(db, new double[]{1, 0, 3, 4, 5, 6, 7, 8}), """
                            {"mode":"decompose","time":"t","value":"v","period":2,
                             "method":"multiplicative"}
                            """));
            assertTrue(nonPositive.getMessage().contains("positive"), nonPositive.getMessage());
        }
    }
}
