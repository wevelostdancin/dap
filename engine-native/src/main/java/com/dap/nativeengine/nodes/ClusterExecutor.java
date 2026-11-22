package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * dap.cluster: unsupervised clustering of numeric columns with k-means++ (default), DBSCAN or
 * agglomerative hierarchical clustering (Ward, complete or average linkage).
 *
 * <p>Appends {@code cluster} (Int64; -1 is DBSCAN noise) and the per-row {@code silhouette}
 * (Float64, NULL for noise and when fewer than two clusters exist). Features are optionally
 * z-score standardized first. k-means++ and DBSCAN are implemented here; hierarchical
 * clustering uses an O(n^2) distance matrix and is therefore capped at
 * {@value #HIERARCHICAL_ROW_CAP} rows. The result is keyed by the feature vector, so it does
 * not depend on the physical scan order of the input.</p>
 */
public final class ClusterExecutor implements NodeExecutor {
    private static final String NODE = "cluster";
    private static final String CLUSTER = "cluster";
    private static final String SILHOUETTE = "silhouette";
    private static final Set<String> METHODS = Set.of("kmeans", "dbscan", "hierarchical");
    private static final Set<String> LINKAGES = Set.of("ward", "complete", "average");
    private static final int DEFAULT_K = 3;
    private static final int DEFAULT_MAX_ITERATIONS = 100;
    private static final double DEFAULT_EPS = 0.5;
    private static final int DEFAULT_MIN_POINTS = 5;
    private static final int DEFAULT_MAX_ROWS = 50_000;
    /** Hierarchical clustering keeps an n x n distance matrix: O(n^2) memory and O(n^3) time. */
    static final int HIERARCHICAL_ROW_CAP = 1000;

    @Override
    public String nodeId() {
        return "dap.cluster";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> features = StatsSupport.numericColumns(input, config, "columns", NODE);
        String method = NodeSql.text(config, "method", "kmeans").trim().toLowerCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("cluster: unknown method '" + method
                    + "' (expected kmeans, dbscan or hierarchical)");
        }
        String linkage = NodeSql.text(config, "linkage", "ward").trim().toLowerCase(Locale.ROOT);
        if (!LINKAGES.contains(linkage)) {
            throw new IllegalArgumentException("cluster: unknown linkage '" + linkage
                    + "' (expected ward, complete or average)");
        }
        int k = StatsSupport.positiveInt(config, "k", DEFAULT_K, NODE, 1_000_000);
        boolean standardize = NodeSql.bool(config, "standardize", true);
        int seed = StatsSupport.integer(config, "seed", 42, NODE);
        int maxIterations = StatsSupport.positiveInt(config, "max_iterations",
                DEFAULT_MAX_ITERATIONS, NODE, 1_000_000);
        double eps = StatsSupport.positiveNumber(config, "eps", DEFAULT_EPS, NODE);
        int minPoints = StatsSupport.positiveInt(config, "min_points", DEFAULT_MIN_POINTS, NODE, 1_000_000);
        int maxRows = StatsSupport.positiveInt(config, "max_rows", DEFAULT_MAX_ROWS, NODE, 10_000_000);
        TransformSupport.requireNewName(input.columns(), CLUSTER, NODE);
        TransformSupport.requireNewName(input.columns(), SILHOUETTE, NODE);

        List<double[]> points = StatsSupport.readFeatures(context, input, features, maxRows, NODE);
        List<ColumnSpec> added = List.of(ColumnSpec.of(CLUSTER, "Int64"),
                ColumnSpec.of(SILHOUETTE, "Float64"));
        if (points.isEmpty()) {
            return constantPlan(context, input, added);
        }
        double[][] raw = points.toArray(new double[0][]);
        double[][] model = StatsSupport.copy(raw);
        if (standardize) {
            StatsSupport.standardize(model, features.size());
        }
        int n = model.length;
        if (!"dbscan".equals(method) && k > n) {
            throw new IllegalArgumentException("cluster: k=" + k + " exceeds the " + n
                    + " rows; reduce k or add rows");
        }
        if ("hierarchical".equals(method) && n > HIERARCHICAL_ROW_CAP) {
            throw new IllegalArgumentException("cluster: hierarchical clustering keeps an n x n "
                    + "distance matrix and supports at most " + HIERARCHICAL_ROW_CAP + " rows, got " + n
                    + "; use kmeans or dbscan");
        }
        int[] labels = switch (method) {
            case "dbscan" -> StatsSupport.dbscan(model, eps, minPoints).labels();
            case "hierarchical" -> hierarchical(model, k, linkage);
            default -> kmeans(model, k, seed, maxIterations);
        };
        Double[] silhouettes = silhouette(model, labels);

        Map<VectorKey, Boolean> seen = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < n; index++) {
            if (seen.putIfAbsent(new VectorKey(raw[index]), Boolean.TRUE) == null) {
                rows.add(StatsSupport.mappingRow(raw[index], (long) labels[index], silhouettes[index]));
            }
        }
        String table = StatsSupport.materialize(context,
                StatsSupport.mappingColumns(features.size(), added), rows);
        return StatsSupport.appendMapping(context, input, features, table, added, List.of(
                "dap_res." + NodeSql.quote(CLUSTER) + " AS " + NodeSql.quote(CLUSTER),
                "dap_res." + NodeSql.quote(SILHOUETTE) + " AS " + NodeSql.quote(SILHOUETTE)));
    }

    private static LazyPlan constantPlan(NodeContext context, LazyPlan input, List<ColumnSpec> added) {
        List<ColumnSpec> output = new ArrayList<>(input.columns());
        output.addAll(added);
        String sql = "SELECT dap_input.*, CAST(NULL AS BIGINT) AS " + NodeSql.quote(CLUSTER)
                + ", CAST(NULL AS DOUBLE) AS " + NodeSql.quote(SILHOUETTE)
                + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, output);
    }

    // ---------------------------------------------------------------- k-means

    /** k-means++ seeding followed by Lloyd iterations until the assignment is stable. */
    private static int[] kmeans(double[][] points, int k, int seed, int maxIterations) {
        int n = points.length;
        int dimensions = points[0].length;
        Random random = new Random(seed);
        double[][] centers = new double[k][];
        boolean[] used = new boolean[n];
        int first = random.nextInt(n);
        centers[0] = points[first].clone();
        used[first] = true;
        double[] nearest = new double[n];
        Arrays.fill(nearest, Double.MAX_VALUE);
        for (int center = 1; center < k; center++) {
            double total = 0.0;
            for (int index = 0; index < n; index++) {
                nearest[index] = Math.min(nearest[index], squaredDistance(points[index], centers[center - 1]));
                total += nearest[index];
            }
            int chosen = -1;
            if (total > 0.0) {
                double target = random.nextDouble() * total;
                double cumulative = 0.0;
                for (int index = 0; index < n; index++) {
                    cumulative += nearest[index];
                    if (cumulative >= target && nearest[index] > 0.0) {
                        chosen = index;
                        break;
                    }
                }
                if (chosen == -1) {
                    for (int index = n - 1; index >= 0; index--) {
                        if (nearest[index] > 0.0) {
                            chosen = index;
                            break;
                        }
                    }
                }
            }
            if (chosen == -1) {
                // All points coincide with existing centers: fall back to the first free row.
                for (int index = 0; index < n; index++) {
                    if (!used[index]) {
                        chosen = index;
                        break;
                    }
                }
            }
            centers[center] = points[chosen == -1 ? 0 : chosen].clone();
            if (chosen >= 0) {
                used[chosen] = true;
            }
        }

        int[] labels = new int[n];
        Arrays.fill(labels, -1);
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            boolean changed = false;
            for (int index = 0; index < n; index++) {
                int assignment = nearestCenter(points[index], centers);
                if (labels[index] != assignment) {
                    labels[index] = assignment;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
            double[][] sums = new double[k][dimensions];
            int[] counts = new int[k];
            for (int index = 0; index < n; index++) {
                int assignment = labels[index];
                counts[assignment]++;
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    sums[assignment][dimension] += points[index][dimension];
                }
            }
            for (int center = 0; center < k; center++) {
                if (counts[center] == 0) {
                    continue;
                }
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    centers[center][dimension] = sums[center][dimension] / counts[center];
                }
            }
        }
        for (int index = 0; index < n; index++) {
            labels[index] = nearestCenter(points[index], centers);
        }
        return labels;
    }

    private static int nearestCenter(double[] point, double[][] centers) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int center = 0; center < centers.length; center++) {
            double distance = squaredDistance(point, centers[center]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = center;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ hierarchical

    /**
     * Agglomerative clustering down to k clusters. Ward uses squared Euclidean distances and
     * the Lance-Williams update; complete and average linkage use Euclidean distances.
     */
    private static int[] hierarchical(double[][] points, int k, String linkage) {
        int n = points.length;
        boolean ward = "ward".equals(linkage);
        double[][] distances = new double[n][n];
        for (int left = 0; left < n; left++) {
            for (int right = left + 1; right < n; right++) {
                double squared = squaredDistance(points[left], points[right]);
                distances[left][right] = ward ? squared : Math.sqrt(squared);
                distances[right][left] = distances[left][right];
            }
        }
        boolean[] active = new boolean[n];
        int[] sizes = new int[n];
        int[] owner = new int[n];
        Arrays.fill(active, true);
        Arrays.fill(sizes, 1);
        for (int index = 0; index < n; index++) {
            owner[index] = index;
        }
        int remaining = n;
        while (remaining > k) {
            int bestLeft = -1;
            int bestRight = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int left = 0; left < n; left++) {
                if (!active[left]) {
                    continue;
                }
                for (int right = left + 1; right < n; right++) {
                    if (active[right] && distances[left][right] < best) {
                        best = distances[left][right];
                        bestLeft = left;
                        bestRight = right;
                    }
                }
            }
            if (bestLeft < 0) {
                break;
            }
            merge(distances, active, sizes, owner, bestLeft, bestRight, linkage);
            remaining--;
        }
        int[] representatives = new int[n];
        int count = 0;
        for (int index = 0; index < n; index++) {
            if (active[index]) {
                representatives[count++] = index;
            }
        }
        int[] labelOf = new int[n];
        Arrays.fill(labelOf, -1);
        for (int label = 0; label < count; label++) {
            labelOf[representatives[label]] = label;
        }
        int[] labels = new int[n];
        for (int index = 0; index < n; index++) {
            labels[index] = labelOf[owner[index]];
        }
        return labels;
    }

    private static void merge(double[][] distances, boolean[] active, int[] sizes, int[] owner,
                              int left, int right, String linkage) {
        int sizeLeft = sizes[left];
        int sizeRight = sizes[right];
        int combined = sizeLeft + sizeRight;
        for (int other = 0; other < distances.length; other++) {
            if (!active[other] || other == left || other == right) {
                continue;
            }
            int sizeOther = sizes[other];
            double updated = switch (linkage) {
                case "complete" -> Math.max(distances[left][other], distances[right][other]);
                case "average" -> (sizeLeft * distances[left][other]
                        + sizeRight * distances[right][other]) / combined;
                default -> ((sizeLeft + sizeOther) * distances[left][other]
                        + (sizeRight + sizeOther) * distances[right][other]
                        - sizeOther * distances[left][right]) / (double) (combined + sizeOther);
            };
            distances[left][other] = Math.max(0.0, updated);
            distances[other][left] = distances[left][other];
        }
        active[right] = false;
        sizes[left] = combined;
        for (int index = 0; index < owner.length; index++) {
            if (owner[index] == right) {
                owner[index] = left;
            }
        }
    }

    // -------------------------------------------------------------- silhouette

    /** Per-row silhouette; NULL for noise and when fewer than two clusters are present, 0 for singletons. */
    private static Double[] silhouette(double[][] points, int[] labels) {
        int n = points.length;
        Double[] result = new Double[n];
        int maxLabel = -1;
        for (int label : labels) {
            maxLabel = Math.max(maxLabel, label);
        }
        boolean[] present = new boolean[maxLabel + 1];
        int clusters = 0;
        for (int label : labels) {
            if (label >= 0 && !present[label]) {
                present[label] = true;
                clusters++;
            }
        }
        if (clusters < 2) {
            return result;
        }
        double[] sums = new double[maxLabel + 1];
        int[] counts = new int[maxLabel + 1];
        for (int point = 0; point < n; point++) {
            if (labels[point] < 0) {
                continue;
            }
            Arrays.fill(sums, 0.0);
            Arrays.fill(counts, 0);
            for (int other = 0; other < n; other++) {
                if (other == point || labels[other] < 0) {
                    continue;
                }
                sums[labels[other]] += StatsSupport.distance(points[point], points[other]);
                counts[labels[other]]++;
            }
            int own = labels[point];
            if (counts[own] == 0) {
                // Singleton cluster: a is undefined; set s = 0 (Rousseeuw, sklearn).
                result[point] = 0.0;
                continue;
            }
            double ownMean = sums[own] / counts[own];
            double nearestOther = Double.POSITIVE_INFINITY;
            for (int cluster = 0; cluster <= maxLabel; cluster++) {
                if (cluster != own && counts[cluster] > 0) {
                    nearestOther = Math.min(nearestOther, sums[cluster] / counts[cluster]);
                }
            }
            if (!Double.isFinite(nearestOther)) {
                result[point] = 0.0;
            } else {
                double denominator = Math.max(ownMean, nearestOther);
                result[point] = denominator == 0.0 ? 0.0 : (nearestOther - ownMean) / denominator;
            }
        }
        return result;
    }

    private static double squaredDistance(double[] left, double[] right) {
        double sum = 0.0;
        for (int index = 0; index < left.length; index++) {
            double delta = left[index] - right[index];
            sum += delta * delta;
        }
        return sum;
    }

    /** Feature-vector key: rows with equal features share one mapping entry. */
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
}
