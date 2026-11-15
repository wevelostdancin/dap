package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Shared plumbing for the statistics nodes that fit a model in Java (outlier detection
 * methods, clustering and PCA): numeric column validation, the bounded in-memory read,
 * the distance helpers and the per-row result mapping joined back to the input.
 *
 * <p>The result of such a node depends only on the selected feature values, so the mapping
 * is keyed by the feature vector instead of a synthetic row id: rows with equal features get
 * equal results by construction, and the join needs no ordering assumption about the input
 * plan. The mapping is materialized as a DuckDB table named {@code stats_<workflow>_<node>}
 * and cleaned up together with the workflow.</p>
 */
final class StatsSupport {
    /** Prefix of the materialized per-row result tables; dropped with the workflow. */
    static final String TABLE_PREFIX = "stats_";
    private static final double EULER_GAMMA = 0.5772156649015329;

    private StatsSupport() {
    }

    // ------------------------------------------------------------- validation

    /** Resolve the required non-empty array of distinct numeric input columns. */
    static List<ColumnSpec> numericColumns(LazyPlan input, JsonNode config, String field, String node) {
        JsonNode array = config.get(field);
        if (array == null || array.isNull()) {
            throw new IllegalArgumentException(node + ": config field '" + field + "' is required");
        }
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException(node + ": '" + field
                    + "' must be a non-empty array of numeric column names");
        }
        List<ColumnSpec> columns = new ArrayList<>(array.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(node + ": '" + field
                        + "' must contain non-empty column names");
            }
            String name = item.textValue();
            ColumnSpec column = null;
            for (ColumnSpec candidate : input.columns()) {
                if (candidate.name().equals(name)) {
                    column = candidate;
                    break;
                }
            }
            if (column == null) {
                throw new IllegalArgumentException(node + ": unknown column '" + name + "'");
            }
            if (!column.numeric()) {
                throw new IllegalArgumentException(node + ": column '" + name + "' is "
                        + column.arrowType() + "; '" + field + "' requires numeric columns");
            }
            if (seen.add(name)) {
                columns.add(column);
            }
        }
        return columns;
    }

    /** Positive integer setting; {@code max} bounds it (inclusive). */
    static int positiveInt(JsonNode config, String field, int fallback, String node, int max) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || value.longValue() < 1 || value.longValue() > max) {
            throw new IllegalArgumentException(node + ": '" + field
                    + "' must be an integer between 1 and " + max);
        }
        return value.intValue();
    }

    /** Any integer setting (the random seed); absent means {@code fallback}. */
    static int integer(JsonNode config, String field, int fallback, String node) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(node + ": '" + field + "' must be an integer");
        }
        return value.intValue();
    }

    /** Positive finite number setting. */
    static double positiveNumber(JsonNode config, String field, double fallback, String node) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0.0) || !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(node + ": '" + field + "' must be a positive finite number");
        }
        return value.doubleValue();
    }

    /** Number in [0, {@code max}] setting. */
    static double boundedNumber(JsonNode config, String field, double fallback, double max, String node) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        double number = value.doubleValue();
        if (!value.isNumber() || !Double.isFinite(number) || number < 0.0 || number > max) {
            throw new IllegalArgumentException(node + ": '" + field + "' must be a number between 0 and "
                    + max);
        }
        return number;
    }

    // ------------------------------------------------------------------ data

    /**
     * Read the selected feature columns into memory, keeping rows without NULL / non-finite
     * values. Rows are sorted lexicographically by their feature vector so seeded methods do
     * not depend on the physical scan order.
     */
    static List<double[]> readFeatures(NodeContext context, LazyPlan input, List<ColumnSpec> columns,
                                       int maxRows, String node) throws SQLException {
        StringJoiner projection = new StringJoiner(", ");
        StringJoiner where = new StringJoiner(" AND ");
        for (ColumnSpec column : columns) {
            String cast = "CAST(" + NodeSql.quote(column.name()) + " AS DOUBLE)";
            projection.add(cast);
            where.add(NodeSql.quote(column.name()) + " IS NOT NULL AND isfinite(" + cast + ")");
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input WHERE "
                + where + " LIMIT " + (maxRows + 1);
        List<double[]> rows = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                double[] values = new double[columns.size()];
                for (int index = 0; index < values.length; index++) {
                    double value = result.getDouble(index + 1);
                    values[index] = value == 0.0 ? 0.0 : value;
                }
                rows.add(values);
            }
        }
        if (rows.size() > maxRows) {
            throw new IllegalArgumentException(node + ": the method is computed in memory and supports at "
                    + "most max_rows=" + maxRows + " rows, got more; raise max_rows or pre-filter the input");
        }
        rows.sort(StatsSupport::compareVectors);
        return rows;
    }

    private static int compareVectors(double[] left, double[] right) {
        for (int index = 0; index < left.length; index++) {
            int comparison = Double.compare(left[index], right[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    /** Copy a feature matrix (the mapping keys stay raw, the model works on a copy). */
    static double[][] copy(double[][] rows) {
        double[][] copy = new double[rows.length][];
        for (int index = 0; index < rows.length; index++) {
            copy[index] = rows[index].clone();
        }
        return copy;
    }

    /** Z-score every feature in place (sample standard deviation); a constant column becomes zero. */
    static void standardize(double[][] rows, int dimensions) {
        int n = rows.length;
        if (n < 2) {
            for (double[] row : rows) {
                Arrays.fill(row, 0.0);
            }
            return;
        }
        double[] mean = new double[dimensions];
        for (double[] row : rows) {
            for (int dimension = 0; dimension < dimensions; dimension++) {
                mean[dimension] += row[dimension];
            }
        }
        for (int dimension = 0; dimension < dimensions; dimension++) {
            mean[dimension] /= n;
        }
        double[] deviation = new double[dimensions];
        for (double[] row : rows) {
            for (int dimension = 0; dimension < dimensions; dimension++) {
                double delta = row[dimension] - mean[dimension];
                deviation[dimension] += delta * delta;
            }
        }
        for (int dimension = 0; dimension < dimensions; dimension++) {
            deviation[dimension] = Math.sqrt(deviation[dimension] / (n - 1));
        }
        for (double[] row : rows) {
            for (int dimension = 0; dimension < dimensions; dimension++) {
                double scale = deviation[dimension];
                row[dimension] = scale == 0.0 ? 0.0 : (row[dimension] - mean[dimension]) / scale;
            }
        }
    }

    // -------------------------------------------------------------- distances

    static double distance(double[] left, double[] right) {
        double sum = 0.0;
        for (int index = 0; index < left.length; index++) {
            double delta = left[index] - right[index];
            sum += delta * delta;
        }
        return Math.sqrt(sum);
    }

    /** The k nearest neighbours of every point and the distance to the k-th one. */
    record Neighbours(int[][] indices, double[] distance) {
    }

    /**
     * Exact k nearest neighbours (k clamped to n-1). Points are sorted by their first
     * coordinate, so the scan of each point stops once the coordinate gap alone exceeds the
     * current k-th distance; that keeps the common low-dimensional case close to linear.
     */
    static Neighbours kNearest(double[][] points, int k) {
        int n = points.length;
        int effective = Math.min(k, Math.max(0, n - 1));
        int[][] indices = new int[n][effective];
        double[] distances = new double[n];
        if (effective == 0) {
            return new Neighbours(indices, distances);
        }
        int[] order = orderByFirstCoordinate(points);
        int[] position = new int[n];
        for (int index = 0; index < n; index++) {
            position[order[index]] = index;
        }
        for (int point = 0; point < n; point++) {
            PriorityQueue<double[]> heap = new PriorityQueue<>(
                    (left, right) -> Double.compare(right[0], left[0]));
            double x = points[point][0];
            for (int step = position[point] + 1; step < n; step++) {
                int candidate = order[step];
                double gap = points[candidate][0] - x;
                if (heap.size() == effective && gap > heap.peek()[0]) {
                    break;
                }
                offer(heap, effective, distance(points[point], points[candidate]), candidate);
            }
            for (int step = position[point] - 1; step >= 0; step--) {
                int candidate = order[step];
                double gap = x - points[candidate][0];
                if (heap.size() == effective && gap > heap.peek()[0]) {
                    break;
                }
                offer(heap, effective, distance(points[point], points[candidate]), candidate);
            }
            int filled = heap.size();
            double[] neighbourIndices = new double[filled];
            double[] neighbourDistances = new double[filled];
            for (int index = filled - 1; index >= 0; index--) {
                double[] entry = heap.poll();
                neighbourIndices[index] = entry[1];
                neighbourDistances[index] = entry[0];
            }
            for (int index = 0; index < filled; index++) {
                indices[point][index] = (int) neighbourIndices[index];
            }
            distances[point] = filled == 0 ? 0.0 : neighbourDistances[filled - 1];
        }
        return new Neighbours(indices, distances);
    }

    private static void offer(PriorityQueue<double[]> heap, int capacity, double distance, int index) {
        if (heap.size() < capacity) {
            heap.add(new double[]{distance, index});
            return;
        }
        if (distance < heap.peek()[0]) {
            heap.poll();
            heap.add(new double[]{distance, index});
        }
    }

    // ---------------------------------------------------------------- dbscan

    /** DBSCAN labels (-1 noise, 0..m-1 clusters) and the core-point flags. */
    record Dbscan(int[] labels, boolean[] core) {
    }

    /** Classic DBSCAN on Euclidean distances; neighbour lists use the first-coordinate slab. */
    static Dbscan dbscan(double[][] points, double eps, int minPoints) {
        int n = points.length;
        int[] labels = new int[n];
        Arrays.fill(labels, -1);
        boolean[] core = new boolean[n];
        boolean[] visited = new boolean[n];
        int[] order = orderByFirstCoordinate(points);
        int[] position = new int[n];
        for (int index = 0; index < n; index++) {
            position[order[index]] = index;
        }
        double squaredEps = eps * eps;
        int cluster = 0;
        for (int start = 0; start < n; start++) {
            if (visited[start]) {
                continue;
            }
            visited[start] = true;
            List<Integer> neighbours = epsilonNeighbours(points, order, position, start, eps, squaredEps);
            if (neighbours.size() < minPoints) {
                continue;
            }
            core[start] = true;
            labels[start] = cluster;
            ArrayDeque<Integer> queue = new ArrayDeque<>(neighbours);
            while (!queue.isEmpty()) {
                int point = queue.poll();
                if (!visited[point]) {
                    visited[point] = true;
                    List<Integer> pointNeighbours =
                            epsilonNeighbours(points, order, position, point, eps, squaredEps);
                    if (pointNeighbours.size() >= minPoints) {
                        core[point] = true;
                        for (int candidate : pointNeighbours) {
                            if (!visited[candidate]) {
                                queue.add(candidate);
                            }
                        }
                    }
                }
                if (labels[point] == -1) {
                    labels[point] = cluster;
                }
            }
            cluster++;
        }
        return new Dbscan(labels, core);
    }

    private static List<Integer> epsilonNeighbours(double[][] points, int[] order, int[] position,
                                                   int point, double eps, double squaredEps) {
        List<Integer> neighbours = new ArrayList<>();
        neighbours.add(point);
        double x = points[point][0];
        int n = points.length;
        for (int step = position[point] - 1; step >= 0; step--) {
            int candidate = order[step];
            if (x - points[candidate][0] > eps) {
                break;
            }
            if (squaredDistance(points[point], points[candidate]) <= squaredEps) {
                neighbours.add(candidate);
            }
        }
        for (int step = position[point] + 1; step < n; step++) {
            int candidate = order[step];
            if (points[candidate][0] - x > eps) {
                break;
            }
            if (squaredDistance(points[point], points[candidate]) <= squaredEps) {
                neighbours.add(candidate);
            }
        }
        return neighbours;
    }

    private static double squaredDistance(double[] left, double[] right) {
        double sum = 0.0;
        for (int index = 0; index < left.length; index++) {
            double delta = left[index] - right[index];
            sum += delta * delta;
        }
        return sum;
    }

    private static int[] orderByFirstCoordinate(double[][] points) {
        Integer[] boxed = new Integer[points.length];
        for (int index = 0; index < points.length; index++) {
            boxed[index] = index;
        }
        Arrays.sort(boxed, Comparator.comparingDouble(index -> points[index][0]));
        int[] order = new int[points.length];
        for (int index = 0; index < points.length; index++) {
            order[index] = boxed[index];
        }
        return order;
    }

    // --------------------------------------------------------------- quantile

    /** Linear-interpolation quantile of the values (copy sorted, does not mutate). */
    static double quantile(double[] values, double q) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double clamped = Math.max(0.0, Math.min(1.0, q));
        double position = clamped * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(sorted.length - 1, lower + 1);
        double weight = position - lower;
        return sorted[lower] + weight * (sorted[upper] - sorted[lower]);
    }

    // ------------------------------------------------------------ output plan

    /**
     * Materialize a distinct feature-vector result mapping as a DuckDB table. The stable
     * name lets a re-run replace its previous copy instead of leaking one table per run.
     */
    static String materialize(NodeContext context, List<ColumnSpec> columns, List<Object[]> rows)
            throws SQLException {
        try (ArrowTable table = ArrowTable.of(columns, rows)) {
            Connection connection = context.jobConnection();
            if (connection instanceof DuckDBConnection) {
                return context.db().loadInto(connection, table, tableName(context));
            }
            return context.db().load(table, tableName(context));
        }
    }

    private static String tableName(NodeContext context) {
        return TABLE_PREFIX + sanitize(context.workflowId()) + "_" + sanitize(context.nodeId());
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Feature mapping columns: dap_f0..dap_fN plus the node result columns. */
    static List<ColumnSpec> mappingColumns(int features, List<ColumnSpec> results) {
        List<ColumnSpec> columns = new ArrayList<>(features + results.size());
        for (int index = 0; index < features; index++) {
            columns.add(ColumnSpec.of("dap_f" + index, "Float64"));
        }
        columns.addAll(results);
        return columns;
    }

    static Object[] mappingRow(double[] features, Object... results) {
        Object[] row = new Object[features.length + results.length];
        for (int index = 0; index < features.length; index++) {
            row[index] = features[index];
        }
        System.arraycopy(results, 0, row, features.length, results.length);
        return row;
    }

    /**
     * Append the result columns from a materialized feature mapping to the input table.
     * {@code expressions} must be one aliased SQL expression per added column and may
     * reference {@code dap_res.<column>}; rows without a mapping entry (NULL features) get
     * the expression's NULL/COALESCE fallback.
     */
    static LazyPlan appendMapping(NodeContext context, LazyPlan input, List<ColumnSpec> features,
                                  String table, List<ColumnSpec> added, List<String> expressions) {
        if (expressions.size() != added.size()) {
            throw new IllegalStateException("mapping expression/column count mismatch");
        }
        List<ColumnSpec> output = new ArrayList<>(input.columns());
        output.addAll(added);
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : input.columns()) {
            projection.add("dap_input." + NodeSql.quote(column.name()));
        }
        for (String expression : expressions) {
            projection.add(expression);
        }
        StringJoiner join = new StringJoiner(" AND ");
        for (int index = 0; index < features.size(); index++) {
            join.add("dap_res." + NodeSql.quote("dap_f" + index) + " IS NOT DISTINCT FROM CAST(dap_input."
                    + NodeSql.quote(features.get(index).name()) + " AS DOUBLE)");
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input"
                + " LEFT JOIN " + NodeSql.quote(table) + " AS dap_res ON " + join;
        return new LazyPlan(context.db(), sql, output);
    }

    /** A plan over literal rows (one SELECT per row) with the given output columns. */
    static LazyPlan literalPlan(NodeContext context, List<String> rows, List<ColumnSpec> columns) {
        if (rows.isEmpty()) {
            StringJoiner select = new StringJoiner(", ");
            for (ColumnSpec column : columns) {
                select.add("CAST(NULL AS " + column.duckDbType() + ") AS " + NodeSql.quote(column.name()));
            }
            return new LazyPlan(context.db(), "SELECT " + select + " WHERE FALSE", columns);
        }
        return new LazyPlan(context.db(), String.join(" UNION ALL ", rows), columns);
    }

    static String doubleLiteral(Double value) {
        return value == null || !Double.isFinite(value)
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
    }

    static String longLiteral(Long value) {
        return value == null
                ? "CAST(NULL AS BIGINT)"
                : "CAST(" + value + " AS BIGINT)";
    }

    /** Mean path length of an isolation-forest leaf with {@code size} samples. */
    static double averagePathLength(int size) {
        if (size <= 1) {
            return 0.0;
        }
        if (size == 2) {
            return 1.0;
        }
        return 2.0 * (Math.log(size - 1.0) + EULER_GAMMA) - 2.0 * (size - 1.0) / size;
    }
}
