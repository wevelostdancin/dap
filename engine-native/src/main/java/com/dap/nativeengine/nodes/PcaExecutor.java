package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.Matrix;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
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
 * The covariance matrix is built from DuckDB sums and cross products, so only p-by-p
 * matrices live in Java and the row count is unbounded. Features are optionally z-score
 * standardized first; the sign of every component is fixed so that its largest absolute
 * loading is positive, which keeps the output reproducible.</p>
 */
public final class PcaExecutor implements NodeExecutor {
    private static final String NODE = "pca";
    private static final Set<String> MODES = Set.of("scores", "loadings", "explained");
    private static final int DEFAULT_COMPONENTS = 2;
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
        int sampleRows = config == null ? 0 : config.path("sample_rows").asInt(0);
        if (sampleRows < 0) {
            throw new IllegalArgumentException("pca: 'sample_rows' must be 0 or positive");
        }
        int seed = config == null ? 42 : config.path("seed").asInt(42);
        if (seed < 0) {
            throw new IllegalArgumentException("pca: 'seed' must be 0 or positive");
        }
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
        List<String> expressions = new ArrayList<>(features.size());
        StringJoiner where = new StringJoiner(" AND ");
        for (ColumnSpec feature : features) {
            String value = MomentSupport.doubleColumn(feature.name());
            expressions.add(value);
            where.add(NodeSql.quote(feature.name()) + " IS NOT NULL AND isfinite(" + value + ")");
        }
        String table = input.asTable();
        String source = sampleRows <= 0
                ? table + " AS dap_input WHERE " + where
                : "(SELECT * FROM " + table + " AS dap_src WHERE " + where
                        + " USING SAMPLE reservoir(" + sampleRows + " ROWS) REPEATABLE(" + seed
                        + ")) AS dap_input WHERE TRUE";
        MomentSupport.Moments moments = MomentSupport.read(context, "", " FROM " + source,
                expressions, false, List.of()).values().iterator().next();
        if (moments.count() < 2) {
            throw new IllegalArgumentException("pca: need at least 2 complete rows to fit the "
                    + "covariance matrix, got " + moments.count()
                    + "; filter the input or select columns with fewer NULL values");
        }
        Fit fit = fit(moments, features.size(), standardize, components);
        return switch (mode) {
            case "loadings" -> loadings(context, features, fit);
            case "explained" -> explained(context, fit);
            default -> scores(context, input, features, fit, standardize);
        };
    }

    private record Fit(double[] mean, double[] scale, double[][] loadings, double[] eigenvalues,
                       double[] explained, double[] cumulative) {
    }

    private static Fit fit(MomentSupport.Moments moments, int dimensions, boolean standardize,
                           int components) {
        double[] mean = new double[dimensions];
        double[] scale = new double[dimensions];
        double[][] covariance = new double[dimensions][dimensions];
        for (int i = 0; i < dimensions; i++) {
            mean[i] = moments.mean(i);
            scale[i] = 1.0;
        }
        for (int i = 0; i < dimensions; i++) {
            for (int j = 0; j < dimensions; j++) {
                covariance[i][j] = moments.covariance(i, j) / (moments.count() - 1.0);
            }
        }
        if (standardize) {
            for (int i = 0; i < dimensions; i++) {
                scale[i] = covariance[i][i] <= 0.0 ? 0.0 : Math.sqrt(covariance[i][i]);
            }
            for (int i = 0; i < dimensions; i++) {
                for (int j = 0; j < dimensions; j++) {
                    covariance[i][j] = scale[i] == 0.0 || scale[j] == 0.0
                            ? 0.0
                            : covariance[i][j] / (scale[i] * scale[j]);
                }
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
