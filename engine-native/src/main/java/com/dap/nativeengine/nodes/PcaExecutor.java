package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.Matrix;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.pca: principal component analysis of numeric columns by the covariance matrix and a
 * Jacobi eigen decomposition.
 *
 * <p>Three output modes share the same fit: {@code scores} appends pc1..pcN as lazy SQL
 * expressions over the input (a linear combination of the standardized, centered columns),
 * {@code loadings} returns one row per component and column, and {@code explained} returns the
 * eigenvalue, the explained-variance fraction and the cumulative fraction per component.
 * Features are optionally z-score standardized first; the sign of every component is fixed so
 * that its largest absolute loading is positive, which keeps the output reproducible.</p>
 */
public final class PcaExecutor implements NodeExecutor {
    private static final String NODE = "pca";
    private static final Set<String> MODES = Set.of("scores", "loadings", "explained");
    private static final int DEFAULT_COMPONENTS = 2;
    private static final int DEFAULT_MAX_ROWS = 100_000;
    private static final List<ColumnSpec> LOADINGS = List.of(
            ColumnSpec.of("component", "Int64"),
            ColumnSpec.of("column", "Utf8"),
            ColumnSpec.of("loading", "Float64"));
    private static final List<ColumnSpec> EXPLAINED = List.of(
            ColumnSpec.of("component", "Int64"),
            ColumnSpec.of("eigenvalue", "Float64"),
            ColumnSpec.of("explained_variance", "Float64"),
            ColumnSpec.of("cumulative", "Float64"));

