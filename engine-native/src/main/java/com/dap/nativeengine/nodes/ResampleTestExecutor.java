package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.resample_test: bootstrap confidence intervals and two-sample permutation tests.
 *
 * <p>The rows are read into Java (capped by {@code max_rows}) because the resampling
 * loop has no SQL equivalent; the result is a literal plan like the other in-memory
 * statistics. Bootstrap resamples the observations with replacement and reports the
 * estimate, the standard error of the resampled statistics, the percentile confidence
 * interval and the bootstrap bias. The permutation mode shuffles the group labels of
 * {@code value} between the two levels of {@code group} and reports the observed
 * difference and its permutation p-value (add-one estimator, so p is never exactly 0).</p>
 *
 * <p>Determinism: every draw comes from one {@link Random} seeded with {@code seed}, so
 * the same input and settings always produce the same numbers.</p>
 */
public final class ResampleTestExecutor implements NodeExecutor {
    private static final String NODE = "resample_test";
    private static final List<String> MODES = List.of("bootstrap", "permutation");
    private static final List<String> BOOTSTRAP_STATISTICS =
            List.of("mean", "median", "sd", "correlation", "slope");
    private static final List<String> PERMUTATION_STATISTICS =
            List.of("difference_mean", "difference_median");
    private static final List<String> ALTERNATIVES = List.of("two-sided", "less", "greater");
    private static final int DEFAULT_REPLICATES = 1000;
    private static final double DEFAULT_CONFIDENCE = 0.95;
    private static final int DEFAULT_SEED = 42;
    private static final int DEFAULT_MAX_ROWS = 100_000;
    private static final int MAX_REPLICATES = 1_000_000;
    private static final int MAX_ROWS_LIMIT = 10_000_000;
    /** Warn when more than 10% of the configured resamples had to be discarded. */
    private static final double WARNING_SKIP_FRACTION = 0.10;

