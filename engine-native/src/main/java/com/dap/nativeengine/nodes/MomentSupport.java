package com.dap.nativeengine.nodes;

import com.dap.nativeengine.exec.NodeContext;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.StringJoiner;

/**
 * SQL sufficient statistics for the moment-based statistics nodes.
 *
 * <p>One scan of DuckDB produces the count, the per-column sums and squares and every
 * cross product of the selected numeric columns. The Java side only ever holds a
 * p-by-p matrix, so memory is O(columns²) regardless of the row count. Pair-conditional
 * aggregates keep pairwise deletion (NULL in one column drops only that pair) exact,
 * and the cross products are cast to DOUBLE so DuckDB never infers a DECIMAL type.</p>
 */
final class MomentSupport {
    private MomentSupport() {
    }

    /** {@code CAST("x" AS DOUBLE)} expression for an input column. */
    static String doubleColumn(String name) {
        return "CAST(" + NodeSql.quote(name) + " AS DOUBLE)";
    }

    /**
     * Aggregate projection over the expressions {@code c0..cN}. Besides the global
     * moments it emits, for every pair (i, j), the count and the five sums restricted
     * to rows where both columns are present.
     */
    static String projection(List<String> columns, boolean pairwise) {
        StringJoiner select = new StringJoiner(", ");
        select.add("COUNT(*) AS " + NodeSql.quote("dap_n"));
        for (int i = 0; i < columns.size(); i++) {
            select.add("COUNT(" + columns.get(i) + ") AS " + NodeSql.quote("dap_count" + i));
            select.add("CAST(SUM(" + columns.get(i) + ") AS DOUBLE) AS " + NodeSql.quote("dap_s" + i));
            select.add("CAST(SUM(" + product(columns.get(i), columns.get(i)) + ") AS DOUBLE) AS "
                    + NodeSql.quote("dap_q" + i));
        }
        for (int i = 0; i < columns.size(); i++) {
            for (int j = i + 1; j < columns.size(); j++) {
                select.add("CAST(SUM(" + product(columns.get(i), columns.get(j)) + ") AS DOUBLE) AS "
                        + NodeSql.quote("dap_c" + i + "_" + j));
                if (pairwise) {
                    String both = columns.get(i) + " IS NOT NULL AND " + columns.get(j) + " IS NOT NULL";
                    select.add("COUNT(*) FILTER (WHERE " + both + ") AS " + NodeSql.quote("dap_n" + i + "_" + j));
                    select.add("CAST(SUM(" + columns.get(i) + ") FILTER (WHERE " + columns.get(j)
                            + " IS NOT NULL) AS DOUBLE) AS " + NodeSql.quote("dap_x" + i + "_" + j));
                    select.add("CAST(SUM(" + product(columns.get(i), columns.get(i))
                            + ") FILTER (WHERE " + columns.get(j) + " IS NOT NULL) AS DOUBLE) AS "
                            + NodeSql.quote("dap_xx" + i + "_" + j));
                    select.add("CAST(SUM(" + columns.get(j) + ") FILTER (WHERE " + columns.get(i)
                            + " IS NOT NULL) AS DOUBLE) AS " + NodeSql.quote("dap_y" + i + "_" + j));
                    select.add("CAST(SUM(" + product(columns.get(j), columns.get(j))
                            + ") FILTER (WHERE " + columns.get(i) + " IS NOT NULL) AS DOUBLE) AS "
                            + NodeSql.quote("dap_yy" + i + "_" + j));
                }
            }
        }
        return select.toString();
    }

    private static String product(String left, String right) {
        return "(" + left + " * " + right + ")";
    }

    /** The sufficient statistics of one (possibly grouped) scan. */
    static final class Moments {
        private long n;
        private final long[] counts;
        private final double[] sum;
        private final double[] square;
        private final double[][] cross;
        private final long[][] pairN;
        private final double[][] pairSumX;
        private final double[][] pairSumY;
        private final double[][] pairSquareX;
        private final double[][] pairSquareY;
        private final double[][] pairCross;

        private Moments(int size, boolean pairwise) {
            counts = new long[size];
            sum = new double[size];
            square = new double[size];
            cross = new double[size][size];
            pairN = pairwise ? new long[size][size] : null;
            pairSumX = pairwise ? new double[size][size] : null;
            pairSumY = pairwise ? new double[size][size] : null;
            pairSquareX = pairwise ? new double[size][size] : null;
            pairSquareY = pairwise ? new double[size][size] : null;
            pairCross = pairwise ? new double[size][size] : null;
        }

        long count() {
            return n;
        }

