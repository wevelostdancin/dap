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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * dap.hypothesis_test: one or more hypothesis tests over the input.
 *
 * <p>Parametric tests (Welch and paired t, one-sample t, chi-square, one-way ANOVA) and
 * Tukey's HSD compute their statistics with DuckDB aggregates; the rank tests
 * (Mann-Whitney, Kruskal-Wallis, Wilcoxon signed rank) read the values and rank them in
 * Java with the usual tie correction and normal approximation. p-values come from
 * {@link Distributions} because DuckDB has no erf/beta and the studentized range CDF is
 * not built in either. Every test reports an effect size, and the requested multiple
 * comparison correction fills {@code p_adjusted}.</p>
 */
public final class HypothesisTestExecutor implements NodeExecutor {
    private static final List<String> TESTS = List.of(
            "t_test_two_sample", "t_test_paired", "t_test_one_sample", "chi_square", "anova",
            "mann_whitney", "kruskal_wallis", "wilcoxon_signed_rank", "tukey_hsd");
    private static final List<String> ALTERNATIVES = List.of("two-sided", "less", "greater");
    /** Rank tests and Tukey read values into Java; same cap as the other in-memory statistics. */
    private static final int ROW_CAP = 100_000;
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
        double alpha = alpha(config);
        String correction = MultipleTestingCorrection.validate(
                NodeSql.text(config, "correction", "none"), "hypothesis_test");
        List<Row> rows = switch (test) {
            case "t_test_one_sample" -> List.of(oneSample(context, input, config, alternative, alpha));
            case "t_test_two_sample" -> List.of(twoSample(context, input, config, alternative, alpha));
            case "t_test_paired" -> List.of(paired(context, input, config, alternative, alpha));
            case "chi_square" -> List.of(chiSquare(context, input, config, alpha));
            case "anova" -> List.of(anova(context, input, config, alpha));
            case "mann_whitney" -> List.of(mannWhitney(context, input, config, alternative));
            case "kruskal_wallis" -> List.of(kruskalWallis(context, input, config));
            case "wilcoxon_signed_rank" -> List.of(wilcoxon(context, input, config, alternative));
            default -> tukey(context, input, config, alpha);
        };
        List<Double> pValues = new ArrayList<>();
        for (Row row : rows) {
            pValues.add(row.pValue());
        }
        Double[] adjusted = MultipleTestingCorrection.adjust(pValues, correction);
        return new LazyPlan(context.db(), sql(rows, adjusted), OUTPUT);
    }

    /**
     * One output row. {@code statistic}, {@code df}, {@code pValue}, the effect size and
     * the interval are NULL when the test is undefined (zero variance, too few pairs, a
     * degenerate correlation matrix).
     */
    private record Row(String test, String comparison, Double statistic, Double df, Double pValue,
                       Double effectSize, String effectSizeName, Double ciLow, Double ciHigh,
                       long n) {
    }

    private record Stats(long n, Double mean, Double sd) {
    }

    private record TwoGroups(String first, String second) {
    }

    private record GroupStats(String name, long n, double mean, double variance) {
    }

    // ------------------------------------------------------------- t tests

    private static Row oneSample(NodeContext context, LazyPlan input, JsonNode config,
                                 String alternative, double alpha) throws SQLException {
        String column = numericColumn(input, config, "value");
        String expression = "CAST(" + NodeSql.quote(column) + " AS DOUBLE)";
        Stats stats = stats(context, input, expression, null);
        requireSample(stats.n(), "t_test_one_sample", 2);
        double mu = number(config, "mu", 0.0);
        String comparison = column + " vs " + formatNumber(mu);
        if (stats.sd() == null || stats.sd() == 0.0) {
            return new Row("t_test_one_sample", comparison, null, stats.n() - 1.0, null,
                    null, "cohens_d", null, null, stats.n());
        }
        double t = (stats.mean() - mu) / (stats.sd() / Math.sqrt(stats.n()));
        double df = stats.n() - 1.0;
        Double d = effect(stats.mean() - mu, stats.sd());
        double[] interval = effectInterval(d,
                d == null ? 0.0 : Math.sqrt(1.0 / stats.n() + d * d / (2.0 * stats.n())), df, alpha);
        return new Row("t_test_one_sample", comparison, t, df, tPValue(t, df, alternative),
                d, "cohens_d", interval[0], interval[1], stats.n());
    }

    private static Row twoSample(NodeContext context, LazyPlan input, JsonNode config,
                                 String alternative, double alpha) throws SQLException {
        String column = numericColumn(input, config, "value");
        String group = column(input, config, "group");
        String expression = "CAST(" + NodeSql.quote(column) + " AS DOUBLE)";
        TwoGroups groups = twoGroups(context, input, group, config, "t_test_two_sample");
        Map<String, Stats> groupStats = groupStats(context, input, NodeSql.quote(group), expression,
                groups.first(), groups.second());
        Stats first = groupStats.get(groups.first());
        Stats second = groupStats.get(groups.second());
        requireSample(first == null ? 0 : first.n(),
                "t_test_two_sample group '" + groups.first() + "'", 2);
        requireSample(second == null ? 0 : second.n(),
                "t_test_two_sample group '" + groups.second() + "'", 2);
        String comparison = groups.first() + " vs " + groups.second();
        long n = first.n() + second.n();
        double var1 = first.sd() * first.sd();
        double var2 = second.sd() * second.sd();
        double term1 = var1 / first.n();
        double term2 = var2 / second.n();
        double standardError = Math.sqrt(term1 + term2);
        if (standardError == 0.0) {
            return new Row("t_test_two_sample", comparison, null, null, null, null, "cohens_d",
                    null, null, n);
        }
        double t = (first.mean() - second.mean()) / standardError;
        double spread = term1 * term1 / (first.n() - 1.0) + term2 * term2 / (second.n() - 1.0);
        if (spread == 0.0) {
            return new Row("t_test_two_sample", comparison, null, null, null, null, "cohens_d",
                    null, null, n);
        }
        double df = (term1 + term2) * (term1 + term2) / spread;
        double pooledVariance = ((first.n() - 1.0) * var1 + (second.n() - 1.0) * var2)
                / (first.n() + second.n() - 2.0);
        Double d = effect(first.mean() - second.mean(), Math.sqrt(pooledVariance));
        double[] interval = effectInterval(d, d == null ? 0.0
                : Math.sqrt((first.n() + second.n()) / ((double) first.n() * second.n())
                        + d * d / (2.0 * (first.n() + second.n()))),
                first.n() + second.n() - 2.0, alpha);
        return new Row("t_test_two_sample", comparison, t, df, tPValue(t, df, alternative),
                d, "cohens_d", interval[0], interval[1], n);
    }

    private static Row paired(NodeContext context, LazyPlan input, JsonNode config,
                              String alternative, double alpha) throws SQLException {
        String first = numericColumn(input, config, "group_a");
        String second = numericColumn(input, config, "group_b");
        String expression = "(CAST(" + NodeSql.quote(first) + " AS DOUBLE) - CAST("
                + NodeSql.quote(second) + " AS DOUBLE))";
        String where = NodeSql.quote(first) + " IS NOT NULL AND " + NodeSql.quote(second) + " IS NOT NULL";
        Stats stats = stats(context, input, expression, where);
        requireSample(stats.n(), "t_test_paired", 2);
        String comparison = first + " vs " + second;
        if (stats.sd() == null || stats.sd() == 0.0) {
            return new Row("t_test_paired", comparison, null, stats.n() - 1.0, null,
                    null, "cohens_dz", null, null, stats.n());
        }
        double t = stats.mean() / (stats.sd() / Math.sqrt(stats.n()));
        double df = stats.n() - 1.0;
        Double dz = effect(stats.mean(), stats.sd());
        double[] interval = effectInterval(dz,
                dz == null ? 0.0 : Math.sqrt(1.0 / stats.n() + dz * dz / (2.0 * stats.n())), df, alpha);
        return new Row("t_test_paired", comparison, t, df, tPValue(t, df, alternative),
                dz, "cohens_dz", interval[0], interval[1], stats.n());
    }

    private static Row chiSquare(NodeContext context, LazyPlan input, JsonNode config, double alpha)
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
        double cramersV = Math.sqrt(statistic
                / (n * (double) Math.min(rowTotals.size() - 1, columnTotals.size() - 1)));
        return new Row("chi_square", value + " vs " + group, statistic, df,
                clamp(1.0 - Distributions.chiSquareCdf(statistic, df)), cramersV, "cramers_v",
                null, null, n);
    }

    private static Row anova(NodeContext context, LazyPlan input, JsonNode config, double alpha)
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
            return new Row("anova", value + " ~ " + group, null, df1, null, null, "eta_squared",
                    null, null, total);
        }
        double statistic = (between / df1) / (within / df2);
        double etaSquared = between / (between + within);
        return new Row("anova", value + " ~ " + group, statistic, df1,
                clamp(1.0 - Distributions.fCdf(statistic, df1, df2)), etaSquared, "eta_squared",
                null, null, total);
    }

    // ------------------------------------------------------- nonparametric

    private static Row mannWhitney(NodeContext context, LazyPlan input, JsonNode config,
                                   String alternative) throws SQLException {
        String value = numericColumn(input, config, "value");
        String group = column(input, config, "group");
        TwoGroups groups = twoGroups(context, input, group, config, "mann_whitney");
        List<ValueRow> rows = groupedValues(context, input, group, value);
        double[] values = new double[rows.size()];
        String[] labels = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            values[i] = rows.get(i).value();
            labels[i] = rows.get(i).group();
        }
        long first = 0;
        long second = 0;
        double rankSum = 0.0;
        double[] ranks = averageRanks(values);
        for (int i = 0; i < values.length; i++) {
            if (groups.first().equals(labels[i])) {
                first++;
                rankSum += ranks[i];
            } else if (groups.second().equals(labels[i])) {
                second++;
            }
        }
        requireSample(first, "mann_whitney group '" + groups.first() + "'", 2);
        requireSample(second, "mann_whitney group '" + groups.second() + "'", 2);
        long n = first + second;
        String comparison = groups.first() + " vs " + groups.second();
        double statistic = rankSum - first * (first + 1) / 2.0;
        double mean = first * second / 2.0;
        double ties = tieSum(values);
        double variance = first * second / 12.0 * ((n + 1.0) - ties / (n * (n - 1.0)));
        double rankBiserial = 2.0 * statistic / (first * second) - 1.0;
        if (!(variance > 0.0)) {
            return new Row("mann_whitney", comparison, statistic, null, null, rankBiserial,
                    "rank_biserial", null, null, n);
        }
        double z = statistic - mean;
        z = z > 0.0 ? z - 0.5 : z + 0.5;
        z /= Math.sqrt(variance);
        double p = switch (alternative) {
            case "less" -> Distributions.normalCdf(z);
            case "greater" -> 1.0 - Distributions.normalCdf(z);
            default -> 2.0 * (1.0 - Distributions.normalCdf(Math.abs(z)));
        };
        return new Row("mann_whitney", comparison, statistic, null, clamp(p), rankBiserial,
                "rank_biserial", null, null, n);
    }

    private static Row kruskalWallis(NodeContext context, LazyPlan input, JsonNode config)
            throws SQLException {
        String value = numericColumn(input, config, "value");
        String group = column(input, config, "group");
        List<ValueRow> rows = groupedValues(context, input, group, value);
        double[] values = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            values[i] = rows.get(i).value();
        }
        double[] ranks = averageRanks(values);
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, Double> rankSums = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String label = rows.get(i).group();
            counts.computeIfAbsent(label, ignored -> new long[1])[0]++;
            rankSums.merge(label, ranks[i], Double::sum);
        }
        if (counts.size() < 2) {
            throw new IllegalArgumentException("hypothesis_test: kruskal_wallis needs at least two groups in '"
                    + group + "', got " + counts.size());
        }
        long n = values.length;
        if (n - counts.size() <= 0) {
            throw new IllegalArgumentException("hypothesis_test: kruskal_wallis needs more rows than groups, "
                    + "got " + n + " rows in " + counts.size() + " groups");
        }
        double sum = 0.0;
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            double rankSum = rankSums.get(entry.getKey());
            sum += rankSum * rankSum / entry.getValue()[0];
        }
        double statistic = 12.0 / (n * (n + 1.0)) * sum - 3.0 * (n + 1.0);
        double ties = tieSum(values);
        double correction = 1.0 - ties / ((double) n * n * n - n);
        if (correction > 0.0) {
            statistic /= correction;
        }
        double df = counts.size() - 1.0;
        double epsilonSquared = statistic / (n - 1.0);
        return new Row("kruskal_wallis", value + " ~ " + group, statistic, df,
                clamp(1.0 - Distributions.chiSquareCdf(statistic, df)), epsilonSquared,
                "epsilon_squared", null, null, n);
    }

    private static Row wilcoxon(NodeContext context, LazyPlan input, JsonNode config,
                                String alternative) throws SQLException {
        String first = numericColumn(input, config, "group_a");
        String second = numericColumn(input, config, "group_b");
        String quotedFirst = NodeSql.quote(first);
        String quotedSecond = NodeSql.quote(second);
        String sql = "SELECT CAST(" + quotedFirst + " AS DOUBLE) AS dap_a, CAST(" + quotedSecond
                + " AS DOUBLE) AS dap_b FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + quotedFirst + " IS NOT NULL AND " + quotedSecond + " IS NOT NULL"
                + " LIMIT " + (ROW_CAP + 1);
        List<Double> differences = new ArrayList<>();
        int read = 0;
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                read++;
                double difference = result.getDouble("dap_a") - result.getDouble("dap_b");
                if (difference != 0.0) {
                    differences.add(difference);
                }
            }
        }
        checkCap(read, "wilcoxon_signed_rank");
        int n = differences.size();
        requireSample(n, "wilcoxon_signed_rank", 2);
        double[] absolute = new double[n];
        for (int i = 0; i < n; i++) {
            absolute[i] = Math.abs(differences.get(i));
        }
        double[] ranks = averageRanks(absolute);
        double positive = 0.0;
        double negative = 0.0;
        for (int i = 0; i < n; i++) {
            if (differences.get(i) > 0.0) {
                positive += ranks[i];
            } else {
                negative += ranks[i];
            }
        }
        double mean = n * (n + 1.0) / 4.0;
        double variance = n * (n + 1.0) * (2.0 * n + 1.0) / 24.0 - tieSum(absolute) / 48.0;
        double rankBiserial = (positive - negative) / (positive + negative);
        String comparison = first + " vs " + second;
        if (!(variance > 0.0)) {
            return new Row("wilcoxon_signed_rank", comparison, positive, null, null, rankBiserial,
                    "rank_biserial", null, null, n);
        }
        double z = positive - mean;
        z = z > 0.0 ? z - 0.5 : z + 0.5;
        z /= Math.sqrt(variance);
        double p = switch (alternative) {
            case "less" -> Distributions.normalCdf(z);
            case "greater" -> 1.0 - Distributions.normalCdf(z);
            default -> 2.0 * (1.0 - Distributions.normalCdf(Math.abs(z)));
        };
        return new Row("wilcoxon_signed_rank", comparison, positive, null, clamp(p), rankBiserial,
                "rank_biserial", null, null, n);
    }

    // ---------------------------------------------------------------- tukey

    private static List<Row> tukey(NodeContext context, LazyPlan input, JsonNode config, double alpha)
            throws SQLException {
        String value = numericColumn(input, config, "value");
        String group = column(input, config, "group");
        String quotedValue = NodeSql.quote(value);
        String quotedGroup = NodeSql.quote(group);
        String sql = "SELECT CAST(" + quotedGroup + " AS VARCHAR) AS dap_group, COUNT(" + quotedValue
                + ") AS dap_n, AVG(CAST(" + quotedValue + " AS DOUBLE)) AS dap_mean,"
                + " VAR_SAMP(CAST(" + quotedValue + " AS DOUBLE)) AS dap_var"
                + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + quotedGroup + " IS NOT NULL AND " + quotedValue + " IS NOT NULL"
                + " GROUP BY 1 ORDER BY 1";
        List<GroupStats> groups = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                long n = result.getLong("dap_n");
                double variance = result.getObject("dap_var") == null ? 0.0 : result.getDouble("dap_var");
                groups.add(new GroupStats(result.getString("dap_group"), n,
                        result.getDouble("dap_mean"), variance));
            }
        }
        if (groups.size() < 2) {
            throw new IllegalArgumentException("hypothesis_test: tukey_hsd needs at least two groups in '"
                    + group + "', got " + groups.size());
        }
        long total = 0;
        double within = 0.0;
        for (GroupStats stats : groups) {
            if (stats.n() < 2) {
                throw new IllegalArgumentException("hypothesis_test: tukey_hsd needs at least 2 observations "
                        + "in group '" + stats.name() + "', got " + stats.n());
            }
            total += stats.n();
            within += (stats.n() - 1.0) * stats.variance();
        }
        double df = total - groups.size();
        if (df <= 0.0) {
            throw new IllegalArgumentException("hypothesis_test: tukey_hsd needs more rows than groups, got "
                    + total + " rows in " + groups.size() + " groups");
        }
        double mse = within / df;
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                GroupStats first = groups.get(i);
                GroupStats second = groups.get(j);
                String comparison = first.name() + " vs " + second.name();
                if (!(mse > 0.0)) {
                    rows.add(new Row("tukey_hsd", comparison, null, df, null, null, "cohens_d",
                            null, null, total));
                    continue;
                }
                double difference = first.mean() - second.mean();
                double standardError = Math.sqrt(mse / 2.0
                        * (1.0 / first.n() + 1.0 / second.n()));
                double q = Math.abs(difference) / standardError;
                double p = clamp(1.0 - Distributions.studentizedRangeCdf(q, groups.size(), df));
                double d = difference / Math.sqrt(mse);
                double dStandardError = Math.sqrt(1.0 / first.n() + 1.0 / second.n());
                double critical = Distributions.tQuantile(1.0 - alpha / 2.0, df);
                rows.add(new Row("tukey_hsd", comparison, q, df, p, d, "cohens_d",
                        d - critical * dStandardError, d + critical * dStandardError, total));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ SQL

    private static String sql(List<Row> rows, Double[] adjusted) {
        StringJoiner union = new StringJoiner("\nUNION ALL\n");
        for (int i = 0; i < rows.size(); i++) {
            union.add(rowSql(rows.get(i), adjusted[i]));
        }
        return union.toString();
    }

    private static String rowSql(Row row, Double adjusted) {
        StringJoiner select = new StringJoiner(", ");
        select.add(NodeSql.literal(row.test()) + " AS " + NodeSql.quote("test"));
        select.add(doubleLiteral(row.statistic()) + " AS " + NodeSql.quote("statistic"));
        select.add(doubleLiteral(row.df()) + " AS " + NodeSql.quote("df"));
        select.add(doubleLiteral(row.pValue()) + " AS " + NodeSql.quote("p_value"));
        select.add(row.n() + " AS " + NodeSql.quote("n"));
        select.add(NodeSql.literal(row.comparison()) + " AS " + NodeSql.quote("comparison"));
        select.add(doubleLiteral(adjusted) + " AS " + NodeSql.quote("p_adjusted"));
        select.add(doubleLiteral(row.effectSize()) + " AS " + NodeSql.quote("effect_size"));
        select.add(row.effectSizeName() == null
                ? "CAST(NULL AS VARCHAR) AS " + NodeSql.quote("effect_size_name")
                : NodeSql.literal(row.effectSizeName()) + " AS " + NodeSql.quote("effect_size_name"));
        select.add(doubleLiteral(row.ciLow()) + " AS " + NodeSql.quote("ci_low"));
        select.add(doubleLiteral(row.ciHigh()) + " AS " + NodeSql.quote("ci_high"));
        return "SELECT " + select;
    }

    private static String doubleLiteral(Double value) {
        return value == null || !Double.isFinite(value)
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
    }

    // -------------------------------------------------------------- helpers

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

    private static TwoGroups twoGroups(NodeContext context, LazyPlan input, String groupColumn,
                                       JsonNode config, String test) throws SQLException {
        String groupA = scalar(config, "group_a");
        String groupB = scalar(config, "group_b");
        if (groupA == null && groupB == null) {
            List<String> values = distinctGroups(context, input, NodeSql.quote(groupColumn));
            if (values.size() != 2) {
                throw new IllegalArgumentException("hypothesis_test: " + test + " needs exactly two "
                        + "groups, the column '" + groupColumn + "' has " + values.size()
                        + "; set group_a and group_b");
            }
            return new TwoGroups(values.get(0), values.get(1));
        }
        if (groupA == null || groupB == null) {
            throw new IllegalArgumentException("hypothesis_test: " + test
                    + " needs both group_a and group_b");
        }
        return new TwoGroups(groupA, groupB);
    }

    private record ValueRow(String group, double value) {
    }

    private static List<ValueRow> groupedValues(NodeContext context, LazyPlan input, String group,
                                                String value) throws SQLException {
        String sql = "SELECT CAST(" + NodeSql.quote(group) + " AS VARCHAR) AS dap_group,"
                + " CAST(" + NodeSql.quote(value) + " AS DOUBLE) AS dap_value"
                + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + NodeSql.quote(group) + " IS NOT NULL AND "
                + NodeSql.quote(value) + " IS NOT NULL"
                + " LIMIT " + (ROW_CAP + 1);
        List<ValueRow> rows = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                rows.add(new ValueRow(result.getString("dap_group"), result.getDouble("dap_value")));
            }
        }
        checkCap(rows.size(), "hypothesis_test");
        return rows;
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

    private static void checkCap(int rows, String test) {
        if (rows > ROW_CAP) {
            throw new IllegalArgumentException("hypothesis_test: " + test + " is computed in memory and "
                    + "needs at most " + ROW_CAP + " rows, got more");
        }
    }

    /** Average ranks with ties sharing the mean of the ranks they span. */
    static double[] averageRanks(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(index -> values[index]));
        double[] ranks = new double[n];
        int at = 0;
        while (at < n) {
            int end = at;
            while (end + 1 < n && values[order[end + 1]] == values[order[at]]) {
                end++;
            }
            double average = (at + end) / 2.0 + 1.0;
            for (int i = at; i <= end; i++) {
                ranks[order[i]] = average;
            }
            at = end + 1;
        }
        return ranks;
    }

    /** Sum of t^3 - t over the tie groups of the values (the usual rank-test correction). */
    static double tieSum(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double sum = 0.0;
        int at = 0;
        while (at < sorted.length) {
            int end = at;
            while (end + 1 < sorted.length && sorted[end + 1] == sorted[at]) {
                end++;
            }
            double size = end - at + 1.0;
            sum += size * size * size - size;
            at = end + 1;
        }
        return sum;
    }

    private static Double effect(double difference, double scale) {
        return scale > 0.0 ? difference / scale : null;
    }

    /** Cohen's d style interval: d +/- t(1 - alpha/2, df) * standard error. */
    private static double[] effectInterval(Double effect, double standardError, double df, double alpha) {
        if (effect == null || !(standardError > 0.0) || !(df > 0.0)) {
            return new double[]{Double.NaN, Double.NaN};
        }
        double critical = Distributions.tQuantile(1.0 - alpha / 2.0, df);
        return new double[]{effect - critical * standardError, effect + critical * standardError};
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

    private static double alpha(JsonNode config) {
        JsonNode value = config.get("alpha");
        if (value == null || value.isNull()) {
            return 0.05;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0.0) || !(value.doubleValue() < 1.0)) {
            throw new IllegalArgumentException("hypothesis_test: 'alpha' must be a number in (0, 1)");
        }
        return value.doubleValue();
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

    private static String formatNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