    private static final List<ColumnSpec> BOOTSTRAP_COLUMNS = List.of(
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

    private static final List<ColumnSpec> PERMUTATION_COLUMNS = List.of(
            ColumnSpec.of("statistic", "Utf8"),
            ColumnSpec.of("observed", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("permutations", "Int64"));

    @Override
    public String nodeId() {
        return "dap.resample_test";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String mode = required(config, "mode").trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException(NODE + ": unknown mode '" + mode
                    + "' (expected bootstrap or permutation)");
        }
        String statistic = required(config, "statistic").trim().toLowerCase(Locale.ROOT);
        int maxRows = positiveInt(config, "max_rows", DEFAULT_MAX_ROWS, MAX_ROWS_LIMIT);
        int seed = integer(config, "seed", DEFAULT_SEED);
        if ("bootstrap".equals(mode)) {
            return bootstrap(context, input, config, statistic, maxRows, seed);
        }
        return permutation(context, input, config, statistic, maxRows, seed);
    }

    // -------------------------------------------------------------- bootstrap

    private static LazyPlan bootstrap(NodeContext context, LazyPlan input, JsonNode config,
                                      String statistic, int maxRows, int seed) throws SQLException {
        if (!BOOTSTRAP_STATISTICS.contains(statistic)) {
            throw new IllegalArgumentException(NODE + ": unknown bootstrap statistic '" + statistic
                    + "' (expected mean, median, sd, correlation or slope)");
        }
        int resamples = positiveInt(config, "resamples", DEFAULT_REPLICATES, MAX_REPLICATES);
        double confidence = confidence(config);
        List<ColumnSpec> columns = columns(config, input, statistic);
        List<double[]> rows = readNumeric(context, input, columns, maxRows);
        int n = rows.size();
        requireMinimum(n, statistic);
        double estimate = bootstrapStatistic(statistic, column(rows, 0, n), column(rows, 1, n), n);

        Random random = new Random(seed);
        double[] drawn = new double[n];
        double[] drawnY = new double[n];
        double[] statistics = new double[resamples];
        int valid = 0;
        for (int b = 0; b < resamples; b++) {
            for (int i = 0; i < n; i++) {
                double[] row = rows.get(random.nextInt(n));
                drawn[i] = row[0];
                drawnY[i] = row.length > 1 ? row[1] : 0.0;
            }
            double value;
            try {
                value = bootstrapStatistic(statistic, drawn, drawnY, n);
            } catch (IllegalArgumentException degenerate) {
                // A degenerate resample (for example all identical x for a correlation)
                // has no defined statistic; skipping it is the only unbiased choice.
                value = Double.NaN;
            }
            if (Double.isFinite(value)) {
                statistics[valid++] = value;
            }
        }
        if (valid == 0) {
            throw new IllegalArgumentException(NODE + ": every bootstrap resample gave an undefined "
                    + statistic + " (the column is constant)");
        }
        Double se = valid < 2 ? null : sampleSd(statistics, 0, valid);
        Double bias = mean(statistics, 0, valid) - estimate;
        double alpha = (1.0 - confidence) / 2.0;
        double[] sorted = java.util.Arrays.copyOf(statistics, valid);
        java.util.Arrays.sort(sorted);
        Double ciLow = percentile(sorted, alpha);
        Double ciHigh = percentile(sorted, 1.0 - alpha);

        StringJoiner select = new StringJoiner(", ");
        select.add(NodeSql.literal(statistic) + " AS " + NodeSql.quote("statistic"));
        select.add(doubleLiteral(estimate) + " AS " + NodeSql.quote("estimate"));
        select.add(doubleLiteral(se) + " AS " + NodeSql.quote("se"));
        select.add(doubleLiteral(ciLow) + " AS " + NodeSql.quote("ci_low"));
        select.add(doubleLiteral(ciHigh) + " AS " + NodeSql.quote("ci_high"));
        select.add(doubleLiteral(bias) + " AS " + NodeSql.quote("bias"));
        select.add(n + " AS " + NodeSql.quote("n"));
        select.add(resamples + " AS " + NodeSql.quote("resamples"));
        select.add(valid + " AS " + NodeSql.quote("valid_resamples"));
        select.add(warning(valid, resamples, statistic));
        return new LazyPlan(context.db(), "SELECT " + select, BOOTSTRAP_COLUMNS);
    }

    /** NULL unless too many draws were degenerate; then the reason is part of the result. */
    private static String warning(int valid, int resamples, String statistic) {
        if (valid >= resamples * (1.0 - WARNING_SKIP_FRACTION)) {
            return "CAST(NULL AS VARCHAR) AS " + NodeSql.quote("warning");
        }
        String text = String.format(Locale.ROOT,
                "only %d of %d bootstrap resamples gave a defined %s; intervals use %d valid resamples "
                        + "(the column may be nearly constant)", valid, resamples, statistic, valid);
        return NodeSql.literal(text) + " AS " + NodeSql.quote("warning");
    }

    private static List<ColumnSpec> columns(JsonNode config, LazyPlan input, String statistic) {
        JsonNode array = config.get("columns");
        if (array == null || array.isNull() || !array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException(NODE + ": 'columns' must be a non-empty array of "
                    + "numeric column names");
        }
        int expected = "correlation".equals(statistic) || "slope".equals(statistic) ? 2 : 1;
        if (array.size() != expected) {
            throw new IllegalArgumentException(NODE + ": statistic '" + statistic + "' needs "
                    + (expected == 2 ? "two columns" : "one column") + ", got " + array.size());
        }
        List<ColumnSpec> columns = new ArrayList<>(expected);
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(NODE + ": 'columns' must contain non-empty names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException(NODE + ": duplicate column '" + name
                        + "'; correlation and slope need two distinct columns");
            }
            ColumnSpec column;
            try {
                column = NodeSql.column(input.columns(), name);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(NODE + ": unknown column '" + name + "'");
            }
            if (!column.numeric()) {
                throw new IllegalArgumentException(NODE + ": column '" + name
                        + "' must be numeric, got " + column.arrowType());
            }
            columns.add(column);
        }
        return columns;
    }

    private static void requireMinimum(int n, String statistic) {
        int minimum = switch (statistic) {
            case "correlation", "slope" -> 3;
            case "sd" -> 2;
            default -> 1;
        };
        if (n < minimum) {
            throw new IllegalArgumentException(NODE + ": statistic '" + statistic
                    + "' needs at least " + minimum + " row" + (minimum == 1 ? "" : "s") + ", got " + n);
        }
    }

    private static double bootstrapStatistic(String statistic, double[] x, double[] y, int n) {
        return switch (statistic) {
            case "mean" -> mean(x, 0, n);
            case "median" -> median(x, 0, n);
            case "sd" -> sampleSd(x, 0, n);
            case "correlation" -> correlation(x, y, n);
            case "slope" -> slope(x, y, n);
            default -> throw new IllegalArgumentException(NODE + ": unknown statistic '" + statistic + "'");
        };
    }

    // ------------------------------------------------------------ permutation

    private static LazyPlan permutation(NodeContext context, LazyPlan input, JsonNode config,
                                        String statistic, int maxRows, int seed) throws SQLException {
        if (!PERMUTATION_STATISTICS.contains(statistic)) {
            throw new IllegalArgumentException(NODE + ": unknown permutation statistic '" + statistic
                    + "' (expected difference_mean or difference_median)");
        }
        int permutations = positiveInt(config, "permutations", DEFAULT_REPLICATES, MAX_REPLICATES);
        String alternative = NodeSql.text(config, "alternative", "two-sided").trim()
                .toLowerCase(Locale.ROOT);
        if (!ALTERNATIVES.contains(alternative)) {
            throw new IllegalArgumentException(NODE + ": unknown alternative '" + alternative
                    + "' (expected two-sided, less or greater)");
        }
        ColumnSpec value = numericColumn(config, input, "value");
        ColumnSpec group = groupColumn(config, input);
        List<String[]> rows = readGroups(context, input, value, group, maxRows);

        List<String> levels = new ArrayList<>(new java.util.TreeSet<>(
                rows.stream().map(row -> row[1]).toList()));
        if (levels.size() != 2) {
            throw new IllegalArgumentException(NODE + ": 'group' column '" + group.name()
                    + "' needs exactly two groups, got " + levels.size());
        }
        String control = levels.get(0);
        String treatment = levels.get(1);
        List<Double> first = new ArrayList<>();
        List<Double> second = new ArrayList<>();
        for (String[] row : rows) {
            double parsed = Double.parseDouble(row[0]);
            if (control.equals(row[1])) {
                first.add(parsed);
            } else {
                second.add(parsed);
            }
        }
        if (first.isEmpty() || second.isEmpty()) {
            throw new IllegalArgumentException(NODE + ": both groups of '" + group.name()
                    + "' need at least one row");
        }
        double[] pooled = new double[first.size() + second.size()];
        for (int i = 0; i < first.size(); i++) {
            pooled[i] = first.get(i);
        }
        for (int i = 0; i < second.size(); i++) {
            pooled[first.size() + i] = second.get(i);
        }
        int n1 = first.size();
        double observed = differenceStatistic(statistic, pooled, 0, n1)
                - differenceStatistic(statistic, pooled, n1, pooled.length);

        Random random = new Random(seed);
        long extreme = 0;
        for (int b = 0; b < permutations; b++) {
            shuffle(pooled, random);
            double difference = differenceStatistic(statistic, pooled, 0, n1)
                    - differenceStatistic(statistic, pooled, n1, pooled.length);
            if ("greater".equals(alternative)) {
                extreme += difference >= observed ? 1 : 0;
            } else if ("less".equals(alternative)) {
                extreme += difference <= observed ? 1 : 0;
            } else {
                extreme += Math.abs(difference) >= Math.abs(observed) ? 1 : 0;
            }
        }
        double pValue = (extreme + 1.0) / (permutations + 1.0);

        StringJoiner select = new StringJoiner(", ");
        select.add(NodeSql.literal(statistic) + " AS " + NodeSql.quote("statistic"));
        select.add(doubleLiteral(observed) + " AS " + NodeSql.quote("observed"));
        select.add(doubleLiteral(pValue) + " AS " + NodeSql.quote("p_value"));
        select.add(pooled.length + " AS " + NodeSql.quote("n"));
        select.add(permutations + " AS " + NodeSql.quote("permutations"));
        return new LazyPlan(context.db(), "SELECT " + select, PERMUTATION_COLUMNS);
    }

    private static double differenceStatistic(String statistic, double[] values, int from, int to) {
        return switch (statistic) {
            case "difference_mean" -> mean(values, from, to);
            case "difference_median" -> median(values, from, to);
            default -> throw new IllegalArgumentException(NODE + ": unknown statistic '" + statistic + "'");
        };
    }

    private static ColumnSpec numericColumn(JsonNode config, LazyPlan input, String field) {
        String name = required(config, field);
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown '" + field + "' column '" + name + "'");
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' column '" + name
                    + "' must be numeric, got " + column.arrowType());
        }
        return column;
    }

    private static ColumnSpec groupColumn(JsonNode config, LazyPlan input) {
        String name = required(config, "group");
        try {
            return NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown 'group' column '" + name + "'");
        }
    }

    // ------------------------------------------------------------------ data

    /** One numeric row per observation; NULL and non-finite values in any selected column are dropped. */
    private static List<double[]> readNumeric(NodeContext context, LazyPlan input,
                                              List<ColumnSpec> columns, int maxRows)
            throws SQLException {
        StringJoiner projection = new StringJoiner(", ");
        StringJoiner where = new StringJoiner(" AND ");
        for (ColumnSpec column : columns) {
            String quoted = NodeSql.quote(column.name());
            String cast = "CAST(" + quoted + " AS DOUBLE)";
            projection.add(cast);
            where.add(quoted + " IS NOT NULL AND isfinite(" + cast + ")");
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + where + " LIMIT " + (maxRows + 1);
        List<double[]> rows = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                double[] row = new double[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    row[i] = result.getDouble(i + 1);
                }
                rows.add(row);
            }
        }
        if (rows.size() > maxRows) {
            throw new IllegalArgumentException(NODE + ": the resampling runs in memory and reads at "
                    + "most max_rows = " + maxRows + " rows, got more; raise 'max_rows' or "
                    + "pre-aggregate the input");
        }
        return rows;
    }

    /** value/group text pairs; NULL and non-finite values in either column are dropped. */
    private static List<String[]> readGroups(NodeContext context, LazyPlan input, ColumnSpec value,
                                             ColumnSpec group, int maxRows) throws SQLException {
        String quotedValue = NodeSql.quote(value.name());
        String sql = "SELECT CAST(" + quotedValue + " AS DOUBLE), CAST("
                + NodeSql.quote(group.name()) + " AS VARCHAR) FROM " + input.asTable()
                + " AS dap_input WHERE " + quotedValue + " IS NOT NULL AND "
                + "isfinite(CAST(" + quotedValue + " AS DOUBLE)) AND "
                + NodeSql.quote(group.name()) + " IS NOT NULL LIMIT " + (maxRows + 1);
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(new String[]{Double.toString(result.getDouble(1)), result.getString(2)});
            }
        }
        if (rows.size() > maxRows) {
            throw new IllegalArgumentException(NODE + ": the permutation runs in memory and reads at "
                    + "most max_rows = " + maxRows + " rows, got more; raise 'max_rows' or "
                    + "pre-aggregate the input");
        }
        return rows;
    }

    private static double[] column(List<double[]> rows, int index, int n) {
        if (index >= rows.get(0).length) {
            return new double[n];
        }
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = rows.get(i)[index];
        }
        return values;
    }

    // ------------------------------------------------------------ statistics

    private static double mean(double[] values, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            sum += values[i];
        }
        return sum / (to - from);
    }

    /** Median of values[from..to); reorders that slice (quickselect, O(n) average). */
    private static double median(double[] values, int from, int to) {
        int size = to - from;
        if (size % 2 == 1) {
            return select(values, from, to, from + size / 2);
        }
        double lower = select(values, from, to, from + size / 2 - 1);
        double upper = select(values, from, to, from + size / 2);
        return 0.5 * (lower + upper);
    }

    private static double sampleSd(double[] values, int from, int to) {
        int size = to - from;
        double average = mean(values, from, to);
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            double deviation = values[i] - average;
            sum += deviation * deviation;
        }
        return Math.sqrt(sum / (size - 1));
    }

    private static double correlation(double[] x, double[] y, int n) {
        double meanX = mean(x, 0, n);
        double meanY = mean(y, 0, n);
        double covariance = 0.0;
        double varianceX = 0.0;
        double varianceY = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        if (!(varianceX > 0.0) || !(varianceY > 0.0)) {
            throw new IllegalArgumentException(NODE + ": the correlation needs both columns to have "
                    + "a positive variance");
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }

    private static double slope(double[] x, double[] y, int n) {
        double meanX = mean(x, 0, n);
        double meanY = mean(y, 0, n);
        double covariance = 0.0;
        double varianceX = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            covariance += dx * (y[i] - meanY);
            varianceX += dx * dx;
        }
        if (!(varianceX > 0.0)) {
            throw new IllegalArgumentException(NODE + ": the slope needs the x column to have a "
                    + "positive variance");
        }
        return covariance / varianceX;
    }

    /** k-th smallest of values[from..to) (0-based k in that slice) via Hoare partitioning. */
    private static double select(double[] values, int from, int to, int k) {
        int low = from;
        int high = to - 1;
        while (low < high) {
            double pivot = medianOfThree(values[low], values[(low + high) >>> 1], values[high]);
            int left = low;
            int right = high;
            while (left <= right) {
                while (values[left] < pivot) {
                    left++;
                }
                while (values[right] > pivot) {
                    right--;
                }
                if (left <= right) {
                    double swap = values[left];
                    values[left] = values[right];
                    values[right] = swap;
                    left++;
                    right--;
                }
            }
            if (k <= right) {
                high = right;
            } else if (k >= left) {
                low = left;
            } else {
                return values[k];
            }
        }
        return values[k];
    }

    private static double medianOfThree(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /** In-place Fisher-Yates shuffle from the seeded random stream. */
    private static void shuffle(double[] values, Random random) {
        for (int i = values.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            double swap = values[i];
            values[i] = values[j];
            values[j] = swap;
        }
    }

    private static Double percentile(double[] sorted, double probability) {
        if (sorted.length == 0) {
            return null;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double position = (sorted.length - 1) * probability;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = position - lower;
        return sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
    }

    // -------------------------------------------------------------- config

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
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be an integer between 1 and "
                    + maximum);
        }
        return value.intValue();
    }

    private static int integer(JsonNode config, String field, int fallback) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be an integer");
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

    private static String doubleLiteral(Double value) {
        return value == null || !Double.isFinite(value)
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
    }
}
