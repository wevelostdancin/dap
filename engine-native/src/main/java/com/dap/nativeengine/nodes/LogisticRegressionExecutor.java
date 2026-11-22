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
 * dap.logistic_regression: binary logistic regression by iteratively reweighted least
 * squares (Newton-Raphson).
 *
 * <p>coefficients mode returns one row per term with the log-odds coefficient, standard
 * error (classical or HC1 sandwich), Wald z and p, the confidence interval on the
 * log-odds scale and the odds ratio, plus the model-level McFadden pseudo R2 and AUC.
 * predict mode stays a lazy plan over the input: the probability comes from the sigmoid
 * of the linear predictor with the coefficients as literals. An optional L2 penalty
 * (ridge, intercept excluded) keeps perfectly separated data finite; without it the
 * Newton iterations may not converge and {@code converged} reports that.</p>
 */
public final class LogisticRegressionExecutor implements NodeExecutor {
    private static final List<String> MODES = List.of("coefficients", "predict");
    private static final List<String> ROBUST = List.of("none", "hc1");
    /** The fit reads values into Java; same cap as the other in-memory statistics. */
    private static final int ROW_CAP = 100_000;
    private static final int DEFAULT_ITERATIONS = 50;
    private static final double DEFAULT_TOLERANCE = 1e-8;
    private static final String PROBABILITY = "probability";
    private static final String PREDICTION = "prediction";
    private static final List<ColumnSpec> COEFFICIENTS = List.of(
            ColumnSpec.of("term", "Utf8"),
            ColumnSpec.of("coefficient", "Float64"),
            ColumnSpec.of("std_error", "Float64"),
            ColumnSpec.of("z_statistic", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"),
            ColumnSpec.of("odds_ratio", "Float64"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("pseudo_r2", "Float64"),
            ColumnSpec.of("auc", "Float64"),
            ColumnSpec.of("iterations", "Int64"),
            ColumnSpec.of("converged", "Boolean"));

    @Override
    public String nodeId() {
        return "dap.logistic_regression";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> predictors = predictors(input, config);
        ColumnSpec response = binaryColumn(input, config);
        String mode = NodeSql.text(config, "mode", "coefficients").trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("logistic_regression: unknown mode '" + mode
                    + "' (expected coefficients or predict)");
        }
        String robust = NodeSql.text(config, "robust", "none").trim().toLowerCase(Locale.ROOT);
        if (!ROBUST.contains(robust)) {
            throw new IllegalArgumentException("logistic_regression: unknown robust '" + robust
                    + "' (expected none or hc1)");
        }
        int maxIterations = positiveInt(config, "max_iterations", DEFAULT_ITERATIONS);
        double tolerance = positiveNumber(config, "tolerance", DEFAULT_TOLERANCE);
        double l2 = nonNegativeNumber(config, "l2", 0.0);
        double alpha = alpha(config);
        String probabilityName = "predict".equals(mode) ? probabilityName(input, config) : null;
        List<SampleRow> rows = readRows(context, input, predictors, response);
        if (rows.isEmpty()) {
            return emptyPlan(context, input, mode, probabilityName);
        }
        Fit fit = fit(rows, predictors, robust, maxIterations, tolerance, l2, alpha);
        if ("predict".equals(mode)) {
            return predict(context, input, probabilityName, predictors, fit);
        }
        return coefficients(context, fit);
    }

    // -------------------------------------------------------------- data

    private record SampleRow(double[] x, double y) {
    }

    private record Fit(long n, int parameters, boolean defined, String[] terms, double[] beta,
                       double[] stdErrors, double[] zStatistics, Double[] pValues, double[] ciLow,
                       double[] ciHigh, double[] oddsRatios, Double pseudoR2, Double auc,
                       int iterations, boolean converged) {
    }

    private static List<SampleRow> readRows(NodeContext context, LazyPlan input,
                                            List<ColumnSpec> predictors, ColumnSpec response)
            throws SQLException {
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec predictor : predictors) {
            projection.add("CAST(" + NodeSql.quote(predictor.name()) + " AS DOUBLE)");
        }
        projection.add(responseExpression(response));
        StringJoiner where = new StringJoiner(" AND ");
        for (ColumnSpec predictor : predictors) {
            String quoted = NodeSql.quote(predictor.name());
            where.add(quoted + " IS NOT NULL AND isfinite(CAST(" + quoted + " AS DOUBLE))");
        }
        where.add(NodeSql.quote(response.name()) + " IS NOT NULL");
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + where + " LIMIT " + (ROW_CAP + 1);
        List<SampleRow> rows = new ArrayList<>();
        boolean positive = false;
        boolean negative = false;
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                double[] x = new double[predictors.size()];
                for (int i = 0; i < predictors.size(); i++) {
                    x[i] = result.getDouble(i + 1);
                }
                double y = result.getDouble(predictors.size() + 1);
                if (y != 0.0 && y != 1.0) {
                    throw new IllegalArgumentException("logistic_regression: 'y' column '"
                            + response.name() + "' must be binary (0/1 or boolean), got " + y);
                }
                positive |= y == 1.0;
                negative |= y == 0.0;
                rows.add(new SampleRow(x, y));
            }
        }
        if (rows.size() > ROW_CAP) {
            throw new IllegalArgumentException("logistic_regression: the fit is computed in memory and "
                    + "needs at most " + ROW_CAP + " rows, got more");
        }
        if (!rows.isEmpty() && (!positive || !negative)) {
            throw new IllegalArgumentException("logistic_regression: 'y' column '" + response.name()
                    + "' must contain both classes (0 and 1)");
        }
        return rows;
    }

    private static String responseExpression(ColumnSpec response) {
        return "Boolean".equals(response.arrowType())
                ? "CAST(" + NodeSql.quote(response.name()) + " AS INTEGER)"
                : "CAST(" + NodeSql.quote(response.name()) + " AS DOUBLE)";
    }

    // --------------------------------------------------------------- fit

    private static Fit fit(List<SampleRow> rows, List<ColumnSpec> predictorColumns, String robust,
                           int maxIterations, double tolerance, double l2, double alpha) {
        int predictors = predictorColumns.size();
        int parameters = predictors + 1;
        long n = rows.size();
        String[] terms = terms(predictorColumns);
        if (n <= parameters) {
            throw new IllegalArgumentException("logistic_regression: need more rows than parameters, got "
                    + n + " rows and " + parameters + " parameters (including the intercept); "
                    + "add rows or drop a predictor");
        }
        double[][] design = new double[(int) n][parameters];
        double[] y = new double[(int) n];
        for (int i = 0; i < n; i++) {
            design[i][0] = 1.0;
            System.arraycopy(rows.get(i).x(), 0, design[i], 1, predictors);
            y[i] = rows.get(i).y();
        }
        double[] beta = new double[parameters];
        double[] probabilities = new double[(int) n];
        double[][] inverse = null;
        int iterations = 0;
        boolean converged = false;
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            iterations = iteration;
            for (int i = 0; i < n; i++) {
                probabilities[i] = sigmoid(dot(design[i], beta));
            }
            double[][] hessian = new double[parameters][parameters];
            double[] gradient = new double[parameters];
            for (int i = 0; i < n; i++) {
                double weight = probabilities[i] * (1.0 - probabilities[i]);
                for (int j = 0; j < parameters; j++) {
                    gradient[j] += design[i][j] * (y[i] - probabilities[i]);
                    for (int k = 0; k < parameters; k++) {
                        hessian[j][k] += weight * design[i][j] * design[i][k];
                    }
                }
            }
            for (int j = 1; j < parameters; j++) {
                hessian[j][j] += l2;
                gradient[j] -= l2 * beta[j];
            }
            double[][] step;
            try {
                step = Matrix.invert(hessian);
            } catch (IllegalArgumentException singular) {
                break;
            }
            inverse = step;
            double largest = 0.0;
            for (int j = 0; j < parameters; j++) {
                double delta = dot(step[j], gradient);
                beta[j] += delta;
                largest = Math.max(largest, Math.abs(delta));
            }
            if (largest < tolerance) {
                converged = true;
                break;
            }
        }
        if (inverse == null) {
            return new Fit(n, parameters, false, terms, null, null, null, null, null, null, null,
                    null, null, iterations, converged);
        }
        for (int i = 0; i < n; i++) {
            probabilities[i] = sigmoid(dot(design[i], beta));
        }
        double[][] covariance = covariance(design, inverse, y, probabilities, robust, n, parameters);
        double[] stdErrors = new double[parameters];
        double[] zStatistics = new double[parameters];
        Double[] pValues = new Double[parameters];
        double[] ciLow = new double[parameters];
        double[] ciHigh = new double[parameters];
        double[] oddsRatios = new double[parameters];
        double critical = Distributions.normalQuantile(1.0 - alpha / 2.0);
        for (int j = 0; j < parameters; j++) {
            stdErrors[j] = Math.sqrt(Math.max(0.0, covariance[j][j]));
            if (stdErrors[j] == 0.0) {
                zStatistics[j] = Double.NaN;
                pValues[j] = null;
            } else {
                zStatistics[j] = beta[j] / stdErrors[j];
                pValues[j] = clamp(2.0 * (1.0 - Distributions.normalCdf(Math.abs(zStatistics[j]))));
            }
            ciLow[j] = beta[j] - critical * stdErrors[j];
            ciHigh[j] = beta[j] + critical * stdErrors[j];
            oddsRatios[j] = Math.exp(beta[j]);
        }
        Double pseudoR2 = pseudoR2(y, probabilities);
        Double auc = auc(y, probabilities);
        return new Fit(n, parameters, true, terms, beta, stdErrors, zStatistics, pValues, ciLow,
                ciHigh, oddsRatios, pseudoR2, auc, iterations, converged);
    }

    private static String[] terms(List<ColumnSpec> predictors) {
        String[] terms = new String[predictors.size() + 1];
        terms[0] = "intercept";
        for (int j = 0; j < predictors.size(); j++) {
            terms[j + 1] = predictors.get(j).name();
        }
        return terms;
    }

    /** (X'WX)^-1 or the HC1 sandwich (X'WX)^-1 [sum (y-p)^2 x x'] (X'WX)^-1 n/(n-p). */
    private static double[][] covariance(double[][] design, double[][] inverse, double[] y,
                                         double[] probabilities, String robust, long n,
                                         int parameters) {
        if (!"hc1".equals(robust)) {
            return inverse;
        }
        double[][] meat = new double[parameters][parameters];
        for (int i = 0; i < n; i++) {
            double residual = y[i] - probabilities[i];
            double weight = residual * residual;
            for (int j = 0; j < parameters; j++) {
                for (int k = 0; k < parameters; k++) {
                    meat[j][k] += weight * design[i][j] * design[i][k];
                }
            }
        }
        double[][] middle = Matrix.multiply(Matrix.multiply(inverse, meat), inverse);
        double factor = (double) n / (n - parameters);
        for (int j = 0; j < parameters; j++) {
            for (int k = 0; k < parameters; k++) {
                middle[j][k] *= factor;
            }
        }
        return middle;
    }

    /** McFadden pseudo R2: 1 - logLik / logLikNull. */
    private static Double pseudoR2(double[] y, double[] probabilities) {
        double positive = 0.0;
        for (double value : y) {
            positive += value;
        }
        double nullProbability = positive / y.length;
        if (nullProbability <= 0.0 || nullProbability >= 1.0) {
            return null;
        }
        double logLikelihood = 0.0;
        for (int i = 0; i < y.length; i++) {
            logLikelihood += y[i] * Math.log(clampProbability(probabilities[i]))
                    + (1.0 - y[i]) * Math.log(1.0 - clampProbability(probabilities[i]));
        }
        double nullLogLikelihood = y.length * (nullProbability * Math.log(nullProbability)
                + (1.0 - nullProbability) * Math.log(1.0 - nullProbability));
        return nullLogLikelihood == 0.0 ? null : 1.0 - logLikelihood / nullLogLikelihood;
    }

    /** Rank-based AUC (Mann-Whitney U over the predicted probabilities, ties averaged). */
    private static Double auc(double[] y, double[] probabilities) {
        long positive = 0;
        long negative = 0;
        for (double value : y) {
            if (value == 1.0) {
                positive++;
            } else {
                negative++;
            }
        }
        if (positive == 0 || negative == 0) {
            return null;
        }
        double[] ranks = HypothesisTestExecutor.averageRanks(probabilities);
        double rankSum = 0.0;
        for (int i = 0; i < y.length; i++) {
            if (y[i] == 1.0) {
                rankSum += ranks[i];
            }
        }
        return (rankSum - positive * (positive + 1) / 2.0) / ((double) positive * negative);
    }

    private static double sigmoid(double value) {
        if (value >= 0.0) {
            double exponential = Math.exp(-value);
            return 1.0 / (1.0 + exponential);
        }
        double exponential = Math.exp(value);
        return exponential / (1.0 + exponential);
    }

    private static double clampProbability(double probability) {
        return Math.max(1e-15, Math.min(1.0 - 1e-15, probability));
    }

    private static double dot(double[] left, double[] right) {
        double sum = 0.0;
        for (int i = 0; i < left.length; i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }

    // ------------------------------------------------------- coefficients

    private static LazyPlan coefficients(NodeContext context, Fit fit) {
        List<String> rows = new ArrayList<>();
        for (int term = 0; term < fit.terms().length; term++) {
            StringJoiner select = new StringJoiner(", ");
            select.add(NodeSql.literal(fit.terms()[term]) + " AS " + NodeSql.quote("term"));
            select.add(doubleLiteral(fit.defined() ? fit.beta()[term] : null)
                    + " AS " + NodeSql.quote("coefficient"));
            select.add(doubleLiteral(fit.defined() ? fit.stdErrors()[term] : null)
                    + " AS " + NodeSql.quote("std_error"));
            select.add(doubleLiteral(fit.defined() ? fit.zStatistics()[term] : null)
                    + " AS " + NodeSql.quote("z_statistic"));
            select.add(doubleLiteral(fit.defined() ? fit.pValues()[term] : null)
                    + " AS " + NodeSql.quote("p_value"));
            select.add(doubleLiteral(fit.defined() ? fit.ciLow()[term] : null)
                    + " AS " + NodeSql.quote("ci_low"));
            select.add(doubleLiteral(fit.defined() ? fit.ciHigh()[term] : null)
                    + " AS " + NodeSql.quote("ci_high"));
            select.add(doubleLiteral(fit.defined() ? fit.oddsRatios()[term] : null)
                    + " AS " + NodeSql.quote("odds_ratio"));
            select.add(fit.n() + " AS " + NodeSql.quote("n"));
            select.add(doubleLiteral(fit.pseudoR2()) + " AS " + NodeSql.quote("pseudo_r2"));
            select.add(doubleLiteral(fit.auc()) + " AS " + NodeSql.quote("auc"));
            select.add(fit.iterations() + " AS " + NodeSql.quote("iterations"));
            select.add(fit.converged() + " AS " + NodeSql.quote("converged"));
            rows.add("SELECT " + select);
        }
        return literalPlan(context, rows, COEFFICIENTS);
    }

    // ------------------------------------------------------------ predict

    private static String probabilityName(LazyPlan input, JsonNode config) {
        String name = NodeSql.text(config, "output", PROBABILITY);
        if (name.isBlank()) {
            throw new IllegalArgumentException("logistic_regression: 'output' must not be blank");
        }
        for (ColumnSpec column : input.columns()) {
            if (column.name().equals(name) || column.name().equals(PREDICTION)) {
                throw new IllegalArgumentException("logistic_regression: output column '"
                        + column.name() + "' already exists in the input");
            }
        }
        if (PREDICTION.equals(name)) {
            throw new IllegalArgumentException("logistic_regression: 'output' must not be '" + PREDICTION + "'");
        }
        return name;
    }

    private static LazyPlan predict(NodeContext context, LazyPlan input, String probabilityName,
                                    List<ColumnSpec> predictors, Fit fit) {
        List<ColumnSpec> output = new ArrayList<>(input.columns());
        output.add(ColumnSpec.of(probabilityName, "Float64"));
        output.add(ColumnSpec.of(PREDICTION, "Int64"));
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : input.columns()) {
            projection.add(NodeSql.quote(column.name()));
        }
        if (!fit.defined()) {
            projection.add("CAST(NULL AS DOUBLE) AS " + NodeSql.quote(probabilityName));
            projection.add("CAST(NULL AS BIGINT) AS " + NodeSql.quote(PREDICTION));
        } else {
            StringJoiner linear = new StringJoiner(" + ");
            linear.add(Double.toString(fit.beta()[0]));
            for (int j = 0; j < predictors.size(); j++) {
                linear.add("(" + Double.toString(fit.beta()[j + 1]) + " * CAST("
                        + NodeSql.quote(predictors.get(j).name()) + " AS DOUBLE))");
            }
            String probability = "(1.0 / (1.0 + exp(-(" + linear + "))))";
            projection.add(probability + " AS " + NodeSql.quote(probabilityName));
            projection.add("(CASE WHEN " + probability + " IS NULL THEN CAST(NULL AS BIGINT)"
                    + " WHEN " + probability + " >= 0.5 THEN 1 ELSE 0 END) AS "
                    + NodeSql.quote(PREDICTION));
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, output);
    }

    // -------------------------------------------------------------- helpers

    private static LazyPlan emptyPlan(NodeContext context, LazyPlan input, String mode,
                                      String probabilityName) {
        List<ColumnSpec> output = new ArrayList<>();
        if ("predict".equals(mode)) {
            output.addAll(input.columns());
            output.add(ColumnSpec.of(probabilityName, "Float64"));
            output.add(ColumnSpec.of(PREDICTION, "Int64"));
        } else {
            output.addAll(COEFFICIENTS);
        }
        return literalPlan(context, List.of(), output);
    }

    private static LazyPlan literalPlan(NodeContext context, List<String> rows,
                                        List<ColumnSpec> columns) {
        if (rows.isEmpty()) {
            StringJoiner select = new StringJoiner(", ");
            for (ColumnSpec column : columns) {
                select.add("CAST(NULL AS " + column.duckDbType() + ") AS " + NodeSql.quote(column.name()));
            }
            return new LazyPlan(context.db(), "SELECT " + select + " WHERE FALSE", columns);
        }
        return new LazyPlan(context.db(), String.join(" UNION ALL ", rows), columns);
    }

    private static String doubleLiteral(Double value) {
        return value == null || !Double.isFinite(value)
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
    }

    private static double clamp(double probability) {
        return Math.max(0.0, Math.min(1.0, probability));
    }

    // ----------------------------------------------------------- validation

    private static List<ColumnSpec> predictors(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("x");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("logistic_regression: 'x' must be a non-empty array of column names");
        }
        List<ColumnSpec> predictors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("logistic_regression: 'x' must contain non-empty strings");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("logistic_regression: duplicate 'x' column '" + name + "'");
            }
            ColumnSpec column;
            try {
                column = NodeSql.column(input.columns(), name);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("logistic_regression: unknown 'x' column '" + name + "'");
            }
            if (!column.numeric()) {
                throw new IllegalArgumentException("logistic_regression: 'x' column '" + name
                        + "' must be numeric, got " + column.arrowType());
            }
            predictors.add(column);
        }
        return predictors;
    }

    private static ColumnSpec binaryColumn(LazyPlan input, JsonNode config) {
        String name = NodeSql.requiredText(config, "y");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("logistic_regression: unknown 'y' column '" + name + "'");
        }
        if (!column.numeric() && !"Boolean".equals(column.arrowType())) {
            throw new IllegalArgumentException("logistic_regression: 'y' column '" + name
                    + "' must be numeric (0/1) or boolean, got " + column.arrowType());
        }
        return column;
    }

    private static int positiveInt(JsonNode config, String field, int fallback) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber() || value.longValue() <= 0 || value.longValue() > 1_000_000) {
            throw new IllegalArgumentException("logistic_regression: '" + field
                    + "' must be a positive integer");
        }
        return value.intValue();
    }

    private static double positiveNumber(JsonNode config, String field, double fallback) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0.0) || Double.isInfinite(value.doubleValue())) {
            throw new IllegalArgumentException("logistic_regression: '" + field
                    + "' must be a positive number");
        }
        return value.doubleValue();
    }

    private static double nonNegativeNumber(JsonNode config, String field, double fallback) {
        JsonNode value = config.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isNumber() || !(value.doubleValue() >= 0.0) || Double.isInfinite(value.doubleValue())) {
            throw new IllegalArgumentException("logistic_regression: '" + field
                    + "' must be a non-negative number");
        }
        return value.doubleValue();
    }

    private static double alpha(JsonNode config) {
        JsonNode value = config.get("alpha");
        if (value == null || value.isNull()) {
            return 0.05;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0.0) || !(value.doubleValue() < 1.0)) {
            throw new IllegalArgumentException("logistic_regression: 'alpha' must be a number in (0, 1)");
        }
        return value.doubleValue();
    }
}
