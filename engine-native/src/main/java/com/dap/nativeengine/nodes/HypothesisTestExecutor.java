package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.Distributions;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * dap.hypothesis_test: one-row hypothesis test (Student's t, chi-square, one-way ANOVA).
 *
 * <p>The statistic and its degrees of freedom are computed by DuckDB aggregates, the
 * p-value by {@link Distributions} because DuckDB has no erf/beta. The output is a
 * single-row literal plan, so /table and /profile stay lazy like the other nodes.</p>
 */
public final class HypothesisTestExecutor implements NodeExecutor {
    private static final List<String> TESTS = List.of(
            "t_test_two_sample", "t_test_paired", "t_test_one_sample", "chi_square", "anova");
    private static final List<String> ALTERNATIVES = List.of("two-sided", "less", "greater");
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("test", "Utf8"),
            ColumnSpec.of("statistic", "Float64"),
            ColumnSpec.of("df", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("n", "Int64"));

    @Override
    public String nodeId() {
        return "dap.hypothesis_test";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String test = NodeSql.requiredText(config, "test").toLowerCase(Locale.ROOT);
        if (!TESTS.contains(test)) {
            throw new IllegalArgumentException("hypothesis_test: unknown test '" + test
                    + "' (expected " + String.join(", ", TESTS) + ")");
        }
        String alternative = NodeSql.text(config, "alternative", "two-sided").toLowerCase(Locale.ROOT);
        if (!ALTERNATIVES.contains(alternative)) {
            throw new IllegalArgumentException("hypothesis_test: unknown alternative '" + alternative
                    + "' (expected two-sided, less, greater)");
        }
        alpha(config);
        Result result = switch (test) {
            case "t_test_one_sample" -> oneSample(context, input, config, alternative);
            case "t_test_two_sample" -> twoSample(context, input, config, alternative);
            case "t_test_paired" -> paired(context, input, config, alternative);
            case "chi_square" -> chiSquare(context, input, config);
            default -> anova(context, input, config);
        };
        return new LazyPlan(context.db(), sql(test, result), OUTPUT);
    }

    /** A test result; the statistic, df and p-value are NULL when the test is undefined. */
    private record Result(Double statistic, Double df, Double pValue, long n) {
    }

    private static Result undefined(Double df, long n) {
        return new Result(null, df, null, n);
    }

    private record Stats(long n, Double mean, Double sd) {
    }

    // ------------------------------------------------------------------ tests

    private static Result oneSample(NodeContext context, LazyPlan input, JsonNode config,
                                    String alternative) throws SQLException {
        String column = numericColumn(input, config, "value");
        String expression = "CAST(" + NodeSql.quote(column) + " AS DOUBLE)";
        Stats stats = stats(context, input, expression, null);
        requireSample(stats.n(), "t_test_one_sample", 2);
        double mu = number(config, "mu", 0.0);
        if (stats.sd() == null || stats.sd() == 0.0) {
            return undefined(stats.n() - 1.0, stats.n());
        }
        double t = (stats.mean() - mu) / (stats.sd() / Math.sqrt(stats.n()));
        return new Result(t, stats.n() - 1.0, tPValue(t, stats.n() - 1.0, alternative), stats.n());
    }

    private static Result twoSample(NodeContext context, LazyPlan input, JsonNode config,
                                    String alternative) throws SQLException {
        String column = numericColumn(input, config, "value");
        String group = column(input, config, "group");
        String expression = "CAST(" + NodeSql.quote(column) + " AS DOUBLE)";
        String quotedGroup = NodeSql.quote(group);
        String groupA = scalar(config, "group_a");
        String groupB = scalar(config, "group_b");
        if (groupA == null && groupB == null) {
            List<String> values = distinctGroups(context, input, quotedGroup);
            if (values.size() != 2) {
                throw new IllegalArgumentException("hypothesis_test: t_test_two_sample needs exactly two "
                        + "groups, the column '" + group + "' has " + values.size()
                        + "; set group_a and group_b");
            }
            groupA = values.get(0);
            groupB = values.get(1);
        } else if (groupA == null || groupB == null) {
            throw new IllegalArgumentException("hypothesis_test: t_test_two_sample needs both group_a and group_b");
        }
        Map<String, Stats> groups = groupStats(context, input, quotedGroup, expression, groupA, groupB);
        Stats first = groups.get(groupA);
        Stats second = groups.get(groupB);
        requireSample(first == null ? 0 : first.n(), "t_test_two_sample group '" + groupA + "'", 2);
        requireSample(second == null ? 0 : second.n(), "t_test_two_sample group '" + groupB + "'", 2);
        double var1 = first.sd() * first.sd();
        double var2 = second.sd() * second.sd();
        double term1 = var1 / first.n();
        double term2 = var2 / second.n();
        double standardError = Math.sqrt(term1 + term2);
        long n = first.n() + second.n();
        if (standardError == 0.0) {
            return undefined(null, n);
        }
        double t = (first.mean() - second.mean()) / standardError;
        double spread = term1 * term1 / (first.n() - 1.0) + term2 * term2 / (second.n() - 1.0);
        if (spread == 0.0) {
            return undefined(null, n);
        }
        double df = (term1 + term2) * (term1 + term2) / spread;
        return new Result(t, df, tPValue(t, df, alternative), n);
    }

    private static Result paired(NodeContext context, LazyPlan input, JsonNode config,
                                 String alternative) throws SQLException {
        String first = numericColumn(input, config, "group_a");
        String second = numericColumn(input, config, "group_b");
        String expression = "(CAST(" + NodeSql.quote(first) + " AS DOUBLE) - CAST("
                + NodeSql.quote(second) + " AS DOUBLE))";
        String where = NodeSql.quote(first) + " IS NOT NULL AND " + NodeSql.quote(second) + " IS NOT NULL";
        Stats stats = stats(context, input, expression, where);
        requireSample(stats.n(), "t_test_paired", 2);
        if (stats.sd() == null || stats.sd() == 0.0) {
            return undefined(stats.n() - 1.0, stats.n());
        }
        double t = stats.mean() / (stats.sd() / Math.sqrt(stats.n()));
        return new Result(t, stats.n() - 1.0, tPValue(t, stats.n() - 1.0, alternative), stats.n());
    }

    private static Result chiSquare(NodeContext context, LazyPlan input, JsonNode config)
            throws SQLException {
        String value = column(input, config, "value");
        String group = column(input, config, "group");
        String quotedValue = NodeSql.quote(value);
        String quotedGroup = NodeSql.quote(group);
        String sql = "SELECT CAST(" + quotedValue + " AS VARCHAR) AS dap_value, CAST(" + quotedGroup
                + " AS VARCHAR) AS dap_group, COUNT(*) AS dap_count"
                + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + quotedValue + " IS NOT NULL AND " + quotedGroup + " IS NOT NULL"
                + " GROUP BY 1, 2";
        Map<String, Map<String, Long>> table = new LinkedHashMap<>();
        Map<String, Long> rowTotals = new LinkedHashMap<>();
        Map<String, Long> columnTotals = new LinkedHashMap<>();
        long n = 0;
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                String row = result.getString("dap_value");
                String column = result.getString("dap_group");
                long count = result.getLong("dap_count");
                table.computeIfAbsent(row, ignored -> new LinkedHashMap<>()).put(column, count);
                rowTotals.merge(row, count, Long::sum);
                columnTotals.merge(column, count, Long::sum);
                n += count;
            }
        }
        if (rowTotals.size() < 2 || columnTotals.size() < 2) {
            throw new IllegalArgumentException("hypothesis_test: chi_square needs at least two distinct "
                    + "values in '" + value + "' and in '" + group + "', got " + rowTotals.size()
                    + " and " + columnTotals.size());
        }
        double statistic = 0.0;
        for (Map.Entry<String, Map<String, Long>> row : table.entrySet()) {
            for (Map.Entry<String, Long> cell : row.getValue().entrySet()) {
                double expected = (double) rowTotals.get(row.getKey()) * columnTotals.get(cell.getKey()) / n;
                double difference = cell.getValue() - expected;
                statistic += difference * difference / expected;
            }
        }
        double df = (double) (rowTotals.size() - 1) * (columnTotals.size() - 1);
        return new Result(statistic, df, clamp(1.0 - Distributions.chiSquareCdf(statistic, df)), n);
    }

    private static Result anova(NodeContext context, LazyPlan input, JsonNode config)
            throws SQLException {
        String value = numericColumn(input, config, "value");
        String group = column(input, config, "group");
        String quotedValue = NodeSql.quote(value);
        String quotedGroup = NodeSql.quote(group);
        String expression = "CAST(" + quotedValue + " AS DOUBLE)";
        String sql = "SELECT CAST(" + quotedGroup + " AS VARCHAR) AS dap_group, COUNT(" + expression
                + ") AS dap_n, AVG(" + expression + ") AS dap_mean, VAR_SAMP(" + expression
                + ") AS dap_var"
                + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + quotedGroup + " IS NOT NULL AND " + quotedValue + " IS NOT NULL"
                + " GROUP BY 1";
        List<long[]> counts = new ArrayList<>();
        List<Double> means = new ArrayList<>();
        List<Double> variances = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                counts.add(new long[]{result.getLong("dap_n")});
                means.add(result.getDouble("dap_mean"));
                variances.add(result.getObject("dap_var") == null ? 0.0 : result.getDouble("dap_var"));
            }
        }
        if (means.size() < 2) {
            throw new IllegalArgumentException("hypothesis_test: anova needs at least two groups in '"
                    + group + "', got " + means.size());
        }
        long total = 0;
        double weighted = 0.0;
        for (int i = 0; i < means.size(); i++) {
            total += counts.get(i)[0];
            weighted += counts.get(i)[0] * means.get(i);
        }
        if (total - means.size() <= 0) {
            throw new IllegalArgumentException("hypothesis_test: anova needs more rows than groups, got "
                    + total + " rows in " + means.size() + " groups");
        }
        double grandMean = weighted / total;
        double between = 0.0;
        double within = 0.0;
        for (int i = 0; i < means.size(); i++) {
            double difference = means.get(i) - grandMean;
            between += counts.get(i)[0] * difference * difference;
            within += (counts.get(i)[0] - 1) * variances.get(i);
        }
        double df1 = means.size() - 1.0;
        double df2 = total - means.size();
        if (within == 0.0) {
            return undefined(df1, total);
        }
        double statistic = (between / df1) / (within / df2);
        return new Result(statistic, df1, clamp(1.0 - Distributions.fCdf(statistic, df1, df2)), total);
    }

    // ------------------------------------------------------------------ SQL

    private static String sql(String test, Result result) {
        return "SELECT " + NodeSql.literal(test) + " AS " + NodeSql.quote("test")
                + ", " + doubleLiteral(result.statistic()) + " AS " + NodeSql.quote("statistic")
                + ", " + doubleLiteral(result.df()) + " AS " + NodeSql.quote("df")
                + ", " + doubleLiteral(result.pValue()) + " AS " + NodeSql.quote("p_value")
                + ", " + result.n() + " AS " + NodeSql.quote("n");
    }

    private static String doubleLiteral(Double value) {
        return value == null
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
    }

    // ------------------------------------------------------------- helpers

    private static Stats stats(NodeContext context, LazyPlan input, String expression, String where)
            throws SQLException {
        String sql = "SELECT COUNT(" + expression + ") AS dap_n, AVG(" + expression + ") AS dap_mean,"
                + " STDDEV_SAMP(" + expression + ") AS dap_sd"
                + " FROM " + input.asTable() + " AS dap_input"
                + (where == null ? "" : " WHERE " + where);
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return new Stats(0, null, null);
            }
            return new Stats(result.getLong("dap_n"),
                    result.getObject("dap_mean") == null ? null : result.getDouble("dap_mean"),
                    result.getObject("dap_sd") == null ? null : result.getDouble("dap_sd"));
        }
    }

    private static List<String> distinctGroups(NodeContext context, LazyPlan input, String quotedGroup)
            throws SQLException {
        String sql = "SELECT CAST(" + quotedGroup + " AS VARCHAR) AS dap_group FROM " + input.asTable()
                + " AS dap_input WHERE " + quotedGroup + " IS NOT NULL GROUP BY 1 ORDER BY 1";
        List<String> groups = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                groups.add(result.getString("dap_group"));
            }
        }
        return groups;
    }

    private static Map<String, Stats> groupStats(NodeContext context, LazyPlan input, String quotedGroup,
                                                 String expression, String groupA, String groupB)
            throws SQLException {
        String sql = "SELECT CAST(" + quotedGroup + " AS VARCHAR) AS dap_group, COUNT(" + expression
                + ") AS dap_n, AVG(" + expression + ") AS dap_mean, STDDEV_SAMP(" + expression
                + ") AS dap_sd"
                + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE CAST(" + quotedGroup + " AS VARCHAR) IN (" + NodeSql.literal(groupA) + ", "
                + NodeSql.literal(groupB) + ")"
                + " GROUP BY 1";
        Map<String, Stats> groups = new LinkedHashMap<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                groups.put(result.getString("dap_group"), new Stats(result.getLong("dap_n"),
                        result.getObject("dap_mean") == null ? null : result.getDouble("dap_mean"),
                        result.getObject("dap_sd") == null ? null : result.getDouble("dap_sd")));
            }
        }
        return groups;
    }

    private static void requireSample(long n, String test, int minimum) {
        if (n < minimum) {
            throw new IllegalArgumentException("hypothesis_test: " + test + " needs at least " + minimum
                    + " non-null observations, got " + n);
        }
    }

    private static double tPValue(double t, double df, String alternative) {
        double p = switch (alternative) {
            case "less" -> Distributions.tCdf(t, df);
            case "greater" -> 1.0 - Distributions.tCdf(t, df);
            default -> 2.0 * (1.0 - Distributions.tCdf(Math.abs(t), df));
        };
        return clamp(p);
    }

    private static double clamp(double p) {
        return Math.max(0.0, Math.min(1.0, p));
    }

    private static String column(LazyPlan input, JsonNode config, String field) {
        String name = NodeSql.requiredText(config, field);
        try {
            return NodeSql.column(input.columns(), name).name();
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("hypothesis_test: unknown '" + field + "' column '" + name + "'");
        }
    }

    private static String numericColumn(LazyPlan input, JsonNode config, String field) {
        String name = NodeSql.requiredText(config, field);
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("hypothesis_test: unknown '" + field + "' column '" + name + "'");
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException("hypothesis_test: '" + field + "' column '" + name
                    + "' must be numeric, got " + column.arrowType());
        }
        return column.name();
    }

    private static double number(JsonNode config, String field, double fallback) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException("hypothesis_test: '" + field + "' must be a number");
        }
        return value.doubleValue();
    }

    private static void alpha(JsonNode config) {
        JsonNode value = config.get("alpha");
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0.0) || !(value.doubleValue() < 1.0)) {
            throw new IllegalArgumentException("hypothesis_test: 'alpha' must be a number in (0, 1)");
        }
    }

    private static String scalar(JsonNode config, String field) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isValueNode()) {
            throw new IllegalArgumentException("hypothesis_test: '" + field + "' must be a scalar value");
        }
        return value.asText();
    }
}
