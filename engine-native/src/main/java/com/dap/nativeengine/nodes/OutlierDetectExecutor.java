package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.outlier_detect: flag rows with a value outside per-column IQR fences or z-score bounds
 * (iqr / zscore), or with a distance-based model computed in memory (dbscan / lof /
 * isolation_forest).
 *
 * <p>The distance-based methods standardize the selected numeric columns (z-score) first,
 * drop rows with a NULL or non-finite feature from the model, and append the Boolean
 * {@code outlier} plus a Float64 {@code score}. The score is method specific: DBSCAN uses the
 * distance to the k-th nearest neighbour (0 for a core point), LOF the local outlier factor,
 * isolation forest the normalized anomaly score in [0, 1]. iqr and zscore keep their original
 * contract: input columns plus {@code outlier} only.</p>
 */
public final class OutlierDetectExecutor implements NodeExecutor {
    private static final String NODE = "outlier_detect";
    private static final double IQR_THRESHOLD = 1.5;
    private static final double ZSCORE_THRESHOLD = 3.0;
    private static final String OUTPUT = "outlier";
    private static final String SCORE = "score";
    private static final Set<String> METHODS =
            Set.of("iqr", "zscore", "dbscan", "lof", "isolation_forest");
    private static final double DEFAULT_EPS = 0.5;
    private static final int DEFAULT_MIN_POINTS = 5;
    private static final int DEFAULT_K = 5;
    private static final double DEFAULT_CONTAMINATION = 0.05;
    private static final int DEFAULT_SEED = 42;
    private static final int DEFAULT_MAX_ROWS = 50_000;
    /** Isolation forest: 100 trees and at most 256 samples per tree (the standard defaults). */
    private static final int FOREST_TREES = 100;
    private static final int FOREST_SAMPLE = 256;

    @Override
    public String nodeId() {
        return "dap.outlier_detect";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String method = NodeSql.requiredText(config, "method").toLowerCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("outlier_detect: unknown method '" + method
                    + "' (expected iqr, zscore, dbscan, lof or isolation_forest)");
        }
        if (!"iqr".equals(method) && !"zscore".equals(method)) {
            return model(context, input, config, method);
        }
        double threshold = threshold(config, method);
        List<ColumnSpec> columns = columns(config, input, method);
        TransformSupport.requireNewName(input.columns(), OUTPUT, "outlier_detect");

        String table = input.asTable();
        StringJoiner stats = new StringJoiner(", ");
        StringJoiner flags = new StringJoiner(" OR ");
        for (int index = 0; index < columns.size(); index++) {
            ColumnSpec column = columns.get(index);
            String value = "dap_input." + NodeSql.quote(column.name());
            // Statistics read the input as their own table expression: referencing dap_input
            // would make the join LATERAL, and DuckDB forbids aggregates in a lateral.
            String source = "dap_outlier_source." + NodeSql.quote(column.name());
            if ("iqr".equals(method)) {
                String q1 = "quantile_cont(" + source + ", 0.25)";
                String q3 = "quantile_cont(" + source + ", 0.75)";
                String spread = "(" + q3 + " - " + q1 + ")";
                stats.add(q1 + " - " + threshold + " * " + spread + " AS dap_lo_" + index);
                stats.add(q3 + " + " + threshold + " * " + spread + " AS dap_hi_" + index);
                flags.add("(" + value + " IS NOT NULL AND (" + value + " < dap_stats.dap_lo_" + index
                        + " OR " + value + " > dap_stats.dap_hi_" + index + "))");
            } else {
                stats.add("avg(" + source + ") AS dap_mean_" + index);
                stats.add("COALESCE(stddev(" + source + "), 0) AS dap_sd_" + index);
                flags.add("(" + value + " IS NOT NULL AND abs(" + value + " - dap_stats.dap_mean_" + index
                        + ") > " + threshold + " * dap_stats.dap_sd_" + index + ")");
            }
        }

