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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.correlation: pairwise Pearson, Spearman or Kendall correlation in long or matrix
 * form, optionally partial on control columns.
 *
 * <p>Pearson/Spearman correlations and their counts come from DuckDB aggregates; the
 * p-value (t approximation, Kendall z approximation) and the confidence interval
 * (Fisher's z, Kendall asymptotic standard error) need the engine's own distributions,
 * so the pairs are materialised into a literal plan like {@code dap.hypothesis_test}
 * does. Partial correlations come from the inverse of the correlation matrix; Kendall
 * tau-b is computed in Java with a Fenwick tree over ranks (O(n log n)).</p>
 */
public final class CorrelationExecutor implements NodeExecutor {
    private static final List<String> METHODS = List.of("pearson", "spearman", "kendall");
    private static final List<String> OUTPUTS = List.of("long", "matrix");
    /** Kendall reads values into Java; the same cap as the other in-memory statistics. */
    private static final int ROW_CAP = 100_000;
    private static final List<ColumnSpec> LONG = List.of(
            ColumnSpec.of("column_a", "Utf8"),
            ColumnSpec.of("column_b", "Utf8"),
            ColumnSpec.of("method", "Utf8"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("correlation", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"),
            ColumnSpec.of("partial", "Utf8"));

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
        double alpha = alpha(config);
        boolean partial = !controls.isEmpty();
        List<ColumnSpec> all = new ArrayList<>(columns);
        all.addAll(controls);
        if ("matrix".equals(output)) {
            return matrix(context, input, columns, all, method, partial);
        }
        return longFormat(context, input, columns, all, method, alpha, partial);
    }

    // ------------------------------------------------------------------- long

    private static LazyPlan longFormat(NodeContext context, LazyPlan input, List<ColumnSpec> columns,
                                       List<ColumnSpec> all, String method, double alpha,
                                       boolean partial) throws SQLException {
        int requested = columns.size();
        List<Pair> pairs = new ArrayList<>();
        if ("kendall".equals(method)) {
            // Kendall needs the values: complete cases when partial (for the matrix
            // inverse), otherwise every row so that nulls can be dropped per pair.
            double[][] values = readValues(context, input, all, partial);
            long commonN = values.length;
            double[][] matrix = kendallMatrix(values, all.size());
            for (int i = 0; i < requested; i++) {
                for (int j = i + 1; j < requested; j++) {
                    double correlation = partial
                            ? partialCorrelation(matrix, i, j, requested, all.size() - requested)
                            : kendallFromValues(values, i, j);
                    long n = partial ? commonN : finitePairs(values, i, j);
                    pairs.add(new Pair(i, j, correlation, n));
                }
            }
        } else if (partial) {
            List<PairResult> results = pairQuery(context, input, all, method, true);
            double[][] matrix = matrixFrom(results, all.size());
            long n = results.isEmpty() ? 0 : results.get(0).n();
            for (int i = 0; i < requested; i++) {
                for (int j = i + 1; j < requested; j++) {
                    pairs.add(new Pair(i, j,
                            partialCorrelation(matrix, i, j, requested, all.size() - requested), n));
                }
            }
        } else {
            List<PairResult> results = pairQuery(context, input, columns, method, false);
            for (PairResult result : results) {
                pairs.add(new Pair(result.first(), result.second(), result.correlation(), result.n()));
            }
        }
        List<String> rows = new ArrayList<>(pairs.size());
        String partialValue = partial ? joinNames(controlsOf(all, requested)) : null;
        for (Pair pair : pairs) {
            rows.add(longRow(columns.get(pair.first()), columns.get(pair.second()), method,
                    stats(method, pair.correlation(), pair.n(), partial ? all.size() - requested : 0,
                            alpha),
                    partialValue));
        }
        return literalPlan(context, rows, LONG);
    }

    private record Pair(int first, int second, double correlation, long n) {
    }

    private record Stats(double correlation, long n, Double pValue, Double ciLow, Double ciHigh) {
    }

    /**
     * p-value and confidence interval for one correlation: the t approximation for
     * Pearson/Spearman (df = n - 2 - controls), the normal z approximation for Kendall;
     * Fisher's z for the Pearson/Spearman interval and the asymptotic standard error for
     * Kendall. Degenerate samples leave the p-value and interval NULL.
     */
    private static Stats stats(String method, double rawCorrelation, long n, int controls, double alpha) {
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
            if (n >= 3) {
                double z = 3.0 * correlation * Math.sqrt(n * (n - 1.0)) / (2.0 * Math.sqrt(2.0 * n + 5.0));
                pValue = clamp(2.0 * (1.0 - Distributions.normalCdf(Math.abs(z))));
                double standardError = Math.sqrt(2.0 * (2.0 * n + 5.0) / (9.0 * n * (n - 1.0)));
                ciLow = clip(correlation - critical * standardError);
                ciHigh = clip(correlation + critical * standardError);
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
                                   List<ColumnSpec> all, String method,
                                   boolean partial) throws SQLException {
        double[][] matrix;
        long n;
        if ("kendall".equals(method)) {
            double[][] values = readValues(context, input, all, true);
            n = values.length;
            matrix = kendallMatrix(values, all.size());
        } else {
            List<PairResult> results = pairQuery(context, input, all, method, true);
            matrix = matrixFrom(results, all.size());
            n = results.isEmpty() ? countComplete(context, input, all) : results.get(0).n();
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
                                              boolean completeCase) throws SQLException {
        List<int[]> pairs = new ArrayList<>();
        StringJoiner union = new StringJoiner("\nUNION ALL\n");
        int ordinal = 0;
        for (int i = 0; i < columns.size(); i++) {
            for (int j = i + 1; j < columns.size(); j++) {
                pairs.add(new int[]{i, j});
                String where = completeCase
                        ? completeCaseWhere(columns)
                        : pairWhere(columns.get(i), columns.get(j));
                union.add(pairSql(ordinal++, columns.get(i), columns.get(j), input.asTable(), where, method));
            }
        }
        PairResult[] results = new PairResult[pairs.size()];
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(union.toString())) {
            while (result.next()) {
                int read = result.getInt("dap_ordinal");
                int[] pair = pairs.get(read);
                results[read] = new PairResult(pair[0], pair[1], value(result, "r"),
                        result.getLong("n"));
            }
        }
        List<PairResult> ordered = new ArrayList<>(pairs.size());
        for (PairResult result : results) {
            if (result != null) {
                ordered.add(result);
            }
        }
        return ordered;
    }

    private static String pairSql(int ordinal, ColumnSpec a, ColumnSpec b, String table,
                                  String where, String method) {
        String quotedA = NodeSql.quote(a.name());
        String quotedB = NodeSql.quote(b.name());
        if ("spearman".equals(method)) {
            return "SELECT " + ordinal + " AS \"dap_ordinal\","
                    + " CAST(CORR(\"dap_rank_a\", \"dap_rank_b\") AS DOUBLE) AS \"r\","
                    + " COUNT(*) AS \"n\""
                    + " FROM (SELECT " + quotedA + " AS " + quotedA + ", " + quotedB + " AS " + quotedB
                    + ", RANK() OVER (ORDER BY " + quotedA + ") + (COUNT(*) OVER (PARTITION BY "
                    + quotedA + ") - 1) / 2.0 AS \"dap_rank_a\""
                    + ", RANK() OVER (ORDER BY " + quotedB + ") + (COUNT(*) OVER (PARTITION BY "
                    + quotedB + ") - 1) / 2.0 AS \"dap_rank_b\""
                    + " FROM " + table + " AS dap_input"
                    + " WHERE " + where + ") AS dap_ranked";
        }
        return "SELECT " + ordinal + " AS \"dap_ordinal\","
                + " CAST(CORR(" + quotedA + ", " + quotedB + ") AS DOUBLE) AS \"r\","
                + " COUNT(*) AS \"n\""
                + " FROM " + table + " AS dap_input"
                + " WHERE " + where;
    }

    private static long countComplete(NodeContext context, LazyPlan input, List<ColumnSpec> columns)
            throws SQLException {
        String sql = "SELECT COUNT(*) AS \"n\" FROM " + input.asTable() + " AS dap_input"
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
        double tiesY = 0.0;
        int run = 1;
        for (int i = 1; i <= n; i++) {
            if (i < n && sortedY[i] == sortedY[i - 1]) {
                run++;
            } else {
                tiesY += (double) run * (run - 1) / 2.0;
                run = 1;
            }
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
                                         boolean completeCase) throws SQLException {
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : columns) {
            projection.add("CAST(" + NodeSql.quote(column.name()) + " AS DOUBLE)");
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input"
                + (completeCase ? " WHERE " + completeCaseWhere(columns) : "")
                + " LIMIT " + (ROW_CAP + 1);
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
        if (rows.size() > ROW_CAP) {
            throw new IllegalArgumentException("correlation: kendall tau is computed in memory and needs at "
                    + "most " + ROW_CAP + " rows, got more; use method=spearman or aggregate first");
        }
        return rows.toArray(new double[0][]);
    }

    // --------------------------------------------------------------- output

    private static String longRow(ColumnSpec a, ColumnSpec b, String method, Stats stats,
                                  String partialValue) {
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

    private static Double value(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        if (value == null) {
            return Double.NaN;
        }
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) ? number : Double.NaN;
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
