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
 * consistent (hc1). X'X, X'y, y'y and the residual moments come from DuckDB aggregates,
 * so only p-by-p matrices live in Java and the row count is unbounded; predict stays a
 * lazy plan over the input with the coefficients as literals (or as a literal
 * coefficient table joined on the group columns).</p>
 */
public final class LinearRegressionExecutor implements NodeExecutor {
    private static final List<String> MODES = List.of("coefficients", "predict", "diagnostics");
    private static final List<String> ROBUST = List.of("none", "hc1");
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
        int seed = config == null ? 42 : config.path("seed").asInt(42);
        if (seed < 0) {
            throw new IllegalArgumentException("linear_regression: 'seed' must be 0 or positive");
        }
        String prediction = "predict".equals(mode) ? predictionName(input, config) : null;
        String where = sampleWhere(predictors, response);
        String baseTable = sampledSource(input, sampleRows, seed, where);
        List<String> expressions = new ArrayList<>(predictors.size() + 1);
        for (ColumnSpec predictor : predictors) {
            expressions.add(MomentSupport.doubleColumn(predictor.name()));
        }
        expressions.add(MomentSupport.doubleColumn(response.name()));
        List<String> groupExpressions = new ArrayList<>(groups.size());
        for (ColumnSpec group : groups) {
            groupExpressions.add(NodeSql.quote(group.name()));
        }
        LinkedHashMap<List<Object>, MomentSupport.Moments> moments = MomentSupport.read(context, "",
                " FROM " + baseTable, expressions, false, groupExpressions);
        if (moments.isEmpty()) {
            return emptyPlan(context, input, predictors, groups, mode, config);
        }
        LinkedHashMap<List<Object>, Model> models = new LinkedHashMap<>();
        long sampled = 0;
        for (Map.Entry<List<Object>, MomentSupport.Moments> entry : moments.entrySet()) {
            Model model = fit(context, baseTable, groups, entry.getKey(), entry.getValue(),
                    predictors, response.name(), intercept, robust, alpha,
                    "diagnostics".equals(mode));
            models.put(entry.getKey(), model);
            sampled += model.n();
        }
        long total = sampleRows == 0 ? sampled : countRows(context, input, where);
        if ("predict".equals(mode)) {
            return predict(context, input, prediction, predictors, response, groups, models, intercept,
                    sampled, total);
        }
        if ("diagnostics".equals(mode)) {
            return diagnostics(context, groups, models);
        }
        return coefficients(context, groups, models);
    }

    // -------------------------------------------------------------- data

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
     * Table expression over the eligible rows. With {@code sample_rows > 0} DuckDB draws
     * a deterministic reservoir sample before any moment is computed; the same seed and
     * input give the same fit. With 0 every eligible row is used, at any row count.
     */
    private static String sampledSource(LazyPlan input, int sampleRows, int seed, String where) {
        if (sampleRows <= 0) {
            return input.asTable() + " AS dap_input WHERE " + where;
        }
        return "(SELECT * FROM " + input.asTable() + " AS dap_src WHERE " + where
                + " USING SAMPLE reservoir(" + sampleRows + " ROWS) REPEATABLE(" + seed
                + ")) AS dap_input WHERE TRUE";
    }

    private static long countRows(NodeContext context, LazyPlan input, String where) throws SQLException {
        String sql = "SELECT COUNT(*) AS " + NodeSql.quote("dap_total") + " FROM " + input.asTable()
                + " AS dap_input WHERE " + where;
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong("dap_total") : 0;
        }
    }

    private static String sampleWhere(List<ColumnSpec> predictors, ColumnSpec response) {
        StringJoiner where = new StringJoiner(" AND ");
        for (ColumnSpec predictor : predictors) {
            where.add(NodeSql.quote(predictor.name()) + " IS NOT NULL");
        }
        where.add(NodeSql.quote(response.name()) + " IS NOT NULL");
        return where.toString();
    }

    private static String groupWhere(List<ColumnSpec> groups, List<Object> values) {
        if (groups.isEmpty()) {
            return "TRUE";
        }
        StringJoiner where = new StringJoiner(" AND ");
        for (int i = 0; i < groups.size(); i++) {
            where.add("dap_input." + NodeSql.quote(groups.get(i).name()) + " IS NOT DISTINCT FROM "
                    + literal(groups.get(i), values.get(i)));
        }
        return where.toString();
    }

    // --------------------------------------------------------------- fit

    /**
     * Residuals are evaluated by DuckDB with the fitted coefficients as literal
     * DOUBLEs: the residual sum of squares is exact even for a perfect fit (where
     * y'y − β'X'y would cancel), and the HC1 meat needs no rows in the heap.
     */
    private record ResidualStats(double sse, double[][] meat) {
    }

    /** X'X and X'y from the SQL moments; the design's intercept is the constant column. */
    private static double cross(MomentSupport.Moments moments, int first, int second) {
        if (first < 0 && second < 0) {
            return moments.count();
        }
        if (first < 0) {
            return moments.sum(second);
        }
        if (second < 0) {
            return moments.sum(first);
        }
        return moments.cross(first, second);
    }

    private static Model fit(NodeContext context, String baseTable, List<ColumnSpec> groups,
                             List<Object> groupValues, MomentSupport.Moments moments,
                             List<ColumnSpec> predictorColumns, String responseName,
                             boolean intercept, String robust, double alpha, boolean diagnostics)
            throws SQLException {
        int predictors = predictorColumns.size();
        int parameters = predictors + (intercept ? 1 : 0);
        int offset = intercept ? 1 : 0;
        long n = moments.count();
        double dfResidual = n - parameters;
        String[] terms = terms(predictorColumns, intercept);
        if (n <= parameters) {
            String where = groupValues.isEmpty() ? "" : " in group " + groupValues + "";
            throw new IllegalArgumentException("linear_regression: need more rows than parameters, got "
                    + n + " rows" + where + " and " + parameters + " parameters (including the intercept); "
                    + "add rows, drop a predictor or set intercept=false");
        }
        double[][] xtx = new double[parameters][parameters];
        double[] xty = new double[parameters];
        for (int j = 0; j < parameters; j++) {
            xty[j] = cross(moments, j - offset, predictors);
            for (int k = 0; k < parameters; k++) {
                xtx[j][k] = cross(moments, j - offset, k - offset);
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
        ResidualStats residualStats = residualStats(context, baseTable, groups, groupValues,
                predictorColumns, responseName, beta, intercept, "hc1".equals(robust));
        double sse = residualStats.sse();
        Double durbinWatson = diagnostics
                ? durbinWatson(context, baseTable, groups, groupValues, predictorColumns,
                        responseName, beta, intercept, sse)
                : null;
        double mse = sse / dfResidual;
        double sst = cross(moments, predictors, predictors)
                - (intercept ? moments.sum(predictors) * moments.sum(predictors) / n : 0.0);
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
        double[][] covariance = covariance(inverse, residualStats.meat(), mse, n, parameters, robust);
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
        Double[] vif = vif(moments, predictors, intercept);
        Double aic = sse > 0.0 ? n * Math.log(sse / n) + 2.0 * parameters : null;
        Double bic = sse > 0.0 ? n * Math.log(sse / n) + Math.log(n) * parameters : null;
        return new Model(n, parameters, predictors, intercept, true, terms, beta, stdErrors,
                tStatistics, pValues, ciLow, ciHigh, vif, covariance, mse, rmse, dfResidual, r2,
                adjR2, fStatistic, fPValue, null, durbinWatson, aic, bic);
    }

    private static ResidualStats residualStats(NodeContext context, String baseTable,
            List<ColumnSpec> groups, List<Object> groupValues, List<ColumnSpec> predictorColumns,
            String responseName, double[] beta, boolean intercept, boolean hc1)
            throws SQLException {
        StringJoiner base = new StringJoiner(", ");
        for (int j = 0; j < predictorColumns.size(); j++) {
            base.add(MomentSupport.doubleColumn(predictorColumns.get(j).name()) + " AS "
                    + NodeSql.quote("dap_x" + j));
        }
        base.add(MomentSupport.doubleColumn(responseName) + " AS " + NodeSql.quote("dap_y"));
        StringJoiner fitted = new StringJoiner(" + ");
        if (intercept) {
            fitted.add(doubleSql(beta[0]));
        }
        for (int j = 0; j < predictorColumns.size(); j++) {
            fitted.add("(" + doubleSql(beta[(intercept ? 1 : 0) + j]) + " * "
                    + NodeSql.quote("dap_x" + j) + ")");
        }
        base.add("(dap_y - (" + fitted + ")) AS " + NodeSql.quote("dap_e"));
        String with = "WITH dap_base AS (SELECT " + base + " FROM " + baseTable
                + " AND (" + groupWhere(groups, groupValues) + ")) ";
        StringJoiner select = new StringJoiner(", ");
        select.add("CAST(SUM(dap_e * dap_e) AS DOUBLE) AS " + NodeSql.quote("dap_sse"));
        if (hc1) {
            for (int j = 0; j < predictorColumns.size() + (intercept ? 1 : 0); j++) {
                for (int k = 0; k < predictorColumns.size() + (intercept ? 1 : 0); k++) {
                    select.add("CAST(SUM(dap_e * dap_e * " + designSql(j, intercept) + " * "
                            + designSql(k, intercept) + ") AS DOUBLE) AS "
                            + NodeSql.quote("dap_m" + j + "_" + k));
                }
            }
        }
        String sql = with + "SELECT " + select + " FROM dap_base";
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                return new ResidualStats(0.0, null);
            }
            double sse = value(result, "dap_sse");
            double[][] meat = null;
            if (hc1) {
                int size = predictorColumns.size() + (intercept ? 1 : 0);
                meat = new double[size][size];
                for (int j = 0; j < size; j++) {
                    for (int k = 0; k < size; k++) {
                        meat[j][k] = value(result, "dap_m" + j + "_" + k);
                    }
                }
            }
            return new ResidualStats(sse, meat);
        }
    }

    /**
     * Durbin-Watson is order-dependent, so the residuals are streamed once through a
     * cursor holding only the previous residual; the fit itself never buffers rows.
     */
    private static Double durbinWatson(NodeContext context, String baseTable,
            List<ColumnSpec> groups, List<Object> groupValues, List<ColumnSpec> predictorColumns,
            String responseName, double[] beta, boolean intercept, double sse) throws SQLException {
        if (!(sse > 0.0)) {
            return null;
        }
        StringJoiner fitted = new StringJoiner(" + ");
        if (intercept) {
            fitted.add(doubleSql(beta[0]));
        }
        for (int j = 0; j < predictorColumns.size(); j++) {
            fitted.add("(" + doubleSql(beta[(intercept ? 1 : 0) + j]) + " * "
                    + MomentSupport.doubleColumn(predictorColumns.get(j).name()) + ")");
        }
        String expression = "(" + MomentSupport.doubleColumn(responseName) + " - (" + fitted + "))";
        String sql = "SELECT " + expression + " FROM " + baseTable
                + " AND (" + groupWhere(groups, groupValues) + ")";
        double sum = 0.0;
        double previous = 0.0;
        int seen = 0;
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                double current = result.getDouble(1);
                if (seen > 0) {
                    double difference = current - previous;
                    sum += difference * difference;
                }
                previous = current;
                seen++;
            }
        }
        return seen > 1 ? sum / sse : null;
    }

    /** Design value j inside an SQL expression: the constant column or an aliased predictor. */
    private static String designSql(int index, boolean intercept) {
        return index == 0 && intercept ? "1.0" : NodeSql.quote("dap_x" + (index - (intercept ? 1 : 0)));
    }

    private static String doubleSql(double value) {
        return "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
    }

    private static double value(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value == null ? 0.0 : ((Number) value).doubleValue();
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
    private static double[][] covariance(double[][] inverse, double[][] meat, double mse,
                                         long n, int parameters, String robust) {
        if (!"hc1".equals(robust)) {
            double[][] result = new double[parameters][parameters];
            for (int j = 0; j < parameters; j++) {
                for (int k = 0; k < parameters; k++) {
                    result[j][k] = mse * inverse[j][k];
                }
            }
            return result;
        }
        if (meat == null) {
            return inverse;
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

    /** VIF from the inverse of the predictor correlation matrix: 1 / diag(R^-1). */
    private static Double[] vif(MomentSupport.Moments moments, int predictors, boolean intercept) {
        Double[] result = new Double[predictors];
        if (!intercept) {
            return result;
        }
        if (predictors == 1) {
            result[0] = 1.0;
            return result;
        }
        double[][] correlation = new double[predictors][predictors];
        for (int j = 0; j < predictors; j++) {
            correlation[j][j] = 1.0;
            for (int k = j + 1; k < predictors; k++) {
                double value = moments.centeredCorrelation(j, k);
                correlation[j][k] = correlation[k][j] = Double.isNaN(value) ? 0.0 : value;
            }
        }
        double[][] inverse;
        try {
            inverse = Matrix.invert(correlation);
        } catch (IllegalArgumentException singular) {
            return result;
        }
        for (int j = 0; j < predictors; j++) {
            double r2 = inverse[j][j] == 0.0 ? Double.NaN : 1.0 - 1.0 / inverse[j][j];
            result[j] = !Double.isFinite(r2) || r2 >= 1.0 - 1e-12 ? null : 1.0 / (1.0 - r2);
        }
        return result;
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
                    coefficientSql[j] = doubleSql(model.beta()[j]);
                }
                String[][] covarianceSql = new String[model.parameters()][model.parameters()];
                for (int j = 0; j < model.parameters(); j++) {
                    for (int k = 0; k < model.parameters(); k++) {
                        covarianceSql[j][k] = doubleSql(model.covariance()[j][k]);
                    }
                }
                addPredictionColumns(projection, prediction, response.name(), xExpressions, intercept,
                        coefficientSql, covarianceSql, doubleSql(model.mse()),
                        doubleSql(Distributions.tQuantile(0.975, model.dfResidual())));
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
