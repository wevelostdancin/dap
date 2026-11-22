package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.Distributions;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.Matrix;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * dap.time_series: decomposition, ACF/PACF, lags, forecasts and the ADF stationarity test.
 *
 * <p>Every mode orders the rows by {@code time} inside each {@code group_by} partition.
 * The Java modes read at most {@code max_rows} rows and load the result back as a DuckDB
 * relation (a decomposition has one row per observation); the lag mode stays a lazy SQL
 * plan with {@code LAG(...) OVER (...)}.</p>
 *
 * <ul>
 *   <li><b>decompose</b> - centred moving average of order {@code period} (2 x period for
 *       an even period), per-position means of the detrended series normalised to sum to 0
 *       (additive) or to average 1 (multiplicative), residual = observed - trend - seasonal
 *       (or observed / (trend * seasonal)); edges without a full window are NULL.</li>
 *   <li><b>acf</b> - autocorrelations r_k = sum (y_t - mean)(y_t+k - mean) / sum (y - mean)^2
 *       for k = 0..max_lag and the partial autocorrelations from the Durbin-Levinson
 *       recursion.</li>
 *   <li><b>lag</b> - the input columns plus value_lag_1..value_lag_max_lag.</li>
 *   <li><b>forecast</b> - naive, seasonal_naive, mean, drift or additive Holt-Winters
 *       (fixed smoothing 0.3/0.1/0.1) for {@code horizon} steps; the interval is
 *       forecast +/- z(confidence) * sigma * sqrt(h) with sigma the standard deviation of
 *       the one-step in-sample residuals (documented approximation).</li>
 *   <li><b>stationarity</b> - augmented Dickey-Fuller with a constant and one lagged
 *       difference; the p-value is a normal approximation fitted to the MacKinnon
 *       large-sample critical values (tau 0.10 = -2.57, 0.05 = -2.86, 0.01 = -3.43).</li>
 * </ul>
 */
public final class TimeSeriesExecutor implements NodeExecutor {
    private static final String NODE = "time_series";
    private static final List<String> MODES =
            List.of("decompose", "acf", "lag", "forecast", "stationarity");
    private static final List<String> DECOMPOSE_METHODS = List.of("additive", "multiplicative");
    private static final List<String> FORECAST_METHODS =
            List.of("naive", "seasonal_naive", "mean", "drift", "holt_winters");
    private static final int DEFAULT_PERIOD = 12;
    private static final int DEFAULT_MAX_LAG = 20;
    private static final int DEFAULT_HORIZON = 12;
    private static final double DEFAULT_CONFIDENCE = 0.95;
    private static final int DEFAULT_MAX_ROWS = 100_000;
    private static final int MAX_ROWS_LIMIT = 10_000_000;
    private static final int MAX_LAG_LIMIT = 1_000;
    /** Fixed additive Holt-Winters smoothing constants (documented, no tuning). */
    private static final double HW_ALPHA = 0.3;
    private static final double HW_BETA = 0.1;
    private static final double HW_GAMMA = 0.1;
    /** MacKinnon large-sample critical values for the ADF tau statistic with a constant. */
    private static final double CRITICAL_1 = -3.43;
    private static final double CRITICAL_5 = -2.86;
    private static final double CRITICAL_10 = -2.57;
    /**
     * Normal approximation fitted to those three critical values:
     * Phi((tau - MU) / SIGMA) maps -2.57 -> 0.10, -2.86 -> 0.05 and -3.43 -> 0.009.
     */
    private static final double ADF_MU = -1.54704;
    private static final double ADF_SIGMA = 0.79823;