    @Override
    public String nodeId() {
        return "dap.pca";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> features = StatsSupport.numericColumns(input, config, "columns", NODE);
        int components = StatsSupport.positiveInt(config, "components", DEFAULT_COMPONENTS, NODE, 1_000_000);
        if (components > features.size()) {
            throw new IllegalArgumentException("pca: components=" + components + " exceeds the "
                    + features.size() + " selected columns; reduce components");
        }
        boolean standardize = NodeSql.bool(config, "standardize", true);
        int maxRows = StatsSupport.positiveInt(config, "max_rows", DEFAULT_MAX_ROWS, NODE, 10_000_000);
        String mode = NodeSql.text(config, "mode", "scores").trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("pca: unknown mode '" + mode
                    + "' (expected scores, loadings or explained)");
        }
        if ("scores".equals(mode)) {
            for (int component = 0; component < components; component++) {
                TransformSupport.requireNewName(input.columns(), "pc" + (component + 1), NODE);
            }
        }
        List<double[]> points = StatsSupport.readFeatures(context, input, features, maxRows, NODE);
        if (points.size() < 2) {
            throw new IllegalArgumentException("pca: need at least 2 complete rows to fit the "
                    + "covariance matrix, got " + points.size()
                    + "; filter the input or select columns with fewer NULL values");
        }
        Fit fit = fit(points, features.size(), standardize, components);
        return switch (mode) {
            case "loadings" -> loadings(context, features, fit);
            case "explained" -> explained(context, fit);
            default -> scores(context, input, features, fit, standardize);
        };
    }

    private record Fit(double[] mean, double[] scale, double[][] loadings, double[] eigenvalues,
                       double[] explained, double[] cumulative) {
    }

    private static Fit fit(List<double[]> points, int dimensions, boolean standardize, int components) {
        int n = points.size();
        double[] mean = new double[dimensions];
        for (double[] point : points) {
            for (int dimension = 0; dimension < dimensions; dimension++) {
                mean[dimension] += point[dimension];
            }
        }
        for (int dimension = 0; dimension < dimensions; dimension++) {
            mean[dimension] /= n;
        }
        double[] scale = new double[dimensions];
        if (standardize) {
            double[] squares = new double[dimensions];
            for (double[] point : points) {
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    double delta = point[dimension] - mean[dimension];
                    squares[dimension] += delta * delta;
                }
            }
            for (int dimension = 0; dimension < dimensions; dimension++) {
                double variance = squares[dimension] / (n - 1);
                scale[dimension] = variance <= 0.0 ? 0.0 : Math.sqrt(variance);
            }
        } else {
            Arrays.fill(scale, 1.0);
        }
        double[][] centered = new double[n][dimensions];
        for (int row = 0; row < n; row++) {
            for (int dimension = 0; dimension < dimensions; dimension++) {
                double value = points.get(row)[dimension] - mean[dimension];
                if (standardize) {
                    value = scale[dimension] == 0.0 ? 0.0 : value / scale[dimension];
                }
                centered[row][dimension] = value;
            }
        }
        double[][] covariance = Matrix.multiply(Matrix.transpose(centered), centered);
        for (int row = 0; row < dimensions; row++) {
            for (int column = 0; column < dimensions; column++) {
                covariance[row][column] /= (n - 1);
            }
        }
        Matrix.Eigen eigen = Matrix.symmetricEigen(covariance);
        double total = 0.0;
        for (double eigenvalue : eigen.values()) {
            total += Math.max(0.0, eigenvalue);
        }
        double[] eigenvalues = new double[components];
        for (int component = 0; component < components; component++) {
            eigenvalues[component] = Math.max(0.0, eigen.values()[component]);
        }
        double[][] loadings = new double[dimensions][components];
        double[] explained = new double[components];
        double[] cumulative = new double[components];
        double running = 0.0;
        for (int component = 0; component < components; component++) {
            double[] vector = eigen.vectors()[component].clone();
            int largest = 0;
            for (int dimension = 1; dimension < dimensions; dimension++) {
                if (Math.abs(vector[dimension]) > Math.abs(vector[largest])) {
                    largest = dimension;
                }
            }
            if (vector[largest] < 0.0) {
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    vector[dimension] = -vector[dimension];
                }
            }
            for (int dimension = 0; dimension < dimensions; dimension++) {
                loadings[dimension][component] = vector[dimension];
            }
            explained[component] = total == 0.0 ? 0.0 : eigenvalues[component] / total;
            running += explained[component];
            cumulative[component] = running;
        }
        return new Fit(mean, scale, loadings, eigenvalues, explained, cumulative);
    }

    // ---------------------------------------------------------------- scores

    private static LazyPlan scores(NodeContext context, LazyPlan input, List<ColumnSpec> features,
                                   Fit fit, boolean standardize) {
        List<ColumnSpec> added = new ArrayList<>(fit.loadings()[0].length);
        List<String> expressions = new ArrayList<>(added.size());
        for (int component = 0; component < fit.loadings()[0].length; component++) {
            String name = "pc" + (component + 1);
            TransformSupport.requireNewName(input.columns(), name, NODE);
            added.add(ColumnSpec.of(name, "Float64"));
            StringJoiner sum = new StringJoiner(" + ");
            for (int dimension = 0; dimension < features.size(); dimension++) {
                String value = "dap_input." + NodeSql.quote(features.get(dimension).name());
                String standardized;
                if (!standardize) {
                    standardized = "(CAST(" + value + " AS DOUBLE) - "
                            + Double.toString(fit.mean()[dimension]) + ")";
                } else if (fit.scale()[dimension] == 0.0) {
                    standardized = "(CASE WHEN " + value + " IS NULL THEN CAST(NULL AS DOUBLE)"
                            + " ELSE 0.0 END)";
                } else {
                    standardized = "((CAST(" + value + " AS DOUBLE) - "
                            + Double.toString(fit.mean()[dimension]) + ") / "
                            + Double.toString(fit.scale()[dimension]) + ")";
                }
                sum.add("(" + Double.toString(fit.loadings()[dimension][component]) + " * "
                        + standardized + ")");
            }
            expressions.add("(" + sum + ") AS " + NodeSql.quote(name));
        }
        return TransformSupport.append(input, expressions, added);
    }

    // -------------------------------------------------------------- loadings

    private static LazyPlan loadings(NodeContext context, List<ColumnSpec> features, Fit fit) {
        List<String> rows = new ArrayList<>();
        for (int component = 0; component < fit.loadings()[0].length; component++) {
            for (int dimension = 0; dimension < features.size(); dimension++) {
                StringJoiner select = new StringJoiner(", ");
                select.add("CAST(" + (component + 1) + " AS BIGINT) AS " + NodeSql.quote("component"));
                select.add(NodeSql.literal(features.get(dimension).name()) + " AS "
                        + NodeSql.quote("column"));
                select.add(StatsSupport.doubleLiteral(fit.loadings()[dimension][component]) + " AS "
                        + NodeSql.quote("loading"));
                rows.add("SELECT " + select);
            }
        }
        return StatsSupport.literalPlan(context, rows, LOADINGS);
    }

    // ------------------------------------------------------------- explained

    private static LazyPlan explained(NodeContext context, Fit fit) {
        List<String> rows = new ArrayList<>();
        for (int component = 0; component < fit.explained().length; component++) {
            StringJoiner select = new StringJoiner(", ");
            select.add("CAST(" + (component + 1) + " AS BIGINT) AS " + NodeSql.quote("component"));
            select.add(StatsSupport.doubleLiteral(fit.eigenvalues()[component]) + " AS "
                    + NodeSql.quote("eigenvalue"));
            select.add(StatsSupport.doubleLiteral(fit.explained()[component]) + " AS "
                    + NodeSql.quote("explained_variance"));
            select.add(StatsSupport.doubleLiteral(fit.cumulative()[component]) + " AS "
                    + NodeSql.quote("cumulative"));
            rows.add("SELECT " + select);
        }
        return StatsSupport.literalPlan(context, rows, EXPLAINED);
    }
}
