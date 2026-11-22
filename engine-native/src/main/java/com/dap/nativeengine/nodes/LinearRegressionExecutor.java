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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.linear_regression: least squares with one or more numeric predictors.
 *
 * <p>coefficients mode returns one row per term (intercept and each predictor) with the
 * standard error, t statistic, p-value, confidence interval and variance inflation
 * factor, plus the model fit (r2, adj_r2, F, RMSE, residual df); diagnostics mode
 * returns one row per fit with the model-level statistics (including Durbin-Watson,
 * AIC and BIC); predict mode returns the input rows plus the prediction, residual and
 * prediction interval. Standard errors are classical (none) or heteroskedasticity
 * consistent (hc1). The fit is computed in Java from X'X, the inverse and the residuals;
 * predict stays a lazy plan over the input with the coefficients as literals (or as a
 * literal coefficient table joined on the group columns).</p>
 */
public final class LinearRegressionExecutor implements NodeExecutor {
    private static final List<String> MODES = List.of("coefficients", "predict", "diagnostics");
    private static final List<String> ROBUST = List.of("none", "hc1");
    /** The fit reads values into Java; same cap as the other in-memory statistics. */
    private static final int ROW_CAP = 100_000;
    private static final String RESIDUAL = "residual";
    private static final String LOWER = "prediction_lower";
    private static final String UPPER = "prediction_upper";
    private static final String SAMPLED_ROWS = "sampled_rows";
    private static final String TOTAL_ROWS = "total_rows";
    private static final List<ColumnSpec> COEFFICIENTS = List.of(
            ColumnSpec.of("term", "Utf8"),
            ColumnSpec.of("coefficient", "Float64"),
            ColumnSpec.of("std_error", "Float64"),
            ColumnSpec.of("t_statistic", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("r2", "Float64"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("adj_r2", "Float64"),
            ColumnSpec.of("f_statistic", "Float64"),
            ColumnSpec.of("f_p_value", "Float64"),
            ColumnSpec.of("rmse", "Float64"),
            ColumnSpec.of("df_residual", "Float64"),
            ColumnSpec.of("vif", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"));
    private static final List<ColumnSpec> DIAGNOSTICS = List.of(
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("df_residual", "Float64"),
            ColumnSpec.of("r2", "Float64"),
            ColumnSpec.of("adj_r2", "Float64"),
            ColumnSpec.of("f_statistic", "Float64"),
            ColumnSpec.of("f_p_value", "Float64"),
            ColumnSpec.of("rmse", "Float64"),
            ColumnSpec.of("durbin_watson", "Float64"),
            ColumnSpec.of("aic", "Float64"),
            ColumnSpec.of("bic", "Float64"));

    @Override
    public String nodeId() {
        return "dap.linear_regression";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> predictors = predictors(input, config);
        ColumnSpec response = numericColumn(input, config, "y");
        List<ColumnSpec> groups = groups(input, config);
        String mode = NodeSql.text(config, "mode", "coefficients").trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("linear_regression: unknown mode '" + mode
                    + "' (expected coefficients, predict, diagnostics)");
        }
        boolean intercept = NodeSql.bool(config, "intercept", true);
        String robust = NodeSql.text(config, "robust", "none").trim().toLowerCase(Locale.ROOT);
        if (!ROBUST.contains(robust)) {
            throw new IllegalArgumentException("linear_regression: unknown robust '" + robust
                    + "' (expected none or hc1)");
        }
        double alpha = alpha(config);
        int sampleRows = config == null ? 0 : config.path("sample_rows").asInt(0);
        if (sampleRows < 0) {
            throw new IllegalArgumentException("linear_regression: 'sample_rows' must be 0 or positive");
        }
        String prediction = "predict".equals(mode) ? predictionName(input, config) : null;
        Sample sample = readRows(context, input, groups, predictors, response, sampleRows);
        List<SampleRow> rows = sample.rows();
        LinkedHashMap<List<Object>, List<SampleRow>> grouped = group(rows, groups.size());
        LinkedHashMap<List<Object>, Model> models = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return emptyPlan(context, input, predictors, groups, mode, config);
        }
        for (Map.Entry<List<Object>, List<SampleRow>> entry : grouped.entrySet()) {
            models.put(entry.getKey(), fit(entry.getValue(), predictors, intercept, robust,
                    alpha, entry.getKey()));
        }
        if ("predict".equals(mode)) {
            return predict(context, input, prediction, predictors, response, groups, models, intercept,
                    rows.size(), sample.totalRows());
        }
        if ("diagnostics".equals(mode)) {
            return diagnostics(context, groups, models);
        }
        return coefficients(context, groups, models);
    }

    // -------------------------------------------------------------- data

    private record SampleRow(List<Object> groupValues, double[] x, double y) {
    }

    /** Rows actually fitted, plus the number of eligible rows before the sample LIMIT. */
    private record Sample(List<SampleRow> rows, long totalRows) {
    }

    /**
     * One fitted model. {@code defined} is false when the design matrix is singular (a
     * constant or collinear predictor), in which case every estimate is NULL but the
     * row count and residual degrees of freedom are still reported.
     */
    private record Model(long n, int parameters, int predictors, boolean intercept, boolean defined,
                         String[] terms, double[] beta, double[] stdErrors, double[] tStatistics,
                         Double[] pValues, double[] ciLow, double[] ciHigh, Double[] vif,
                         double[][] covariance, double mse, Double rmse, double dfResidual,
                         Double r2, Double adjR2, Double fStatistic, Double fPValue,
                         double[] residuals, Double durbinWatson, Double aic, Double bic) {
    }

    /**
     * Eligible rows in a canonical order: every projected column is a sort key, so the
     * first-N sample is reproducible on the same input. Rows tied on all keys are
     * interchangeable for the least-squares fit. {@code COUNT(*) OVER ()} reports the
     * unsampled total in the same scan, even when LIMIT cuts the result short.
     */
    private static Sample readRows(NodeContext context, LazyPlan input,
                                   List<ColumnSpec> groups, List<ColumnSpec> predictors,
                                   ColumnSpec response, int sampleRows) throws SQLException {
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec group : groups) {
            projection.add(NodeSql.quote(group.name()));
        }
        for (ColumnSpec predictor : predictors) {
            projection.add("CAST(" + NodeSql.quote(predictor.name()) + " AS DOUBLE)");
        }
        projection.add("CAST(" + NodeSql.quote(response.name()) + " AS DOUBLE)");
        String sql = "SELECT " + projection + ", COUNT(*) OVER () AS " + NodeSql.quote("dap_total")
                + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + sampleWhere(predictors, response)
                + " ORDER BY " + sampleOrder(groups, predictors, response)
                + " LIMIT " + (sampleRows > 0 ? sampleRows : ROW_CAP + 1);
        List<SampleRow> rows = new ArrayList<>();
        long total = 0;
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                total = result.getLong("dap_total");
                List<Object> groupValues = new ArrayList<>(groups.size());
                for (int i = 0; i < groups.size(); i++) {
                    groupValues.add(result.getObject(i + 1));
                }
                double[] x = new double[predictors.size()];
                for (int i = 0; i < predictors.size(); i++) {
                    x[i] = result.getDouble(groups.size() + i + 1);
                }
                rows.add(new SampleRow(groupValues,
                        x, result.getDouble(groups.size() + predictors.size() + 1)));
            }
        }
        if (sampleRows == 0 && total > ROW_CAP) {
            throw new IllegalArgumentException("linear_regression: the fit is computed in memory and needs "
                    + "at most " + ROW_CAP + " rows, got more; set sample_rows=N to fit on a "
                    + "deterministic first-N sample, or pre-filter the input");
        }
        return new Sample(rows, total);
    }

    private static String sampleWhere(List<ColumnSpec> predictors, ColumnSpec response) {
        StringJoiner where = new StringJoiner(" AND ");
        for (ColumnSpec predictor : predictors) {
            where.add(NodeSql.quote(predictor.name()) + " IS NOT NULL");
        }
        where.add(NodeSql.quote(response.name()) + " IS NOT NULL");
        return where.toString();
    }

    private static String sampleOrder(List<ColumnSpec> groups, List<ColumnSpec> predictors,
                                      ColumnSpec response) {
        StringJoiner order = new StringJoiner(", ");
        for (ColumnSpec column : groups) {
            order.add(NodeSql.quote(column.name()));
        }
        for (ColumnSpec column : predictors) {
            order.add(NodeSql.quote(column.name()));
        }
        order.add(NodeSql.quote(response.name()));
        return order.toString();
    }

    private static LinkedHashMap<List<Object>, List<SampleRow>> group(List<SampleRow> rows, int groupCount) {
        LinkedHashMap<List<Object>, List<SampleRow>> grouped = new LinkedHashMap<>();
        if (groupCount == 0) {
            grouped.put(List.of(), rows);
            return grouped;
        }
        for (SampleRow row : rows) {
            grouped.computeIfAbsent(row.groupValues(), ignored -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    // --------------------------------------------------------------- fit

    private static Model fit(List<SampleRow> rows, List<ColumnSpec> predictorColumns,
                             boolean intercept, String robust, double alpha,
                             List<Object> groupValues) {
        int predictors = predictorColumns.size();
        int parameters = predictors + (intercept ? 1 : 0);
        long n = rows.size();
        double dfResidual = n - parameters;
        String[] terms = terms(predictorColumns, intercept);
        if (n <= parameters) {
            String where = groupValues.isEmpty() ? "" : " in group " + groupValues + "";
            throw new IllegalArgumentException("linear_regression: need more rows than parameters, got "
                    + n + " rows" + where + " and " + parameters + " parameters (including the intercept); "
                    + "add rows, drop a predictor or set intercept=false");
        }
        double[][] design = new double[(int) n][parameters];
        double[] y = new double[(int) n];
        for (int i = 0; i < n; i++) {
            int column = 0;
            if (intercept) {
                design[i][column++] = 1.0;
            }
            for (int j = 0; j < predictors; j++) {
                design[i][column++] = rows.get(i).x()[j];
            }
            y[i] = rows.get(i).y();
        }
        double[][] xtx = new double[parameters][parameters];
        double[] xty = new double[parameters];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < parameters; j++) {
                xty[j] += design[i][j] * y[i];
                for (int k = 0; k < parameters; k++) {
                    xtx[j][k] += design[i][j] * design[i][k];
                }
            }
        }
        double[][] inverse;
        try {
            inverse = Matrix.invert(xtx);
        } catch (IllegalArgumentException singular) {
            return undefined(n, parameters, predictors, intercept, terms, dfResidual);
        }
        double[] beta = new double[parameters];
        for (int j = 0; j < parameters; j++) {
            beta[j] = dot(inverse[j], xty);
        }
        double[] residuals = new double[(int) n];
        double sse = 0.0;
        for (int i = 0; i < n; i++) {
            residuals[i] = y[i] - dot(design[i], beta);
            sse += residuals[i] * residuals[i];
        }
        double mean = 0.0;
        for (double value : y) {
            mean += value;
        }
        mean /= n;
        double sst = 0.0;
        for (double value : y) {
            double centered = intercept ? value - mean : value;
            sst += centered * centered;
        }
        double mse = sse / dfResidual;
        Double r2 = sst > 0.0 ? 1.0 - sse / sst : null;
        Double adjR2 = r2 == null ? null
                : 1.0 - (1.0 - r2) * (intercept ? n - 1.0 : n) / dfResidual;
        double df1 = parameters - (intercept ? 1.0 : 0.0);
        Double fStatistic = null;
        Double fPValue = null;
        if (sse > 0.0 && df1 > 0.0) {
            fStatistic = ((sst - sse) / df1) / mse;
            fPValue = clamp(1.0 - Distributions.fCdf(fStatistic, df1, dfResidual));
        }
        Double rmse = Math.sqrt(mse);
        double[][] covariance = covariance(design, inverse, residuals, sse, mse, n, parameters, robust);
        double[] stdErrors = new double[parameters];
        double[] tStatistics = new double[parameters];
        Double[] pValues = new Double[parameters];
        double[] ciLow = new double[parameters];
        double[] ciHigh = new double[parameters];
        double critical = Distributions.tQuantile(1.0 - alpha / 2.0, dfResidual);
        for (int j = 0; j < parameters; j++) {
            stdErrors[j] = Math.sqrt(Math.max(0.0, covariance[j][j]));
            if (stdErrors[j] == 0.0) {
                tStatistics[j] = Double.NaN;
                pValues[j] = null;
            } else {
                tStatistics[j] = beta[j] / stdErrors[j];
                pValues[j] = clamp(2.0 * (1.0 - Distributions.tCdf(Math.abs(tStatistics[j]),
                        dfResidual)));
            }
            ciLow[j] = beta[j] - critical * stdErrors[j];
            ciHigh[j] = beta[j] + critical * stdErrors[j];
        }
        Double[] vif = vif(rows, predictors, intercept);
        Double durbinWatson = durbinWatson(residuals, sse);
        Double aic = sse > 0.0 ? n * Math.log(sse / n) + 2.0 * parameters : null;
        Double bic = sse > 0.0 ? n * Math.log(sse / n) + Math.log(n) * parameters : null;
        return new Model(n, parameters, predictors, intercept, true, terms, beta, stdErrors,
                tStatistics, pValues, ciLow, ciHigh, vif, covariance, mse, rmse, dfResidual, r2,
                adjR2, fStatistic, fPValue, residuals, durbinWatson, aic, bic);
    }

    private static Model undefined(long n, int parameters, int predictors, boolean intercept,
                                   String[] terms, double dfResidual) {
        return new Model(n, parameters, predictors, intercept, false, terms, null, null, null,
                null, null, null, null, null, Double.NaN, null, dfResidual, null, null, null,
                null, null, null, null, null);
    }

    private static String[] terms(List<ColumnSpec> predictors, boolean intercept) {
        String[] terms = new String[predictors.size() + (intercept ? 1 : 0)];
        int at = 0;
        if (intercept) {
            terms[at++] = "intercept";
        }
        for (ColumnSpec predictor : predictors) {
            terms[at++] = predictor.name();
        }
        return terms;
    }

    /** (X'X)^-1 scaled by the residual variance, or by the HC1 sandwich estimator. */
    private static double[][] covariance(double[][] design, double[][] inverse, double[] residuals,
                                         double sse, double mse, long n, int parameters, String robust) {
        if (!"hc1".equals(robust)) {
            double[][] result = new double[parameters][parameters];
            for (int j = 0; j < parameters; j++) {
                for (int k = 0; k < parameters; k++) {
                    result[j][k] = mse * inverse[j][k];
                }
            }
            return result;
        }
        double[][] meat = new double[parameters][parameters];
        for (int i = 0; i < n; i++) {
            double weight = residuals[i] * residuals[i];
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

    /** VIF per predictor: 1 / (1 - R2) of the predictor regressed on the others. */
    private static Double[] vif(List<SampleRow> rows, int predictors, boolean intercept) {
        Double[] result = new Double[predictors];
        if (!intercept) {
            return result;
        }
        if (predictors == 1) {
            result[0] = 1.0;
            return result;
        }
        for (int j = 0; j < predictors; j++) {
            List<double[]> others = new ArrayList<>();
            for (int k = 0; k < predictors; k++) {
                if (k != j) {
                    others.add(column(rows, k));
                }
            }
            Double r2 = rSquared(column(rows, j), others);
            result[j] = r2 == null || r2 >= 1.0 - 1e-12 ? null : 1.0 / (1.0 - r2);
        }
        return result;
    }

    private static double[] column(List<SampleRow> rows, int predictor) {
        double[] values = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            values[i] = rows.get(i).x()[predictor];
        }
        return values;
    }

    /** R2 of an intercept-only-plus-others regression, or null when it is singular. */
    private static Double rSquared(double[] target, List<double[]> others) {
        int n = target.length;
        int parameters = others.size() + 1;
        double[][] xtx = new double[parameters][parameters];
        double[] xty = new double[parameters];
        for (int i = 0; i < n; i++) {
            double[] row = new double[parameters];
            row[0] = 1.0;
            for (int j = 0; j < others.size(); j++) {
                row[j + 1] = others.get(j)[i];
            }
            for (int j = 0; j < parameters; j++) {
                xty[j] += row[j] * target[i];
                for (int k = 0; k < parameters; k++) {
                    xtx[j][k] += row[j] * row[k];
                }
            }
        }
        double[][] inverse;
        try {
            inverse = Matrix.invert(xtx);
        } catch (IllegalArgumentException singular) {
            return null;
        }
        double[] beta = new double[parameters];
        for (int j = 0; j < parameters; j++) {
            beta[j] = dot(inverse[j], xty);
        }
        double mean = 0.0;
        for (double value : target) {
            mean += value;
        }
        mean /= n;
        double sse = 0.0;
        double sst = 0.0;
        for (int i = 0; i < n; i++) {
            double fitted = beta[0];
            for (int j = 0; j < others.size(); j++) {
                fitted += beta[j + 1] * others.get(j)[i];
            }
            sse += (target[i] - fitted) * (target[i] - fitted);
            sst += (target[i] - mean) * (target[i] - mean);
        }
        return sst > 0.0 ? 1.0 - sse / sst : null;
    }

    private static Double durbinWatson(double[] residuals, double sse) {
        if (sse <= 0.0) {
            return null;
        }
        double sum = 0.0;
        for (int i = 1; i < residuals.length; i++) {
            double difference = residuals[i] - residuals[i - 1];
            sum += difference * difference;
        }
        return sum / sse;
    }

    private static double dot(double[] left, double[] right) {
        double sum = 0.0;
        for (int i = 0; i < left.length; i++) {
            sum += left[i] * right[i];
        }
        return sum;
    }

    // ------------------------------------------------------- coefficients

    private static LazyPlan coefficients(NodeContext context, List<ColumnSpec> groups,
                                         LinkedHashMap<List<Object>, Model> models) {
        List<ColumnSpec> output = new ArrayList<>(groups);
        output.addAll(COEFFICIENTS);
        List<String> rows = new ArrayList<>();
        for (Map.Entry<List<Object>, Model> entry : models.entrySet()) {
            Model model = entry.getValue();
            for (int term = 0; term < model.terms().length; term++) {
                StringJoiner select = new StringJoiner(", ");
                addGroupLiterals(select, groups, entry.getKey());
                select.add(NodeSql.literal(model.terms()[term]) + " AS " + NodeSql.quote("term"));
                select.add(doubleLiteral(model.defined() ? model.beta()[term] : null)
                        + " AS " + NodeSql.quote("coefficient"));
                select.add(doubleLiteral(model.defined() ? model.stdErrors()[term] : null)
                        + " AS " + NodeSql.quote("std_error"));
                select.add(doubleLiteral(model.defined() ? model.tStatistics()[term] : null)
                        + " AS " + NodeSql.quote("t_statistic"));
                select.add(doubleLiteral(model.defined() ? model.pValues()[term] : null)
                        + " AS " + NodeSql.quote("p_value"));
                select.add(doubleLiteral(model.r2()) + " AS " + NodeSql.quote("r2"));
                select.add(model.n() + " AS " + NodeSql.quote("n"));
                select.add(doubleLiteral(model.adjR2()) + " AS " + NodeSql.quote("adj_r2"));
                select.add(doubleLiteral(model.fStatistic()) + " AS " + NodeSql.quote("f_statistic"));
                select.add(doubleLiteral(model.fPValue()) + " AS " + NodeSql.quote("f_p_value"));
                select.add(doubleLiteral(model.rmse()) + " AS " + NodeSql.quote("rmse"));
                select.add(doubleLiteral(model.dfResidual()) + " AS " + NodeSql.quote("df_residual"));
                select.add(doubleLiteral(vifValue(model, term)) + " AS " + NodeSql.quote("vif"));
                select.add(doubleLiteral(model.defined() ? model.ciLow()[term] : null)
                        + " AS " + NodeSql.quote("ci_low"));
                select.add(doubleLiteral(model.defined() ? model.ciHigh()[term] : null)
                        + " AS " + NodeSql.quote("ci_high"));
                rows.add("SELECT " + select);
            }
        }
        return literalPlan(context, rows, output);
    }

    private static Double vifValue(Model model, int term) {
        if (!model.defined() || model.vif() == null) {
            return null;
        }
        int offset = model.intercept() ? 1 : 0;
        int index = term - offset;
        return index < 0 || index >= model.vif().length ? null : model.vif()[index];
    }

    private static LazyPlan diagnostics(NodeContext context, List<ColumnSpec> groups,
                                        LinkedHashMap<List<Object>, Model> models) {
        List<ColumnSpec> output = new ArrayList<>(groups);
        output.addAll(DIAGNOSTICS);
        List<String> rows = new ArrayList<>();
        for (Map.Entry<List<Object>, Model> entry : models.entrySet()) {
            Model model = entry.getValue();
            StringJoiner select = new StringJoiner(", ");
            addGroupLiterals(select, groups, entry.getKey());
            select.add(model.n() + " AS " + NodeSql.quote("n"));
            select.add(doubleLiteral(model.dfResidual()) + " AS " + NodeSql.quote("df_residual"));
            select.add(doubleLiteral(model.r2()) + " AS " + NodeSql.quote("r2"));
            select.add(doubleLiteral(model.adjR2()) + " AS " + NodeSql.quote("adj_r2"));
            select.add(doubleLiteral(model.fStatistic()) + " AS " + NodeSql.quote("f_statistic"));
            select.add(doubleLiteral(model.fPValue()) + " AS " + NodeSql.quote("f_p_value"));
            select.add(doubleLiteral(model.rmse()) + " AS " + NodeSql.quote("rmse"));
            select.add(doubleLiteral(model.durbinWatson()) + " AS " + NodeSql.quote("durbin_watson"));
            select.add(doubleLiteral(model.aic()) + " AS " + NodeSql.quote("aic"));
            select.add(doubleLiteral(model.bic()) + " AS " + NodeSql.quote("bic"));
            rows.add("SELECT " + select);
        }
        return literalPlan(context, rows, output);
    }

    // ------------------------------------------------------------ predict

    private static String predictionName(LazyPlan input, JsonNode config) {
        String prediction = NodeSql.text(config, "output", "prediction");
        if (prediction.isBlank()) {
            throw new IllegalArgumentException("linear_regression: 'output' must not be blank");
        }
        Set<String> reserved = Set.of(RESIDUAL, LOWER, UPPER, SAMPLED_ROWS, TOTAL_ROWS);
        for (ColumnSpec column : input.columns()) {
            if (column.name().equals(prediction) || reserved.contains(column.name())) {
                throw new IllegalArgumentException("linear_regression: output column '" + column.name()
                        + "' already exists in the input");
            }
        }
        if (reserved.contains(prediction)) {
            throw new IllegalArgumentException("linear_regression: 'output' must not be one of "
                    + String.join(", ", reserved));
        }
        return prediction;
    }

    private static LazyPlan predict(NodeContext context, LazyPlan input, String prediction,
                                    List<ColumnSpec> predictors, ColumnSpec response,
                                    List<ColumnSpec> groups,
                                    LinkedHashMap<List<Object>, Model> models, boolean intercept,
                                    long sampledRows, long totalRows) {
        List<ColumnSpec> output = new ArrayList<>(input.columns());
        output.add(ColumnSpec.of(prediction, "Float64"));
        output.add(ColumnSpec.of(RESIDUAL, "Float64"));
        output.add(ColumnSpec.of(LOWER, "Float64"));
        output.add(ColumnSpec.of(UPPER, "Float64"));
        output.add(ColumnSpec.of(SAMPLED_ROWS, "Int64"));
        output.add(ColumnSpec.of(TOTAL_ROWS, "Int64"));

        String[] xExpressions = new String[predictors.size()];
        for (int j = 0; j < predictors.size(); j++) {
            xExpressions[j] = "CAST(" + NodeSql.quote(predictors.get(j).name()) + " AS DOUBLE)";
        }
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : input.columns()) {
            projection.add(NodeSql.quote(column.name()));
        }
        String sql;
        if (groups.isEmpty()) {
            Model model = models.values().iterator().next();
            if (model.defined()) {
                String[] coefficientSql = new String[model.parameters()];
                for (int j = 0; j < model.parameters(); j++) {
                    coefficientSql[j] = Double.toString(model.beta()[j]);
                }
                String[][] covarianceSql = new String[model.parameters()][model.parameters()];
                for (int j = 0; j < model.parameters(); j++) {
                    for (int k = 0; k < model.parameters(); k++) {
                        covarianceSql[j][k] = Double.toString(model.covariance()[j][k]);
                    }
                }
                addPredictionColumns(projection, prediction, response.name(), xExpressions, intercept,
                        coefficientSql, covarianceSql, Double.toString(model.mse()),
                        Double.toString(Distributions.tQuantile(0.975, model.dfResidual())));
            } else {
                addNullPredictions(projection, prediction);
            }
            addSampleCounts(projection, sampledRows, totalRows);
            sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input";
        } else {
            sql = groupedPredictSql(input, groups, models, projection, prediction, response.name(),
                    xExpressions, intercept, sampledRows, totalRows);
        }
        return new LazyPlan(context.db(), sql, output);
    }

    private static String groupedPredictSql(LazyPlan input, List<ColumnSpec> groups,
                                            LinkedHashMap<List<Object>, Model> models,
                                            StringJoiner projection, String prediction,
                                            String response, String[] xExpressions,
                                            boolean intercept, long sampledRows, long totalRows) {
        StringJoiner coefficientColumns = new StringJoiner(", ");
        for (int g = 0; g < groups.size(); g++) {
            coefficientColumns.add("CAST(NULL AS " + groups.get(g).duckDbType() + ") AS "
                    + NodeSql.quote("dap_g" + g));
        }
        int maxParameters = 0;
        for (Model model : models.values()) {
            maxParameters = Math.max(maxParameters, model.parameters());
        }
        for (int j = 0; j < maxParameters; j++) {
            coefficientColumns.add("CAST(NULL AS DOUBLE) AS " + NodeSql.quote("dap_b" + j));
        }
        coefficientColumns.add("CAST(NULL AS DOUBLE) AS " + NodeSql.quote("dap_mse"));
        coefficientColumns.add("CAST(NULL AS DOUBLE) AS " + NodeSql.quote("dap_tcrit"));
        for (int j = 0; j < maxParameters; j++) {
            for (int k = 0; k < maxParameters; k++) {
                coefficientColumns.add("CAST(NULL AS DOUBLE) AS " + NodeSql.quote("dap_v" + j + "_" + k));
            }
        }
        List<String> rows = new ArrayList<>();
        for (Map.Entry<List<Object>, Model> entry : models.entrySet()) {
            Model model = entry.getValue();
            StringJoiner select = new StringJoiner(", ");
            for (int g = 0; g < groups.size(); g++) {
                select.add(literal(groups.get(g), entry.getKey().get(g)) + " AS "
                        + NodeSql.quote("dap_g" + g));
            }
            for (int j = 0; j < maxParameters; j++) {
                Double value = model.defined() && j < model.parameters() ? model.beta()[j] : null;
                select.add(doubleLiteral(value) + " AS " + NodeSql.quote("dap_b" + j));
            }
            select.add(doubleLiteral(model.defined() ? model.mse() : null)
                    + " AS " + NodeSql.quote("dap_mse"));
            select.add(doubleLiteral(model.defined()
                    ? Distributions.tQuantile(0.975, model.dfResidual()) : null)
                    + " AS " + NodeSql.quote("dap_tcrit"));
            for (int j = 0; j < maxParameters; j++) {
                for (int k = 0; k < maxParameters; k++) {
                    Double value = model.defined() && j < model.parameters() && k < model.parameters()
                            ? model.covariance()[j][k] : null;
                    select.add(doubleLiteral(value) + " AS " + NodeSql.quote("dap_v" + j + "_" + k));
                }
            }
            rows.add("SELECT " + select);
        }
        String[] coefficientSql = new String[maxParameters];
        String[][] covarianceSql = new String[maxParameters][maxParameters];
        for (int j = 0; j < maxParameters; j++) {
            coefficientSql[j] = "dap_coefs." + NodeSql.quote("dap_b" + j);
            for (int k = 0; k < maxParameters; k++) {
                covarianceSql[j][k] = "dap_coefs." + NodeSql.quote("dap_v" + j + "_" + k);
            }
        }
        addPredictionColumns(projection, prediction, response, xExpressions, intercept,
                coefficientSql, covarianceSql, "dap_coefs." + NodeSql.quote("dap_mse"),
                "dap_coefs." + NodeSql.quote("dap_tcrit"));
        addSampleCounts(projection, sampledRows, totalRows);
        StringJoiner join = new StringJoiner(" AND ");
        for (int g = 0; g < groups.size(); g++) {
            join.add("dap_input." + NodeSql.quote(groups.get(g).name()) + " IS NOT DISTINCT FROM "
                    + "dap_coefs." + NodeSql.quote("dap_g" + g));
        }
        return "WITH dap_coefs AS (" + String.join(" UNION ALL ", rows) + ") "
                + "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input"
                + " LEFT JOIN dap_coefs ON " + join;
    }

    private static void addPredictionColumns(StringJoiner projection, String prediction,
                                             String response, String[] xExpressions,
                                             boolean intercept, String[] coefficientSql,
                                             String[][] covarianceSql, String mseSql,
                                             String criticalSql) {
        String fitted = prediction(coefficientSql, xExpressions, intercept);
        String variance = predictionVariance(covarianceSql, xExpressions, intercept, mseSql);
        projection.add(fitted + " AS " + NodeSql.quote(prediction));
        projection.add("(CAST(dap_input." + NodeSql.quote(response) + " AS DOUBLE) - "
                + fitted + ") AS " + NodeSql.quote(RESIDUAL));
        projection.add("(" + fitted + " - " + criticalSql + " * sqrt(" + variance + ")) AS "
                + NodeSql.quote(LOWER));
        projection.add("(" + fitted + " + " + criticalSql + " * sqrt(" + variance + ")) AS "
                + NodeSql.quote(UPPER));
    }

    private static void addSampleCounts(StringJoiner projection, long sampledRows, long totalRows) {
        projection.add(sampledRows + " AS " + NodeSql.quote(SAMPLED_ROWS));
        projection.add(totalRows + " AS " + NodeSql.quote(TOTAL_ROWS));
    }

    private static void addNullPredictions(StringJoiner projection, String prediction) {
        for (String name : List.of(prediction, RESIDUAL, LOWER, UPPER)) {
            projection.add("CAST(NULL AS DOUBLE) AS " + NodeSql.quote(name));
        }
    }

    private static String prediction(String[] coefficientSql, String[] xExpressions, boolean intercept) {
        StringJoiner sum = new StringJoiner(" + ");
        int offset = intercept ? 1 : 0;
        if (intercept) {
            sum.add(coefficientSql[0]);
        }
        for (int j = 0; j < xExpressions.length; j++) {
            sum.add("(" + coefficientSql[offset + j] + " * " + xExpressions[j] + ")");
        }
        return "(" + sum + ")";
    }

    private static String predictionVariance(String[][] covarianceSql, String[] xExpressions,
                                             boolean intercept, String mseSql) {
        StringJoiner sum = new StringJoiner(" + ");
        sum.add(mseSql);
        int offset = intercept ? 1 : 0;
        String[] design = new String[xExpressions.length + offset];
        if (intercept) {
            design[0] = "1.0";
        }
        for (int j = 0; j < xExpressions.length; j++) {
            design[offset + j] = "(" + xExpressions[j] + ")";
        }
        for (int j = 0; j < design.length; j++) {
            for (int k = 0; k < design.length; k++) {
                sum.add("(" + covarianceSql[j][k] + " * " + design[j] + " * " + design[k] + ")");
            }
        }
        return "(" + sum + ")";
    }

    private static LazyPlan emptyPlan(NodeContext context, LazyPlan input,
                                      List<ColumnSpec> predictors, List<ColumnSpec> groups,
                                      String mode, JsonNode config) {
        List<ColumnSpec> output = new ArrayList<>();
        if ("predict".equals(mode)) {
            String prediction = NodeSql.text(config, "output", "prediction");
            output.addAll(input.columns());
            output.add(ColumnSpec.of(prediction, "Float64"));
            output.add(ColumnSpec.of(RESIDUAL, "Float64"));
            output.add(ColumnSpec.of(LOWER, "Float64"));
            output.add(ColumnSpec.of(UPPER, "Float64"));
            output.add(ColumnSpec.of(SAMPLED_ROWS, "Int64"));
            output.add(ColumnSpec.of(TOTAL_ROWS, "Int64"));
        } else {
            output.addAll(groups);
            output.addAll("diagnostics".equals(mode) ? DIAGNOSTICS : COEFFICIENTS);
        }
        return literalPlan(context, List.of(), output);
    }

    // -------------------------------------------------------------- helpers

    private static void addGroupLiterals(StringJoiner select, List<ColumnSpec> groups,
                                         List<Object> groupValues) {
        for (int i = 0; i < groups.size(); i++) {
            select.add(literal(groups.get(i), groupValues.get(i)) + " AS "
                    + NodeSql.quote(groups.get(i).name()));
        }
    }

    private static String literal(ColumnSpec column, Object value) {
        if (value == null) {
            return "CAST(NULL AS " + column.duckDbType() + ")";
        }
        String type = column.arrowType();
        if ("Int64".equals(type)) {
            return "CAST(" + ((Number) value).longValue() + " AS BIGINT)";
        }
        if ("Float64".equals(type)) {
            return "CAST(" + NodeSql.literal(Double.toString(((Number) value).doubleValue()))
                    + " AS DOUBLE)";
        }
        if ("Boolean".equals(type)) {
            return Boolean.TRUE.equals(value) ? "TRUE" : "FALSE";
        }
        return "CAST(" + NodeSql.literal(value.toString()) + " AS " + column.duckDbType() + ")";
    }

    private static String doubleLiteral(Double value) {
        return value == null || !Double.isFinite(value)
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
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

    private static double clamp(double probability) {
        return Math.max(0.0, Math.min(1.0, probability));
    }

    // ----------------------------------------------------------- validation

    private static List<ColumnSpec> predictors(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("x");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("linear_regression: 'x' must be a non-empty array of column names");
        }
        List<ColumnSpec> predictors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("linear_regression: 'x' must contain non-empty strings");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("linear_regression: duplicate 'x' column '" + name + "'");
            }
            ColumnSpec column;
            try {
                column = NodeSql.column(input.columns(), name);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("linear_regression: unknown 'x' column '" + name + "'");
            }
            if (!column.numeric()) {
                throw new IllegalArgumentException("linear_regression: 'x' column '" + name
                        + "' must be numeric, got " + column.arrowType());
            }
            predictors.add(column);
        }
        return predictors;
    }

    private static ColumnSpec numericColumn(LazyPlan input, JsonNode config, String field) {
        String name = NodeSql.requiredText(config, field);
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("linear_regression: unknown '" + field + "' column '"
                    + name + "'");
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException("linear_regression: '" + field + "' column '" + name
                    + "' must be numeric, got " + column.arrowType());
        }
        return column;
    }

    private static List<ColumnSpec> groups(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("group_by");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("linear_regression: 'group_by' must be an array of column names");
        }
        List<ColumnSpec> groups = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("linear_regression: 'group_by' must contain non-empty strings");
            }
            try {
                groups.add(NodeSql.column(input.columns(), item.textValue()));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("linear_regression: unknown 'group_by' column '"
                        + item.textValue() + "'");
            }
        }
        return groups;
    }

    private static double alpha(JsonNode config) {
        JsonNode value = config.get("alpha");
        if (value == null || value.isNull()) {
            return 0.05;
        }
        if (!value.isNumber() || !(value.doubleValue() > 0.0) || !(value.doubleValue() < 1.0)) {
            throw new IllegalArgumentException("linear_regression: 'alpha' must be a number in (0, 1)");
        }
        return value.doubleValue();
    }
}