    @Override
    public String nodeId() {
        return "dap.time_series";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String mode = required(config, "mode").trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException(NODE + ": unknown mode '" + mode
                    + "' (expected decompose, acf, lag, forecast or stationarity)");
        }
        TimeAxis time = timeAxis(config, input);
        ColumnSpec value = valueColumn(config, input);
        List<ColumnSpec> groups = groupColumns(config, input);
        int maxRows = positiveInt(config, "max_rows", DEFAULT_MAX_ROWS, MAX_ROWS_LIMIT);
        if ("lag".equals(mode)) {
            int maxLag = positiveInt(config, "max_lag", DEFAULT_MAX_LAG, MAX_LAG_LIMIT);
            return lag(context, input, time, value, groups, maxLag);
        }
        List<List<Point>> buckets = readPoints(context, input, time, value, groups, maxRows);
        return switch (mode) {
            case "decompose" -> decompose(context, buckets, time, groups, config);
            case "acf" -> acf(context, buckets, groups, config);
            case "forecast" -> forecast(context, buckets, time, groups, config);
            default -> stationarity(context, buckets, groups);
        };
    }

    // ------------------------------------------------------------------ data

    /** The time column plus how it is read and written back. */
    private record TimeAxis(ColumnSpec column, boolean date, ColumnSpec output) {
    }

    /** One ordered observation: the group key texts, the numeric time and the value. */
    private record Point(String[] groups, double time, double value) {
    }

    private static TimeAxis timeAxis(JsonNode config, LazyPlan input) {
        String name = required(config, "time");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown 'time' column '" + name + "'");
        }
        if ("Date32".equals(column.arrowType())) {
            return new TimeAxis(column, true, ColumnSpec.of("time", "Date32"));
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException(NODE + ": 'time' column '" + name
                    + "' must be numeric or a date, got " + column.arrowType());
        }
        return new TimeAxis(column, false, ColumnSpec.of("time", "Float64"));
    }

    private static ColumnSpec valueColumn(JsonNode config, LazyPlan input) {
        String name = required(config, "value");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown 'value' column '" + name + "'");
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException(NODE + ": 'value' column '" + name
                    + "' must be numeric, got " + column.arrowType());
        }
        return column;
    }

    private static List<ColumnSpec> groupColumns(JsonNode config, LazyPlan input) {
        JsonNode array = config.get("group_by");
        List<ColumnSpec> columns = new ArrayList<>();
        if (array == null || array.isNull()) {
            return columns;
        }
        if (!array.isArray()) {
            throw new IllegalArgumentException(NODE + ": 'group_by' must be an array of column names");
        }
        for (JsonNode item : array) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(NODE + ": 'group_by' must contain non-empty names");
            }
            String name = item.textValue();
            try {
                columns.add(NodeSql.column(input.columns(), name));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(NODE + ": unknown 'group_by' column '" + name + "'");
            }
        }
        return columns;
    }

    /** Read the series rows, ordered by the group key and then by time inside each bucket. */
    private static List<List<Point>> readPoints(NodeContext context, LazyPlan input,
                                                TimeAxis time, ColumnSpec value,
                                                List<ColumnSpec> groups, int maxRows)
            throws SQLException {
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec group : groups) {
            projection.add("CAST(" + NodeSql.quote(group.name()) + " AS VARCHAR)");
        }
        projection.add(time.date()
                ? "CAST(" + NodeSql.quote(time.column().name()) + " AS VARCHAR)"
                : "CAST(" + NodeSql.quote(time.column().name()) + " AS DOUBLE)");
        projection.add("CAST(" + NodeSql.quote(value.name()) + " AS DOUBLE)");
        StringJoiner where = new StringJoiner(" AND ");
        where.add(NodeSql.quote(time.column().name()) + " IS NOT NULL");
        where.add(NodeSql.quote(value.name()) + " IS NOT NULL");
        for (ColumnSpec group : groups) {
            where.add(NodeSql.quote(group.name()) + " IS NOT NULL");
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + where + " LIMIT " + (maxRows + 1);
        List<Point> points = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                String[] keys = new String[groups.size()];
                for (int index = 0; index < keys.length; index++) {
                    keys[index] = result.getString(index + 1);
                }
                int position = groups.size() + 1;
                double at = time.date()
                        ? LocalDate.parse(result.getString(position)).toEpochDay()
                        : result.getDouble(position);
                points.add(new Point(keys, at, result.getDouble(position + 1)));
            }
        }
        if (points.size() > maxRows) {
            throw new IllegalArgumentException(NODE + ": the series is read in memory and needs at "
                    + "most max_rows = " + maxRows + " rows, got more; raise 'max_rows' or "
                    + "pre-aggregate the input");
        }
        points.sort((left, right) -> {
            int compared = compareGroups(left.groups(), right.groups());
            return compared != 0 ? compared : Double.compare(left.time(), right.time());
        });
        List<List<Point>> buckets = new ArrayList<>();
        List<Point> current = null;
        for (Point point : points) {
            if (current == null || compareGroups(current.get(0).groups(), point.groups()) != 0) {
                current = new ArrayList<>();
                buckets.add(current);
            }
            current.add(point);
        }
        return buckets;
    }

    private static int compareGroups(String[] left, String[] right) {
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            int compared = left[index].compareTo(right[index]);
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    // ------------------------------------------------------------- decompose

    private static LazyPlan decompose(NodeContext context, List<List<Point>> buckets,
                                      TimeAxis time, List<ColumnSpec> groups, JsonNode config)
            throws SQLException {
        int period = positiveInt(config, "period", DEFAULT_PERIOD, 1_000_000);
        if (period < 2) {
            throw new IllegalArgumentException(NODE + ": 'period' must be at least 2");
        }
        String method = NodeSql.text(config, "method", "additive").trim().toLowerCase(Locale.ROOT);
        if (!DECOMPOSE_METHODS.contains(method)) {
            throw new IllegalArgumentException(NODE + ": unknown decompose method '" + method
                    + "' (expected additive or multiplicative)");
        }
        boolean multiplicative = "multiplicative".equals(method);
        List<ColumnSpec> columns = new ArrayList<>(groups);
        columns.add(time.output());
        columns.add(ColumnSpec.of("observed", "Float64"));
        columns.add(ColumnSpec.of("trend", "Float64"));
        columns.add(ColumnSpec.of("seasonal", "Float64"));
        columns.add(ColumnSpec.of("residual", "Float64"));

        List<Object[]> rows = new ArrayList<>();
        for (List<Point> bucket : buckets) {
            int n = bucket.size();
            if (n < 2 * period) {
                throw new IllegalArgumentException(NODE + ": decompose with period " + period
                        + " needs at least " + (2 * period) + " rows per group, got " + n);
            }
            double[] values = new double[n];
            for (int index = 0; index < n; index++) {
                values[index] = bucket.get(index).value();
                if (multiplicative && !(values[index] > 0.0)) {
                    throw new IllegalArgumentException(NODE + ": multiplicative decompose needs "
                            + "strictly positive values, got " + values[index]);
                }
            }
            double[] trend = movingAverage(values, period);
            double[] seasonal = seasonalIndices(values, trend, period, multiplicative);
            for (int index = 0; index < n; index++) {
                Point point = bucket.get(index);
                Object[] row = new Object[columns.size()];
                int column = 0;
                for (int group = 0; group < groups.size(); group++) {
                    row[column++] = groupValue(groups.get(group), point.groups()[group]);
                }
                row[column++] = timeValue(time, point.time());
                row[column++] = values[index];
                boolean defined = !Double.isNaN(trend[index]);
                double factor = seasonal[index % period];
                row[column++] = defined ? trend[index] : null;
                row[column++] = factor;
                row[column++] = defined
                        ? (multiplicative ? values[index] / (trend[index] * factor)
                        : values[index] - trend[index] - factor)
                        : null;
                rows.add(row);
            }
        }
        return loadPlan(context, columns, rows);
    }

    /** Centred moving average; NaN where a full window does not fit. */
    private static double[] movingAverage(double[] values, int period) {
        int n = values.length;
        double[] trend = new double[n];
        Arrays.fill(trend, Double.NaN);
        int half = period / 2;
        if (period % 2 == 1) {
            for (int index = half; index + half < n; index++) {
                double sum = 0.0;
                for (int offset = -half; offset <= half; offset++) {
                    sum += values[index + offset];
                }
                trend[index] = sum / period;
            }
        } else {
            for (int index = half; index + half < n; index++) {
                double sum = 0.5 * values[index - half] + 0.5 * values[index + half];
                for (int offset = -half + 1; offset < half; offset++) {
                    sum += values[index + offset];
                }
                trend[index] = sum / period;
            }
        }
        return trend;
    }

    /** Per-position means of the detrended series, normalised to sum to 0 or average to 1. */
    private static double[] seasonalIndices(double[] values, double[] trend, int period,
                                            boolean multiplicative) {
        double[] sums = new double[period];
        int[] counts = new int[period];
        for (int index = 0; index < values.length; index++) {
            if (Double.isNaN(trend[index])) {
                continue;
            }
            int position = index % period;
            sums[position] += multiplicative ? values[index] / trend[index]
                    : values[index] - trend[index];
            counts[position]++;
        }
        double[] indices = new double[period];
        for (int position = 0; position < period; position++) {
            indices[position] = counts[position] == 0
                    ? (multiplicative ? 1.0 : 0.0)
                    : sums[position] / counts[position];
        }
        double mean = 0.0;
        for (double index : indices) {
            mean += index;
        }
        mean /= period;
        for (int position = 0; position < period; position++) {
            if (multiplicative) {
                if (mean == 0.0) {
                    throw new IllegalArgumentException(NODE + ": the multiplicative seasonal "
                            + "indices average to zero");
                }
                indices[position] /= mean;
            } else {
                indices[position] -= mean;
            }
        }
        return indices;
    }

    // ------------------------------------------------------------------- acf

    private static LazyPlan acf(NodeContext context, List<List<Point>> buckets,
                                List<ColumnSpec> groups, JsonNode config) throws SQLException {
        int maxLag = positiveInt(config, "max_lag", DEFAULT_MAX_LAG, MAX_LAG_LIMIT);
        List<ColumnSpec> columns = new ArrayList<>(groups);
        columns.add(ColumnSpec.of("lag", "Int64"));
        columns.add(ColumnSpec.of("acf", "Float64"));
        columns.add(ColumnSpec.of("pacf", "Float64"));

        List<Object[]> rows = new ArrayList<>();
        for (List<Point> bucket : buckets) {
            int n = bucket.size();
            if (n <= maxLag) {
                throw new IllegalArgumentException(NODE + ": acf with max_lag " + maxLag
                        + " needs more than " + maxLag + " rows per group, got " + n);
            }
            double[] values = new double[n];
            for (int index = 0; index < n; index++) {
                values[index] = bucket.get(index).value();
            }
            double[] acf = autocorrelations(values, maxLag);
            Double[] pacf = partialAutocorrelations(acf, maxLag);
            for (int lag = 0; lag <= maxLag; lag++) {
                Point point = bucket.get(0);
                Object[] row = new Object[columns.size()];
                int column = 0;
                for (int group = 0; group < groups.size(); group++) {
                    row[column++] = groupValue(groups.get(group), point.groups()[group]);
                }
                row[column++] = (long) lag;
                row[column++] = acf[lag];
                row[column++] = pacf[lag];
                rows.add(row);
            }
        }
        return loadPlan(context, columns, rows);
    }

    private static double[] autocorrelations(double[] values, int maxLag) {
        int n = values.length;
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= n;
        double total = 0.0;
        for (double value : values) {
            total += (value - mean) * (value - mean);
        }
        if (!(total > 0.0)) {
            throw new IllegalArgumentException(NODE + ": acf needs a non-constant series");
        }
        double[] acf = new double[maxLag + 1];
        acf[0] = 1.0;
        for (int lag = 1; lag <= maxLag; lag++) {
            double sum = 0.0;
            for (int index = 0; index + lag < n; index++) {
                sum += (values[index] - mean) * (values[index + lag] - mean);
            }
            acf[lag] = sum / total;
        }
        return acf;
    }

    /** Durbin-Levinson recursion; a NULL when the recursion degenerates. */
    private static Double[] partialAutocorrelations(double[] acf, int maxLag) {
        Double[] pacf = new Double[maxLag + 1];
        pacf[0] = 1.0;
        if (maxLag == 0) {
            return pacf;
        }
        double[][] phi = new double[maxLag + 1][maxLag + 1];
        phi[1][1] = acf[1];
        pacf[1] = acf[1];
        for (int lag = 2; lag <= maxLag; lag++) {
            double numerator = acf[lag];
            double denominator = 1.0;
            for (int index = 1; index < lag; index++) {
                numerator -= phi[lag - 1][index] * acf[lag - index];
                denominator -= phi[lag - 1][index] * acf[index];
            }
            if (Math.abs(denominator) < 1e-12) {
                pacf[lag] = null;
                continue;
            }
            double value = numerator / denominator;
            phi[lag][lag] = value;
            for (int index = 1; index < lag; index++) {
                phi[lag][index] = phi[lag - 1][index] - value * phi[lag - 1][lag - index];
            }
            pacf[lag] = value;
        }
        return pacf;
    }

    // ------------------------------------------------------------------- lag

    private static LazyPlan lag(NodeContext context, LazyPlan input, TimeAxis time, ColumnSpec value,
                                List<ColumnSpec> groups, int maxLag) {
        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        for (int lag = 1; lag <= maxLag; lag++) {
            columns.add(ColumnSpec.of("value_lag_" + lag, "Float64"));
        }
        List<TimeSeriesSupport.OrderKey> order =
                List.of(new TimeSeriesSupport.OrderKey(time.column(), "asc"));
        String over = TimeSeriesSupport.overClause(groups, order, null);
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : input.columns()) {
            projection.add(NodeSql.quote(column.name()));
        }
        for (int lag = 1; lag <= maxLag; lag++) {
            projection.add("LAG(" + NodeSql.quote(value.name()) + ", " + lag + ") OVER (" + over
                    + ") AS " + NodeSql.quote("value_lag_" + lag));
        }
        StringJoiner orderBy = new StringJoiner(", ");
        for (ColumnSpec group : groups) {
            orderBy.add(NodeSql.quote(group.name()));
        }
        orderBy.add(NodeSql.quote(time.column().name()) + " ASC");
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input"
                + " ORDER BY " + orderBy;
        return new LazyPlan(context.db(), sql, columns);
    }

    // -------------------------------------------------------------- forecast

    private static LazyPlan forecast(NodeContext context, List<List<Point>> buckets,
                                     TimeAxis time, List<ColumnSpec> groups, JsonNode config)
            throws SQLException {
        int period = positiveInt(config, "period", DEFAULT_PERIOD, 1_000_000);
        if (period < 2) {
            throw new IllegalArgumentException(NODE + ": 'period' must be at least 2");
        }
        int horizon = positiveInt(config, "horizon", DEFAULT_HORIZON, 1_000_000);
        double confidence = confidence(config);
        String method = NodeSql.text(config, "method", "naive").trim().toLowerCase(Locale.ROOT);
        if (!FORECAST_METHODS.contains(method)) {
            throw new IllegalArgumentException(NODE + ": unknown forecast method '" + method
                    + "' (expected naive, seasonal_naive, mean, drift or holt_winters)");
        }
        double z = Distributions.normalQuantile(1.0 - (1.0 - confidence) / 2.0);

        List<ColumnSpec> columns = new ArrayList<>(groups);
        columns.add(time.output());
        columns.add(ColumnSpec.of("forecast", "Float64"));
        columns.add(ColumnSpec.of("lower", "Float64"));
        columns.add(ColumnSpec.of("upper", "Float64"));
        columns.add(ColumnSpec.of("method", "Utf8"));

        List<Object[]> rows = new ArrayList<>();
        for (List<Point> bucket : buckets) {
            int n = bucket.size();
            double[] values = new double[n];
            double[] times = new double[n];
            for (int index = 0; index < n; index++) {
                values[index] = bucket.get(index).value();
                times[index] = bucket.get(index).time();
                if (index > 0 && !(times[index] > times[index - 1])) {
                    throw new IllegalArgumentException(NODE + ": forecast needs strictly increasing "
                            + "times inside each group");
                }
            }
            double step = n > 1 ? (times[n - 1] - times[0]) / (n - 1) : 1.0;
            if (!(step > 0.0)) {
                throw new IllegalArgumentException(NODE + ": forecast needs at least two rows with "
                        + "distinct times per group");
            }
            Forecast model = fitModel(method, values, period, horizon);
            for (int ahead = 1; ahead <= horizon; ahead++) {
                Point point = bucket.get(0);
                Object[] row = new Object[columns.size()];
                int column = 0;
                for (int group = 0; group < groups.size(); group++) {
                    row[column++] = groupValue(groups.get(group), point.groups()[group]);
                }
                double future = times[n - 1] + step * ahead;
                row[column++] = timeValue(time, future);
                double predicted = model.values()[ahead - 1];
                row[column++] = predicted;
                if (model.sigma() == null) {
                    row[column++] = null;
                    row[column++] = null;
                } else {
                    double half = z * model.sigma() * Math.sqrt(ahead);
                    row[column++] = predicted - half;
                    row[column++] = predicted + half;
                }
                row[column++] = method;
                rows.add(row);
            }
        }
        return loadPlan(context, columns, rows);
    }

    /** The horizon forecasts plus the standard deviation of the one-step residuals. */
    private record Forecast(double[] values, Double sigma) {
    }

    private static Forecast fitModel(String method, double[] values, int period, int horizon) {
        int n = values.length;
        double[] forecasts = new double[horizon];
        switch (method) {
            case "naive" -> {
                requireRows(n, 3, "naive");
                for (int index = 0; index < horizon; index++) {
                    forecasts[index] = values[n - 1];
                }
                double[] residuals = new double[n - 1];
                for (int index = 1; index < n; index++) {
                    residuals[index - 1] = values[index] - values[index - 1];
                }
                return new Forecast(forecasts, sampleSd(residuals));
            }
            case "seasonal_naive" -> {
                requireRows(n, period + 1, "seasonal_naive");
                for (int index = 0; index < horizon; index++) {
                    forecasts[index] = values[n - period + index % period];
                }
                double[] residuals = new double[n - period];
                for (int index = period; index < n; index++) {
                    residuals[index - period] = values[index] - values[index - period];
                }
                return new Forecast(forecasts, sampleSd(residuals));
            }
            case "mean" -> {
                requireRows(n, 2, "mean");
                double mean = 0.0;
                for (double value : values) {
                    mean += value;
                }
                mean /= n;
                for (int index = 0; index < horizon; index++) {
                    forecasts[index] = mean;
                }
                double[] residuals = new double[n];
                for (int index = 0; index < n; index++) {
                    residuals[index] = values[index] - mean;
                }
                return new Forecast(forecasts, sampleSd(residuals));
            }
            case "drift" -> {
                requireRows(n, 3, "drift");
                double slope = (values[n - 1] - values[0]) / (n - 1);
                for (int index = 0; index < horizon; index++) {
                    forecasts[index] = values[n - 1] + slope * (index + 1);
                }
                double[] residuals = new double[n - 1];
                for (int index = 1; index < n; index++) {
                    residuals[index - 1] = values[index] - values[index - 1] - slope;
                }
                return new Forecast(forecasts, sampleSd(residuals));
            }
            default -> {
                requireRows(n, 2 * period, "holt_winters");
                return holtWinters(values, period, horizon);
            }
        }
    }

    /** Additive Holt-Winters with fixed smoothing; the forecasts and the one-step residual sd. */
    private static Forecast holtWinters(double[] values, int period, int horizon) {
        int n = values.length;
        double level = 0.0;
        for (int index = 0; index < period; index++) {
            level += values[index];
        }
        level /= period;
        double second = 0.0;
        for (int index = period; index < 2 * period; index++) {
            second += values[index];
        }
        second /= period;
        double trend = (second - level) / period;
        double[] seasonal = new double[period];
        for (int index = 0; index < period; index++) {
            seasonal[index] = values[index] - level;
        }
        double[] residuals = new double[n - period];
        for (int index = period; index < n; index++) {
            double predicted = level + trend + seasonal[index % period];
            residuals[index - period] = values[index] - predicted;
            double updatedLevel = HW_ALPHA * (values[index] - seasonal[index % period])
                    + (1.0 - HW_ALPHA) * (level + trend);
            double updatedTrend = HW_BETA * (updatedLevel - level) + (1.0 - HW_BETA) * trend;
            seasonal[index % period] = HW_GAMMA * (values[index] - updatedLevel)
                    + (1.0 - HW_GAMMA) * seasonal[index % period];
            level = updatedLevel;
            trend = updatedTrend;
        }
        double[] forecasts = new double[horizon];
        for (int ahead = 1; ahead <= horizon; ahead++) {
            forecasts[ahead - 1] = level + ahead * trend + seasonal[(n + ahead - 1) % period];
        }
        return new Forecast(forecasts, sampleSd(residuals));
    }

    private static void requireRows(int n, int minimum, String method) {
        if (n < minimum) {
            throw new IllegalArgumentException(NODE + ": forecast method '" + method
                    + "' needs at least " + minimum + " rows per group, got " + n);
        }
    }

    // ---------------------------------------------------------- stationarity

    private static LazyPlan stationarity(NodeContext context, List<List<Point>> buckets,
                                         List<ColumnSpec> groups) throws SQLException {
        List<ColumnSpec> columns = new ArrayList<>(groups);
        columns.add(ColumnSpec.of("test", "Utf8"));
        columns.add(ColumnSpec.of("statistic", "Float64"));
        columns.add(ColumnSpec.of("p_value", "Float64"));
        columns.add(ColumnSpec.of("critical_1", "Float64"));
        columns.add(ColumnSpec.of("critical_5", "Float64"));
        columns.add(ColumnSpec.of("critical_10", "Float64"));
        columns.add(ColumnSpec.of("stationary", "Boolean"));

        List<Object[]> rows = new ArrayList<>();
        for (List<Point> bucket : buckets) {
            int n = bucket.size();
            if (n < 8) {
                throw new IllegalArgumentException(NODE + ": stationarity needs at least 8 rows "
                        + "per group, got " + n);
            }
            double[] values = new double[n];
            for (int index = 0; index < n; index++) {
                values[index] = bucket.get(index).value();
            }
            double statistic = adfStatistic(values);
            double pValue = Math.max(0.0, Math.min(1.0,
                    Distributions.normalCdf((statistic - ADF_MU) / ADF_SIGMA)));
            Point point = bucket.get(0);
            Object[] row = new Object[columns.size()];
            int column = 0;
            for (int group = 0; group < groups.size(); group++) {
                row[column++] = groupValue(groups.get(group), point.groups()[group]);
            }
            row[column++] = "adf";
            row[column++] = statistic;
            row[column++] = pValue;
            row[column++] = CRITICAL_1;
            row[column++] = CRITICAL_5;
            row[column++] = CRITICAL_10;
            row[column++] = statistic < CRITICAL_5;
            rows.add(row);
        }
        return loadPlan(context, columns, rows);
    }

    /** ADF with a constant and one lagged difference: the t statistic of the y(t-1) term. */
    private static double adfStatistic(double[] values) {
        int n = values.length;
        int observations = n - 2;
        if (observations <= 3) {
            throw new IllegalArgumentException(NODE + ": stationarity needs more rows for the "
                    + "ADF regression");
        }
        double[][] design = new double[observations][3];
        double[] response = new double[observations];
        for (int time = 2; time < n; time++) {
            int row = time - 2;
            design[row][0] = 1.0;
            design[row][1] = values[time - 1];
            design[row][2] = values[time - 1] - values[time - 2];
            response[row] = values[time] - values[time - 1];
        }
        double[][] normal = new double[3][3];
        double[] right = new double[3];
        for (int row = 0; row < observations; row++) {
            for (int column = 0; column < 3; column++) {
                right[column] += design[row][column] * response[row];
                for (int other = 0; other < 3; other++) {
                    normal[column][other] += design[row][column] * design[row][other];
                }
            }
        }
        double[] coefficients;
        double[][] inverse;
        try {
            coefficients = Matrix.solve(normal, right);
            inverse = Matrix.invert(normal);
        } catch (IllegalArgumentException singular) {
            throw new IllegalArgumentException(NODE + ": the ADF regression is singular (the "
                    + "series is constant or too short)", singular);
        }
        double residualSum = 0.0;
        for (int row = 0; row < observations; row++) {
            double fitted = 0.0;
            for (int column = 0; column < 3; column++) {
                fitted += design[row][column] * coefficients[column];
            }
            residualSum += (response[row] - fitted) * (response[row] - fitted);
        }
        double variance = residualSum / (observations - 3);
        double error = Math.sqrt(Math.max(0.0, variance * inverse[1][1]));
        if (!(error > 0.0)) {
            throw new IllegalArgumentException(NODE + ": the ADF regression has a zero standard "
                    + "error (the series is constant)");
        }
        return coefficients[1] / error;
    }

    // -------------------------------------------------------------- helpers

    /** Arrow-table value of a group key, preserving the input column type. */
    private static Object groupValue(ColumnSpec column, String text) {
        String type = column.arrowType();
        if (type.startsWith("Decimal128")) {
            return new BigDecimal(text);
        }
        return switch (type) {
            case "Int64" -> Long.parseLong(text);
            case "Float64" -> Double.parseDouble(text);
            case "Boolean" -> Boolean.parseBoolean(text);
            case "Date32" -> LocalDate.parse(text);
            case "Timestamp" -> LocalDateTime.parse(text.replace(' ', 'T'));
            default -> text;
        };
    }

    private static Object timeValue(TimeAxis time, double value) {
        return time.date() ? LocalDate.ofEpochDay(Math.round(value)) : (Object) value;
    }

    private static Double sampleSd(double[] values) {
        if (values.length < 2) {
            return null;
        }
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;
        double sum = 0.0;
        for (double value : values) {
            sum += (value - mean) * (value - mean);
        }
        return Math.sqrt(sum / (values.length - 1));
    }

    private static LazyPlan loadPlan(NodeContext context, List<ColumnSpec> columns,
                                     List<Object[]> rows) throws SQLException {
        try (ArrowTable table = ArrowTable.of(columns, rows)) {
            String name = context.db().load(table, "timeseries", context.workflowId(), context.nodeId());
            return new LazyPlan(context.db(), "SELECT * FROM " + NodeSql.quote(name), columns);
        }
    }

    // --------------------------------------------------------------- config

    private static String required(JsonNode config, String field) {
        try {
            return NodeSql.requiredText(config, field);
        } catch (IllegalArgumentException missing) {
            throw new IllegalArgumentException(NODE + ": config field '" + field + "' is required");
        }
    }

    private static int positiveInt(JsonNode config, String field, int fallback, int maximum) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || value.longValue() < 1 || value.longValue() > maximum) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be an integer between "
                    + 1 + " and " + maximum);
        }
        return value.intValue();
    }

    private static double confidence(JsonNode config) {
        JsonNode value = config.get("confidence");
        if (value == null || value.isNull()) {
            return DEFAULT_CONFIDENCE;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0.0) || !(value.doubleValue() < 1.0)) {
            throw new IllegalArgumentException(NODE + ": 'confidence' must be a number in (0, 1)");
        }
        return value.doubleValue();
    }
}
