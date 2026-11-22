package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.Distributions;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.Matrix;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.correlation: pairwise Pearson, Spearman or Kendall correlation in long or matrix
 * form, optionally partial on control columns.
 *
 * <p>Pearson/Spearman correlations come from SQL sufficient statistics (count, sums,
 * squares and cross products) computed by DuckDB in one scan; Spearman applies SQL
 * window ranks first. Only the p-by-p moment matrix lives in Java, so the row count is
 * unbounded. The p-value (t approximation, Kendall z approximation) and the confidence
 * interval (Fisher's z, Kendall asymptotic standard error) need the engine's own
 * distributions, so the pairs are materialised into a literal plan. Partial
 * correlations come from the inverse of the moment-based correlation matrix; Kendall
 * tau-b is computed in Java with a Fenwick tree over ranks (O(n log n)) and therefore
 * keeps an explicit row cap. The long format also carries {@code p_adjusted} with the
 * shared multiple-comparison correction; the matrix format has no p-values and is
 * therefore never adjusted.</p>
 */
public final class CorrelationExecutor implements NodeExecutor {
    private static final List<String> METHODS = List.of("pearson", "spearman", "kendall");
    private static final List<String> OUTPUTS = List.of("long", "matrix");
    /** Kendall reads values into Java; Pearson and Spearman do not. */
    static final int KENDALL_ROW_CAP = 100_000;
    private static final List<ColumnSpec> LONG = List.of(
            ColumnSpec.of("column_a", "Utf8"),
            ColumnSpec.of("column_b", "Utf8"),
            ColumnSpec.of("method", "Utf8"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("correlation", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"),
            ColumnSpec.of("partial", "Utf8"),
            ColumnSpec.of("p_adjusted", "Float64"));

    @Override
    public String nodeId() {
        return "dap.correlation";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> columns = columns(input.columns(), config, "columns");
        String method = method(config);
        List<ColumnSpec> controls = controls(input.columns(), columns, config);
        String output = NodeSql.text(config, "output", "long").trim().toLowerCase(Locale.ROOT);
        if (!OUTPUTS.contains(output)) {
            throw new IllegalArgumentException("correlation: unknown output '" + output
                    + "' (expected long or matrix)");
        }
        String correction = MultipleTestingCorrection.validate(
                NodeSql.text(config, "correction", "none"), "correlation");
        double alpha = alpha(config);
        boolean partial = !controls.isEmpty();
        List<ColumnSpec> all = new ArrayList<>(columns);
        all.addAll(controls);
        int sampleRows = config == null ? 0 : config.path("sample_rows").asInt(0);
        if (sampleRows < 0) {
            throw new IllegalArgumentException("correlation: 'sample_rows' must be 0 or positive");
        }
        int seed = config == null ? 42 : config.path("seed").asInt(42);
        if (seed < 0) {
            throw new IllegalArgumentException("correlation: 'seed' must be 0 or positive");
        }
        if ("matrix".equals(output)) {
            return matrix(context, input, columns, all, method, partial, sampleRows, seed);
        }
        return longFormat(context, input, columns, all, method, alpha, partial, correction,
                sampleRows, seed);
    }

    // ------------------------------------------------------------------- long

    private static LazyPlan longFormat(NodeContext context, LazyPlan input, List<ColumnSpec> columns,
                                       List<ColumnSpec> all, String method, double alpha,
                                       boolean partial, String correction, int sampleRows, int seed)
            throws SQLException {
        int requested = columns.size();
        List<Pair> pairs = new ArrayList<>();
        if ("kendall".equals(method)) {
            // Kendall needs the values: complete cases when partial (for the matrix
            // inverse), otherwise every row so that nulls can be dropped per pair.
            double[][] values = readValues(context, input, all, partial, sampleRows, seed);
            long commonN = values.length;
            double[][] matrix = kendallMatrix(values, all.size());
            for (int i = 0; i < requested; i++) {
                for (int j = i + 1; j < requested; j++) {
                    double correlation = partial
                            ? partialCorrelation(matrix, i, j, requested, all.size() - requested)
                            : kendallFromValues(values, i, j);
                    long n = partial ? commonN : finitePairs(values, i, j);
                    pairs.add(new Pair(i, j, correlation, n, kendallTieStats(values, i, j)));
                }
            }
        } else if (partial) {
            List<PairResult> results = pairQuery(context, input, all, method, true, sampleRows, seed);
            double[][] matrix = matrixFrom(results, all.size());
            long n = results.isEmpty() ? 0 : results.get(0).n();
            for (int i = 0; i < requested; i++) {
                for (int j = i + 1; j < requested; j++) {
                    pairs.add(new Pair(i, j,
                            partialCorrelation(matrix, i, j, requested, all.size() - requested), n,
                            null));
                }
            }
        } else {
            List<PairResult> results = pairQuery(context, input, columns, method, false,
                    sampleRows, seed);
            for (PairResult result : results) {
                pairs.add(new Pair(result.first(), result.second(), result.correlation(), result.n(),
                        null));
            }
        }
        List<Stats> statistics = new ArrayList<>(pairs.size());
        List<Double> pValues = new ArrayList<>(pairs.size());
        for (Pair pair : pairs) {
            Stats value = stats(method, pair.correlation(), pair.n(),
                    partial ? all.size() - requested : 0, alpha, pair.ties());
            statistics.add(value);
            pValues.add(value.pValue());
        }
        Double[] adjusted = MultipleTestingCorrection.adjust(pValues, correction);
        List<String> rows = new ArrayList<>(pairs.size());
        String partialValue = partial ? joinNames(controlsOf(all, requested)) : null;
        for (int i = 0; i < pairs.size(); i++) {
            Pair pair = pairs.get(i);
            rows.add(longRow(columns.get(pair.first()), columns.get(pair.second()), method,
                    statistics.get(i), adjusted[i], partialValue));
        }
        return literalPlan(context, rows, LONG);
    }

    private record Pair(int first, int second, double correlation, long n, KendallTieStats ties) {
    }

    private record Stats(double correlation, long n, Double pValue, Double ciLow, Double ciHigh) {
    }

    /**
     * p-value and confidence interval for one correlation: the t approximation for
     * Pearson/Spearman (df = n - 2 - controls), the tie-corrected normal z approximation
     * for Kendall; Fisher's z for the Pearson/Spearman interval and the tie-corrected
     * asymptotic standard error for Kendall. Degenerate samples leave the p-value and
     * interval NULL. Kendall never computes an exact permutation p-value.
     */
    private static Stats stats(String method, double rawCorrelation, long n, int controls,
                               double alpha, KendallTieStats ties) {
        double correlation = rawCorrelation;
        if (Double.isNaN(correlation)) {
            return new Stats(Double.NaN, n, null, null, null);
        }
        correlation = Math.max(-1.0, Math.min(1.0, correlation));
        Double pValue = null;
        Double ciLow = null;
        Double ciHigh = null;
        double critical = Distributions.normalQuantile(1.0 - alpha / 2.0);
        if ("kendall".equals(method)) {
            if (n >= 3 && ties != null) {
                double pairs = n * (n - 1.0) / 2.0;
                double m = n * (n - 1.0);
                // scipy.stats._stats_py::_kendalltau, method='asymptotic' (v0/vt ->
                // v1/v2 -> var in count_rank_tie): var of concordant minus discordant
                // pairs; xtie/x0/x1 and ytie/y0/y1 are the two columns' tie sums.
                double variance = (m * (2.0 * n + 5.0) - ties.x1() - ties.y1()) / 18.0
                        + 2.0 * ties.xtie() * ties.ytie() / m
                        + ties.x0() * ties.y0() / (9.0 * m * (n - 2.0));
                double denominator = (pairs - ties.xtie()) * (pairs - ties.ytie());
                if (variance > 0.0 && denominator > 0.0) {
                    // tau-b scale: z = tau / se, with se = sqrt(var / denominator).
                    double standardError = Math.sqrt(variance / denominator);
                    double z = correlation / standardError;
                    pValue = clamp(2.0 * (1.0 - Distributions.normalCdf(Math.abs(z))));
                    ciLow = clip(correlation - critical * standardError);
                    ciHigh = clip(correlation + critical * standardError);
                }
            }
        } else {
            double df = n - 2.0 - controls;
            if (df > 0.0) {
                pValue = Math.abs(correlation) >= 1.0
                        ? 0.0
                        : clamp(2.0 * (1.0 - Distributions.tCdf(
                                Math.abs(correlation) * Math.sqrt(df / (1.0 - correlation * correlation)), df)));
            }
            double intervalDf = n - 3.0 - controls;
            if (intervalDf > 0.0) {
                if (Math.abs(correlation) >= 1.0) {
                    ciLow = correlation;
                    ciHigh = correlation;
                } else {
                    double z = fisherZ(correlation);
                    double standardError = 1.0 / Math.sqrt(intervalDf);
                    ciLow = inverseFisherZ(z - critical * standardError);
                    ciHigh = inverseFisherZ(z + critical * standardError);
                }
            }
        }
        return new Stats(correlation, n, pValue, ciLow, ciHigh);
    }

    // ----------------------------------------------------------------- matrix

    private static LazyPlan matrix(NodeContext context, LazyPlan input, List<ColumnSpec> columns,
                                   List<ColumnSpec> all, String method, boolean partial,
                                   int sampleRows, int seed) throws SQLException {
        double[][] matrix;
        long n;
        if ("kendall".equals(method)) {
            double[][] values = readValues(context, input, all, true, sampleRows, seed);
            n = values.length;
            matrix = kendallMatrix(values, all.size());
        } else {
            List<PairResult> results = pairQuery(context, input, all, method, true, sampleRows, seed);
            matrix = matrixFrom(results, all.size());
            n = results.isEmpty()
                    ? countComplete(context, input, all, sampleRows, seed) : results.get(0).n();
        }
        if (partial) {
            matrix = partialMatrix(matrix, columns.size(), all.size() - columns.size());
        }
        List<String> rows = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            rows.add(matrixRow(columns.get(i).name(), matrix, i, columns));
        }
        List<ColumnSpec> output = new ArrayList<>();
        output.add(ColumnSpec.of("column", "Utf8"));
        output.addAll(columns);
        return literalPlan(context, rows, output);
    }

    // -------------------------------------------------------------------- SQL

    private record PairResult(int first, int second, double correlation, long n) {
    }

    private static List<PairResult> pairQuery(NodeContext context, LazyPlan input,
                                              List<ColumnSpec> columns, String method,
                                              boolean completeCase, int sampleRows, int seed)
            throws SQLException {
        List<PairResult> results = new ArrayList<>();
        String source = sampledSource(input, sampleRows, seed);
        if (!"spearman".equals(method)) {
            // One scan yields every pair's n, sums, squares and cross product.
            List<String> expressions = new ArrayList<>(columns.size());
            for (ColumnSpec column : columns) {
                expressions.add(MomentSupport.doubleColumn(column.name()));
            }
            String fromWhere = " FROM " + source + " WHERE "
                    + (completeCase ? completeCaseWhere(columns) : "TRUE");
            MomentSupport.Moments moments = MomentSupport.read(context, "", fromWhere,
                    expressions, false, List.of()).values().iterator().next();
            boolean pairwise = !moments.complete();
            if (pairwise) {
                // NULLs present: recompute the five sums per pair on pair-complete rows.
                moments = MomentSupport.read(context, "", fromWhere, expressions, true, List.of())
                        .values().iterator().next();
            }
            for (int i = 0; i < columns.size(); i++) {
                for (int j = i + 1; j < columns.size(); j++) {
                    results.add(new PairResult(i, j,
                            pairwise ? moments.correlation(i, j) : moments.centeredCorrelation(i, j),
                            pairwise ? moments.count(i, j) : moments.count()));
                }
            }
            return results;
        }
        if (completeCase) {
            // All pairs share one complete-case row set, so rank every column once.
            String with = spearmanCte(columns, completeCaseWhere(columns), source);
            List<String> ranks = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                ranks.add(NodeSql.quote("dap_v" + i));
            }
            MomentSupport.Moments moments = MomentSupport.read(context, with, " FROM dap_ranked",
                    ranks, true, List.of()).values().iterator().next();
            for (int i = 0; i < columns.size(); i++) {
                for (int j = i + 1; j < columns.size(); j++) {
                    results.add(new PairResult(i, j, moments.correlation(i, j), moments.count(i, j)));
                }
            }
            return results;
        }
        // Pairwise deletion: ranks depend on the pair's complete rows, so rank per pair.
        for (int i = 0; i < columns.size(); i++) {
            for (int j = i + 1; j < columns.size(); j++) {
                List<ColumnSpec> pair = List.of(columns.get(i), columns.get(j));
                String with = spearmanCte(pair, pairWhere(pair.get(0), pair.get(1)), source);
                MomentSupport.Moments moments = MomentSupport.read(context, with, " FROM dap_ranked",
                        List.of(NodeSql.quote("dap_v0"), NodeSql.quote("dap_v1")), true, List.of())
                        .values().iterator().next();
                results.add(new PairResult(i, j, moments.correlation(0, 1), moments.count(0, 1)));
            }
        }
        return results;
    }

    /** Average rank per column: RANK() shares the minimum rank, the offset restores ties. */
    private static String spearmanCte(List<ColumnSpec> columns, String where, String source) {
        StringJoiner select = new StringJoiner(", ");
        for (int i = 0; i < columns.size(); i++) {
            String value = MomentSupport.doubleColumn(columns.get(i).name());
            select.add(value + " AS " + NodeSql.quote("dap_raw" + i));
        }
        StringJoiner ranks = new StringJoiner(", ");
        for (int i = 0; i < columns.size(); i++) {
            String value = MomentSupport.doubleColumn(columns.get(i).name());
            ranks.add("RANK() OVER (ORDER BY " + value + ") + (COUNT(*) OVER (PARTITION BY " + value
                    + ") - 1) / 2.0 AS " + NodeSql.quote("dap_v" + i));
        }
        return "WITH dap_ranked AS (SELECT " + select + ", " + ranks + " FROM " + source
                + " WHERE " + where + ") ";
    }

    /** Optional deterministic reservoir sample, applied before any null filtering. */
    private static String sampledSource(LazyPlan input, int sampleRows, int seed) {
        if (sampleRows <= 0) {
            return input.asTable() + " AS dap_input";
        }
        return "(SELECT * FROM " + input.asTable() + " AS dap_src USING SAMPLE reservoir("
                + sampleRows + " ROWS) REPEATABLE(" + seed + ")) AS dap_input";
    }

    private static long countComplete(NodeContext context, LazyPlan input, List<ColumnSpec> columns,
                                      int sampleRows, int seed) throws SQLException {
        String sql = "SELECT COUNT(*) AS \"n\" FROM " + sampledSource(input, sampleRows, seed)
                + " WHERE " + completeCaseWhere(columns);
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong("n") : 0;
        }
    }

    private static String pairWhere(ColumnSpec a, ColumnSpec b) {
        return NodeSql.quote(a.name()) + " IS NOT NULL AND " + NodeSql.quote(b.name()) + " IS NOT NULL";
    }

    private static String completeCaseWhere(List<ColumnSpec> columns) {
        StringJoiner joiner = new StringJoiner(" AND ");
        for (ColumnSpec column : columns) {
            joiner.add(NodeSql.quote(column.name()) + " IS NOT NULL");
        }
        return joiner.toString();
    }

    /** Pair results to a symmetric matrix; the diagonal is 1 when the sample allows it. */
    private static double[][] matrixFrom(List<PairResult> results, int size) {
        double[][] matrix = new double[size][size];
        for (double[] row : matrix) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        long n = results.isEmpty() ? 0 : results.get(0).n();
        for (PairResult result : results) {
            matrix[result.first()][result.second()] = result.correlation();
            matrix[result.second()][result.first()] = result.correlation();
        }
        for (int i = 0; i < size; i++) {
            matrix[i][i] = n >= 2 ? 1.0 : Double.NaN;
        }
        return matrix;
    }

    // --------------------------------------------------------------- partial

    /** Partial correlations from the inverse of the correlation matrix (requested x controls). */
    private static double[][] partialMatrix(double[][] matrix, int requested, int controls) {
        double[][] result = new double[requested][requested];
        for (double[] row : result) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        for (int i = 0; i < requested; i++) {
            for (int j = i + 1; j < requested; j++) {
                double correlation = partialCorrelation(matrix, i, j, requested, controls);
                result[i][j] = correlation;
                result[j][i] = correlation;
            }
        }
        for (int i = 0; i < requested; i++) {
            result[i][i] = matrix[i][i];
        }
        return result;
    }

    private static double partialCorrelation(double[][] matrix, int first, int second,
                                             int requested, int controls) {
        int size = 2 + controls;
        double[][] sub = new double[size][size];
        int[] index = new int[size];
        index[0] = first;
        index[1] = second;
        for (int c = 0; c < controls; c++) {
            index[2 + c] = requested + c;
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                double value = matrix[index[i]][index[j]];
                if (Double.isNaN(value)) {
                    return Double.NaN;
                }
                sub[i][j] = value;
            }
        }
        double[][] inverse;
        try {
            inverse = Matrix.invert(sub);
        } catch (IllegalArgumentException singular) {
            return Double.NaN;
        }
        double denominator = Math.sqrt(inverse[0][0] * inverse[1][1]);
        return denominator == 0.0 ? Double.NaN : -inverse[0][1] / denominator;
    }

    // --------------------------------------------------------------- Kendall

    /** Tie sums for one column: xtie, x0, x1 in scipy's count_rank_tie notation. */
    private record KendallTieStats(double xtie, double ytie, double x0, double y0,
                                   double x1, double y1) {
    }

    /** Tie sums of both columns over the pair's complete rows. */
    private static KendallTieStats kendallTieStats(double[][] values, int first, int second) {
        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (double[] row : values) {
            if (!Double.isNaN(row[first]) && !Double.isNaN(row[second])) {
                xs.add(row[first]);
                ys.add(row[second]);
            }
        }
        double[] x = tieMoments(xs);
        double[] y = tieMoments(ys);
        return new KendallTieStats(x[0], y[0], x[1], y[1], x[2], y[2]);
    }

    private static double[] tieMoments(List<Double> column) {
        Map<Double, Long> counts = new HashMap<>();
        for (double value : column) {
            counts.merge(value, 1L, Long::sum);
        }
        double pairs = 0.0;
        double v0 = 0.0;
        double v1 = 0.0;
        for (long size : counts.values()) {
            if (size > 1) {
                pairs += size * (size - 1) / 2.0;
                v0 += size * (size - 1) * (size - 2);
                v1 += size * (size - 1) * (2 * size + 5);
            }
        }
        return new double[]{pairs, v0, v1};
    }

    /**
     * Kendall tau-b between two columns; NaN when fewer than three complete pairs or a
     * zero denominator. Ties are corrected with the usual (n0 - n1)(n0 - n2) denominator.
     */
    private static double kendallFromValues(double[][] values, int first, int second) {
        int n = 0;
        for (double[] row : values) {
            if (!Double.isNaN(row[first]) && !Double.isNaN(row[second])) {
                n++;
            }
        }
        if (n < 3) {
            return Double.NaN;
        }
        double[] x = new double[n];
        double[] y = new double[n];
        int at = 0;
        for (double[] row : values) {
            if (!Double.isNaN(row[first]) && !Double.isNaN(row[second])) {
                x[at] = row[first];
                y[at] = row[second];
                at++;
            }
        }
        return kendallTau(x, y);
    }

    private static long finitePairs(double[][] values, int first, int second) {
        long n = 0;
        for (double[] row : values) {
            if (!Double.isNaN(row[first]) && !Double.isNaN(row[second])) {
                n++;
            }
        }
        return n;
    }

    private static double[][] kendallMatrix(double[][] values, int size) {
        double[][] matrix = new double[size][size];
        for (double[] row : matrix) {
            java.util.Arrays.fill(row, Double.NaN);
        }
        for (int i = 0; i < size; i++) {
            for (int j = i + 1; j < size; j++) {
                double tau = kendallFromValues(values, i, j);
                matrix[i][j] = tau;
                matrix[j][i] = tau;
            }
            matrix[i][i] = 1.0;
        }
        return matrix;
    }

    /**
     * Kendall tau-b in O(n log n): sort by x, then count concordant/discordant pairs with
     * a Fenwick tree over the ranks of y, processing equal-x groups together so that
     * x-ties are neither concordant nor discordant.
     */
    static double kendallTau(double[] x, double[] y) {
        int n = x.length;
        if (n < 3) {
            return Double.NaN;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (left, right) -> {
            int byX = Double.compare(x[left], x[right]);
            return byX != 0 ? byX : Double.compare(y[left], y[right]);
        });
        double[] sortedY = new double[n];
        for (int i = 0; i < n; i++) {
            sortedY[i] = y[order[i]];
        }
        double[] unique = new double[n];
        int uniqueCount = 0;
        for (int i = 0; i < n; i++) {
            if (i == 0 || sortedY[i] != sortedY[i - 1]) {
                unique[uniqueCount++] = sortedY[i];
            }
        }
        // lowerBound needs ascending values, but unique was collected in x order.
        java.util.Arrays.sort(unique, 0, uniqueCount);
        int[] rank = new int[n];
        for (int i = 0; i < n; i++) {
            rank[i] = lowerBound(unique, uniqueCount, sortedY[i]) + 1;
        }
        int[] tree = new int[uniqueCount + 1];
        long concordant = 0;
        long discordant = 0;
        long processed = 0;
        int at = 0;
        while (at < n) {
            int end = at;
            double groupX = x[order[at]];
            while (end < n && x[order[end]] == groupX) {
                end++;
            }
            for (int i = at; i < end; i++) {
                int r = rank[i];
                concordant += query(tree, r - 1);
                discordant += processed - query(tree, r);
            }
            for (int i = at; i < end; i++) {
                add(tree, rank[i]);
            }
            processed += end - at;
            at = end;
        }
        double pairs = (double) n * (n - 1) / 2.0;
        double tiesX = 0.0;
        at = 0;
        while (at < n) {
            int end = at;
            double groupX = x[order[at]];
            while (end < n && x[order[end]] == groupX) {
                end++;
            }
            double group = end - at;
            tiesX += group * (group - 1) / 2.0;
            at = end;
        }
        // Equal y values are not adjacent in x order, so count them by dense rank.
        int[] yCounts = new int[uniqueCount + 1];
        for (int r : rank) {
            yCounts[r]++;
        }
        double tiesY = 0.0;
        for (int count : yCounts) {
            tiesY += (double) count * (count - 1) / 2.0;
        }
        double denominator = Math.sqrt((pairs - tiesX) * (pairs - tiesY));
        return denominator == 0.0 ? Double.NaN : (concordant - discordant) / denominator;
    }

    private static int lowerBound(double[] sorted, int length, double value) {
        int low = 0;
        int high = length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static void add(int[] tree, int index) {
        for (int i = index; i < tree.length; i += i & -i) {
            tree[i]++;
        }
    }

    private static int query(int[] tree, int index) {
        int sum = 0;
        for (int i = index; i > 0; i -= i & -i) {
            sum += tree[i];
        }
        return sum;
    }

    private static double[][] readValues(NodeContext context, LazyPlan input, List<ColumnSpec> columns,
                                         boolean completeCase, int sampleRows, int seed)
            throws SQLException {
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : columns) {
            projection.add(MomentSupport.doubleColumn(column.name()));
        }
        String sql = "SELECT " + projection + " FROM " + sampledSource(input, sampleRows, seed)
                + (completeCase ? " WHERE " + completeCaseWhere(columns) : "")
                + " LIMIT " + (KENDALL_ROW_CAP + 1);
        List<double[]> rows = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                double[] row = new double[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    Object value = result.getObject(i + 1);
                    row[i] = value == null ? Double.NaN : ((Number) value).doubleValue();
                }
                rows.add(row);
            }
        }
        if (rows.size() > KENDALL_ROW_CAP) {
            throw new IllegalArgumentException("correlation: method=kendall supports at most "
                    + KENDALL_ROW_CAP + " rows, got more; use pearson/spearman or sample_rows=N "
                    + "(deterministic reservoir sample) instead");
        }
        return rows.toArray(new double[0][]);
    }

    // --------------------------------------------------------------- output

    private static String longRow(ColumnSpec a, ColumnSpec b, String method, Stats stats,
                                  Double pAdjusted, String partialValue) {
        StringJoiner select = new StringJoiner(", ");
        select.add(NodeSql.literal(a.name()) + " AS " + NodeSql.quote("column_a"));
        select.add(NodeSql.literal(b.name()) + " AS " + NodeSql.quote("column_b"));
        select.add(NodeSql.literal(method) + " AS " + NodeSql.quote("method"));
        select.add(stats.n() + " AS " + NodeSql.quote("n"));
        select.add(doubleLiteral(stats.correlation()) + " AS " + NodeSql.quote("correlation"));
        select.add(doubleLiteral(stats.pValue()) + " AS " + NodeSql.quote("p_value"));
        select.add(doubleLiteral(stats.ciLow()) + " AS " + NodeSql.quote("ci_low"));
        select.add(doubleLiteral(stats.ciHigh()) + " AS " + NodeSql.quote("ci_high"));
        select.add(partialValue == null
                ? "CAST(NULL AS VARCHAR) AS " + NodeSql.quote("partial")
                : NodeSql.literal(partialValue) + " AS " + NodeSql.quote("partial"));
        select.add(doubleLiteral(pAdjusted) + " AS " + NodeSql.quote("p_adjusted"));
        return "SELECT " + select;
    }

    private static String matrixRow(String name, double[][] matrix, int row, List<ColumnSpec> columns) {
        StringJoiner select = new StringJoiner(", ");
        select.add(NodeSql.literal(name) + " AS " + NodeSql.quote("column"));
        for (int i = 0; i < columns.size(); i++) {
            select.add(doubleLiteral(matrix[row][i]) + " AS " + NodeSql.quote(columns.get(i).name()));
        }
        return "SELECT " + select;
    }

    private static LazyPlan literalPlan(NodeContext context, List<String> rows,
                                        List<ColumnSpec> columns) {
        if (rows.isEmpty()) {
            return new LazyPlan(context.db(), emptyPlan(columns), columns);
        }
        return new LazyPlan(context.db(), String.join(" UNION ALL ", rows), columns);
    }

    private static String emptyPlan(List<ColumnSpec> columns) {
        StringJoiner select = new StringJoiner(", ");
        for (ColumnSpec column : columns) {
            select.add("CAST(NULL AS " + column.duckDbType() + ") AS " + NodeSql.quote(column.name()));
        }
        return "SELECT " + select + " WHERE FALSE";
    }

    private static String doubleLiteral(Double value) {
        return value == null || !Double.isFinite(value)
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
    }

    private static String joinNames(List<ColumnSpec> columns) {
        StringJoiner joiner = new StringJoiner(",");
        for (ColumnSpec column : columns) {
            joiner.add(column.name());
        }
        return joiner.toString();
    }

    private static List<ColumnSpec> controlsOf(List<ColumnSpec> all, int requested) {
        return all.subList(requested, all.size());
    }

    private static double fisherZ(double correlation) {
        return 0.5 * Math.log((1.0 + correlation) / (1.0 - correlation));
    }

    private static double inverseFisherZ(double z) {
        double exponential = Math.exp(2.0 * z);
        return (exponential - 1.0) / (exponential + 1.0);
    }

    private static double clip(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static double clamp(double probability) {
        return Math.max(0.0, Math.min(1.0, probability));
    }

    // ------------------------------------------------------------- validation

    private static List<ColumnSpec> columns(List<ColumnSpec> available, JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull() || !node.isArray()) {
            throw new IllegalArgumentException("correlation: 'columns' must be an array of at least two numeric column names");
        }
        if (node.size() < 2) {
            throw new IllegalArgumentException("correlation: 'columns' must list at least two numeric columns");
        }
        List<ColumnSpec> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("correlation: 'columns' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("correlation: duplicate column '" + name + "'");
            }
            ColumnSpec column = numericColumn(available, name, "columns");
            columns.add(column);
        }
        return columns;
    }

    private static List<ColumnSpec> controls(List<ColumnSpec> available, List<ColumnSpec> columns,
                                             JsonNode config) {
        JsonNode node = config.get("partial");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("correlation: 'partial' must be an array of control column names");
        }
        List<ColumnSpec> controls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("correlation: 'partial' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("correlation: duplicate partial column '" + name + "'");
            }
            for (ColumnSpec column : columns) {
                if (column.name().equals(name)) {
                    throw new IllegalArgumentException("correlation: 'partial' column '" + name
                            + "' is already in 'columns'");
                }
            }
            controls.add(numericColumn(available, name, "partial"));
        }
        return controls;
    }

    private static ColumnSpec numericColumn(List<ColumnSpec> available, String name, String field) {
        ColumnSpec column;
        try {
            column = NodeSql.column(available, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("correlation: unknown '" + field + "' column '" + name + "'");
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException("correlation: '" + field + "' column '" + name
                    + "' is not numeric (" + column.arrowType() + ")");
        }
        return column;
    }

    private static String method(JsonNode config) {
        String method = NodeSql.text(config, "method", "pearson").trim().toLowerCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("correlation: unknown method '" + method
                    + "' (expected pearson, spearman or kendall)");
        }
        return method;
    }

    private static double alpha(JsonNode config) {
        JsonNode value = config.get("alpha");
        if (value == null || value.isNull()) {
            return 0.05;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0.0) || !(value.doubleValue() < 1.0)) {
            throw new IllegalArgumentException("correlation: 'alpha' must be a number in (0, 1)");
        }
        return value.doubleValue();
    }
}