        String sql = "SELECT dap_input.*, COALESCE(" + flags + ", FALSE) AS " + NodeSql.quote(OUTPUT)
                + " FROM " + table + " AS dap_input CROSS JOIN (SELECT " + stats + " FROM " + table
                + " AS dap_outlier_source) AS dap_stats";
        List<ColumnSpec> output = new ArrayList<>(input.columns());
        output.add(ColumnSpec.of(OUTPUT, "Boolean"));
        return new LazyPlan(context.db(), sql, output);
    }

    // ----------------------------------------------------- in-memory methods

    private static LazyPlan model(NodeContext context, LazyPlan input, JsonNode config, String method)
            throws Exception {
        List<ColumnSpec> features = columns(config, input, method);
        double eps = StatsSupport.positiveNumber(config, "eps", DEFAULT_EPS, NODE);
        int minPoints = StatsSupport.positiveInt(config, "min_points", DEFAULT_MIN_POINTS, NODE, 1_000_000);
        int k = StatsSupport.positiveInt(config, "k", DEFAULT_K, NODE, 1_000_000);
        double contamination = StatsSupport.boundedNumber(config, "contamination", DEFAULT_CONTAMINATION,
                0.5, NODE);
        int seed = StatsSupport.integer(config, "seed", DEFAULT_SEED, NODE);
        int maxRows = StatsSupport.positiveInt(config, "max_rows", DEFAULT_MAX_ROWS, NODE, 10_000_000);
        TransformSupport.requireNewName(input.columns(), OUTPUT, NODE);
        TransformSupport.requireNewName(input.columns(), SCORE, NODE);

        List<double[]> points = StatsSupport.readFeatures(context, input, features, maxRows, NODE);
        List<ColumnSpec> added = List.of(ColumnSpec.of(OUTPUT, "Boolean"),
                ColumnSpec.of(SCORE, "Float64"));
        if (points.isEmpty()) {
            return constantPlan(context, input, added);
        }
        double[][] raw = points.toArray(new double[0][]);
        double[][] model = StatsSupport.copy(raw);
        StatsSupport.standardize(model, features.size());
        double[] scores = new double[model.length];
        boolean[] outliers = new boolean[model.length];
        switch (method) {
            case "dbscan" -> dbscan(model, eps, minPoints, k, scores, outliers);
            case "lof" -> lof(model, k, contamination, scores, outliers);
            default -> isolationForest(model, contamination, seed, scores, outliers);
        }

        Map<VectorKey, Boolean> seen = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < raw.length; index++) {
            if (seen.putIfAbsent(new VectorKey(raw[index]), Boolean.TRUE) == null) {
                rows.add(StatsSupport.mappingRow(raw[index], outliers[index], scores[index]));
            }
        }
        String table = StatsSupport.materialize(context,
                StatsSupport.mappingColumns(features.size(), added), rows);
        return StatsSupport.appendMapping(context, input, features, table, added, List.of(
                "COALESCE(dap_res." + NodeSql.quote(OUTPUT) + ", FALSE) AS " + NodeSql.quote(OUTPUT),
                "dap_res." + NodeSql.quote(SCORE) + " AS " + NodeSql.quote(SCORE)));
    }

    /** Output shape for a table whose selected columns are all NULL / non-finite. */
    private static LazyPlan constantPlan(NodeContext context, LazyPlan input, List<ColumnSpec> added) {
        List<ColumnSpec> output = new ArrayList<>(input.columns());
        output.addAll(added);
        String sql = "SELECT dap_input.*, FALSE AS " + NodeSql.quote(OUTPUT)
                + ", CAST(NULL AS DOUBLE) AS " + NodeSql.quote(SCORE)
                + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, output);
    }

    private static void dbscan(double[][] points, double eps, int minPoints, int k,
                               double[] scores, boolean[] outliers) {
        StatsSupport.Dbscan result = StatsSupport.dbscan(points, eps, minPoints);
        StatsSupport.Neighbours neighbours = StatsSupport.kNearest(points, k);
        for (int index = 0; index < points.length; index++) {
            outliers[index] = result.labels()[index] == -1;
            scores[index] = result.core()[index] ? 0.0 : neighbours.distance()[index];
        }
    }

    /** Local outlier factor: LOF > the (1 - contamination) quantile marks a row. */
    private static void lof(double[][] points, int k, double contamination,
                            double[] scores, boolean[] outliers) {
        int n = points.length;
        StatsSupport.Neighbours neighbours = StatsSupport.kNearest(points, k);
        int effective = n == 0 ? 0 : neighbours.indices()[0].length;
        if (effective == 0) {
            Arrays.fill(scores, 1.0);
            return;
        }
        double[] reachability = new double[n];
        for (int point = 0; point < n; point++) {
            double sum = 0.0;
            for (int neighbour : neighbours.indices()[point]) {
                sum += Math.max(neighbours.distance()[neighbour],
                        StatsSupport.distance(points[point], points[neighbour]));
            }
            double mean = sum / effective;
            // A duplicate-only neighbourhood has reachability 0; a huge density keeps the
            // ratio finite instead of producing infinity / NaN.
            reachability[point] = mean == 0.0 ? 1e308 : 1.0 / mean;
        }
        for (int point = 0; point < n; point++) {
            double sum = 0.0;
            for (int neighbour : neighbours.indices()[point]) {
                sum += reachability[neighbour];
            }
            scores[point] = (sum / effective) / reachability[point];
        }
        double threshold = StatsSupport.quantile(scores, 1.0 - contamination);
        for (int point = 0; point < n; point++) {
            outliers[point] = scores[point] > threshold;
        }
    }

    /** Isolation forest with the standard path-length normalization. */
    private static void isolationForest(double[][] points, double contamination, int seed,
                                        double[] scores, boolean[] outliers) {
        int n = points.length;
        if (n < 2) {
            Arrays.fill(scores, 0.0);
            return;
        }
        int sampleSize = Math.min(FOREST_SAMPLE, n);
        int maxDepth = (int) Math.ceil(Math.log(sampleSize) / Math.log(2.0));
        double[] total = new double[n];
        Random random = new Random(seed);
        for (int tree = 0; tree < FOREST_TREES; tree++) {
            int[] sample = sample(n, sampleSize, random);
            Node root = build(points, sample, 0, maxDepth, random);
            for (int index = 0; index < n; index++) {
                total[index] += pathLength(root, points[index]);
            }
        }
        double normalizer = StatsSupport.averagePathLength(sampleSize);
        for (int index = 0; index < n; index++) {
            scores[index] = normalizer <= 0.0
                    ? 0.5
                    : Math.pow(2.0, -total[index] / FOREST_TREES / normalizer);
        }
        double threshold = StatsSupport.quantile(scores, 1.0 - contamination);
        for (int index = 0; index < n; index++) {
            outliers[index] = scores[index] > threshold;
        }
    }

    private static int[] sample(int size, int wanted, Random random) {
        int[] pool = new int[size];
        for (int index = 0; index < size; index++) {
            pool[index] = index;
        }
        for (int index = 0; index < wanted; index++) {
            int swap = index + random.nextInt(size - index);
            int value = pool[index];
            pool[index] = pool[swap];
            pool[swap] = value;
        }
        return Arrays.copyOf(pool, wanted);
    }

    private static Node build(double[][] points, int[] sample, int depth, int maxDepth, Random random) {
        if (sample.length <= 1 || depth >= maxDepth) {
            return Node.leaf(sample.length, depth);
        }
        int dimensions = points[0].length;
        double[] min = new double[dimensions];
        double[] max = new double[dimensions];
        Arrays.fill(min, Double.POSITIVE_INFINITY);
        Arrays.fill(max, Double.NEGATIVE_INFINITY);
        for (int index : sample) {
            for (int dimension = 0; dimension < dimensions; dimension++) {
                min[dimension] = Math.min(min[dimension], points[index][dimension]);
                max[dimension] = Math.max(max[dimension], points[index][dimension]);
            }
        }
        List<Integer> varying = new ArrayList<>();
        for (int dimension = 0; dimension < dimensions; dimension++) {
            if (max[dimension] > min[dimension]) {
                varying.add(dimension);
            }
        }
        if (varying.isEmpty()) {
            return Node.leaf(sample.length, depth);
        }
        int feature = varying.get(random.nextInt(varying.size()));
        double split = min[feature] + random.nextDouble() * (max[feature] - min[feature]);
        int leftCount = 0;
        for (int index : sample) {
            if (points[index][feature] < split) {
                leftCount++;
            }
        }
        if (leftCount == 0 || leftCount == sample.length) {
            return Node.leaf(sample.length, depth);
        }
        int[] left = new int[leftCount];
        int[] right = new int[sample.length - leftCount];
        int leftIndex = 0;
        int rightIndex = 0;
        for (int index : sample) {
            if (points[index][feature] < split) {
                left[leftIndex++] = index;
            } else {
                right[rightIndex++] = index;
            }
        }
        return new Node(feature, split, sample.length,
                build(points, left, depth + 1, maxDepth, random),
                build(points, right, depth + 1, maxDepth, random));
    }

    private static double pathLength(Node root, double[] point) {
        Node node = root;
        double depth = 0.0;
        while (node.left != null) {
            node = point[node.feature] < node.split ? node.left : node.right;
            depth += 1.0;
        }
        return depth + StatsSupport.averagePathLength(node.size);
    }

    private static final class Node {
        private final int feature;
        private final double split;
        private final int size;
        private final Node left;
        private final Node right;

        private Node(int feature, double split, int size, Node left, Node right) {
            this.feature = feature;
            this.split = split;
            this.size = size;
            this.left = left;
            this.right = right;
        }

        private static Node leaf(int size, int depth) {
            return new Node(-1, 0.0, size, null, null);
        }
    }

    /** Feature-vector key: rows with equal features must share one mapping entry. */
    private static final class VectorKey {
        private final double[] values;

        private VectorKey(double[] values) {
            this.values = values;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof VectorKey key && Arrays.equals(values, key.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }

    // ----------------------------------------------------------- validation

    private static List<ColumnSpec> columns(JsonNode config, LazyPlan input, String method) {
        JsonNode node = config.get("columns");
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("outlier_detect: config field 'columns' is required");
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                    "outlier_detect: 'columns' must be a non-empty array of column names");
        }
        List<ColumnSpec> columns = new ArrayList<>(node.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(
                        "outlier_detect: 'columns' must contain non-empty column names");
            }
            ColumnSpec column = TimeSeriesSupport.column(input.columns(), item.textValue(), "columns",
                    "outlier_detect");
            if (!column.numeric()) {
                throw new IllegalArgumentException("outlier_detect: column '" + column.name() + "' is "
                        + column.arrowType() + "; method '" + method + "' requires a numeric column");
            }
            if (seen.add(column.name())) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static double threshold(JsonNode config, String method) {
        JsonNode node = config.get("threshold");
        if (node == null || node.isNull()) {
            return "iqr".equals(method) ? IQR_THRESHOLD : ZSCORE_THRESHOLD;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException("outlier_detect: 'threshold' must be a positive number");
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("outlier_detect: 'threshold' must be a positive finite number, got "
                    + node.asText());
        }
        return value;
    }
}