        long count(int column) {
            return counts[column];
        }

        /** True when no column has a NULL, so global moments equal pair moments. */
        boolean complete() {
            for (long count : counts) {
                if (count != n) {
                    return false;
                }
            }
            return true;
        }

        double mean(int column) {
            return n == 0 ? Double.NaN : sum[column] / n;
        }

        double sum(int column) {
            return sum[column];
        }

        double cross(int first, int second) {
            return cross[first][second];
        }

        /** Centered cross product Σ(x_i − x̄)(x_j − x̄) over all rows. */
        double covariance(int first, int second) {
            return n == 0 ? Double.NaN
                    : cross[first][second] - sum[first] * sum[second] / n;
        }

        double variance(int column) {
            return covariance(column, column);
        }

        long count(int first, int second) {
            return pairN == null ? n : pairN[first][second];
        }

        /** Pearson r from the pair-specific sufficient statistics. */
        double correlation(int first, int second) {
            long count = count(first, second);
            if (pairN == null) {
                return n < 2 ? Double.NaN : centeredCorrelation(first, second);
            }
            if (count < 2) {
                return Double.NaN;
            }
            double xy = pairCross[first][second] - pairSumX[first][second] * pairSumY[first][second] / count;
            double xx = pairSquareX[first][second]
                    - pairSumX[first][second] * pairSumX[first][second] / count;
            double yy = pairSquareY[first][second]
                    - pairSumY[first][second] * pairSumY[first][second] / count;
            return divide(xy, xx, yy);
        }

        /** Pearson r from the global (complete-case) sufficient statistics. */
        double centeredCorrelation(int first, int second) {
            return divide(covariance(first, second), variance(first), variance(second));
        }

        private static double divide(double xy, double xx, double yy) {
            if (!(xx > 0.0) || !(yy > 0.0)) {
                return Double.NaN;
            }
            double value = xy / Math.sqrt(xx * yy);
            return Double.isFinite(value) ? Math.max(-1.0, Math.min(1.0, value)) : Double.NaN;
        }
    }

    /**
     * Run one aggregate query and return the moments, one entry per group (a single
     * empty group when there are no grouping columns).
     */
    static LinkedHashMap<List<Object>, Moments> read(NodeContext context, String withPrefix,
                                                     String fromWhere, List<String> columns,
                                                     boolean pairwise, List<String> groups)
            throws SQLException {
        String sql = withPrefix + "SELECT " + (groups.isEmpty() ? "" : String.join(", ", groups) + ", ")
                + projection(columns, pairwise) + fromWhere
                + (groups.isEmpty() ? "" : " GROUP BY " + String.join(", ", groups));
        LinkedHashMap<List<Object>, Moments> result = new LinkedHashMap<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                List<Object> key = new ArrayList<>(groups.size());
                for (String group : groups) {
                    key.add(rows.getObject(group.replace("\"", "")));
                }
                result.put(key, fromResultSet(rows, columns.size(), pairwise));
            }
        }
        return result;
    }

    private static Moments fromResultSet(ResultSet rows, int size, boolean pairwise) throws SQLException {
        Moments moments = new Moments(size, pairwise);
        moments.n = rows.getLong("dap_n");
        for (int i = 0; i < size; i++) {
            moments.counts[i] = rows.getLong("dap_count" + i);
            moments.sum[i] = value(rows, "dap_s" + i);
            moments.square[i] = value(rows, "dap_q" + i);
        }
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                moments.cross[i][j] = i == j
                        ? moments.square[i]
                        : value(rows, "dap_c" + Math.min(i, j) + "_" + Math.max(i, j));
            }
        }
        if (pairwise) {
            for (int i = 0; i < size; i++) {
                for (int j = i + 1; j < size; j++) {
                    String suffix = i + "_" + j;
                    moments.pairN[i][j] = moments.pairN[j][i] = rows.getLong("dap_n" + suffix);
                    moments.pairSumX[i][j] = moments.pairSumX[j][i] = value(rows, "dap_x" + suffix);
                    moments.pairSumY[i][j] = moments.pairSumY[j][i] = value(rows, "dap_y" + suffix);
                    moments.pairSquareX[i][j] = moments.pairSquareX[j][i] = value(rows, "dap_xx" + suffix);
                    moments.pairSquareY[i][j] = moments.pairSquareY[j][i] = value(rows, "dap_yy" + suffix);
                    moments.pairCross[i][j] = moments.pairCross[j][i] = value(rows, "dap_c" + suffix);
                }
            }
        }
        return moments;
    }

    private static double value(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        return value == null ? 0.0 : ((Number) value).doubleValue();
    }
}
