package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.Distributions;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * dap.linear_regression: simple (one-predictor) least squares with optional grouping.
 *
 * <p>coefficients mode: the fit comes from DuckDB regr_slope/regr_intercept/regr_r2/
 * regr_count (or the intercept-free sums), standard errors and p-values from
 * {@link Distributions}; the output is one row per term. predict mode stays lazy:
 * window aggregates fit per partition and the plan adds prediction and residual.</p>
 */
public final class LinearRegressionExecutor implements NodeExecutor {
    private static final List<String> MODES = List.of("coefficients", "predict");
    private static final String RESIDUAL = "residual";

    @Override
    public String nodeId() {
        return "dap.linear_regression";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String x = predictor(input, config);
        String y = numericColumn(input, config, "y");
        List<ColumnSpec> groups = groups(input, config);
        String mode = NodeSql.text(config, "mode", "coefficients").toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("linear_regression: unknown mode '" + mode
                    + "' (expected coefficients, predict)");
        }
        boolean intercept = NodeSql.bool(config, "intercept", true);
        if ("predict".equals(mode)) {
            return predict(context, input, config, x, y, groups, intercept);
        }
        return coefficients(context, input, x, y, groups, intercept);
    }

    // ------------------------------------------------------------ coefficients

    private record Fit(long n, Double slope, Double intercept, Double r2, Double meanX,
                       Double sxx, Double sse, Double syy) {
    }

    private static LazyPlan coefficients(NodeContext context, LazyPlan input, String x, String y,
                                         List<ColumnSpec> groups, boolean intercept) throws SQLException {
        String sql = intercept ? fittedSql(input, x, y, groups) : slopeOnlySql(input, x, y, groups);
        List<ColumnSpec> output = new ArrayList<>(groups);
        output.add(ColumnSpec.of("term", "Utf8"));
        output.add(ColumnSpec.of("coefficient", "Float64"));
        output.add(ColumnSpec.of("std_error", "Float64"));
        output.add(ColumnSpec.of("t_statistic", "Float64"));
        output.add(ColumnSpec.of("p_value", "Float64"));
        output.add(ColumnSpec.of("r2", "Float64"));
        output.add(ColumnSpec.of("n", "Int64"));

        List<String> rows = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                List<Object> groupValues = new ArrayList<>(groups.size());
                for (int i = 0; i < groups.size(); i++) {
                    groupValues.add(result.getObject(i + 1));
                }
                Fit fit = fit(result, intercept);
                if (fit.n() < 2) {
                    throw new IllegalArgumentException("linear_regression: need at least 2 rows with "
                            + "non-null '" + x + "' and '" + y + "', got " + fit.n());
                }
                for (Term term : terms(fit, x, intercept)) {
                    rows.add(row(groupValues, groups, term, fit));
                }
            }
        }
        if (rows.isEmpty()) {
            // No rows in the input at all: keep the declared schema, return no rows.
            rows.add(emptyRow(groups));
        }
        return new LazyPlan(context.db(), String.join(" UNION ALL ", rows), output);
    }

    private static String fittedSql(LazyPlan input, String x, String y, List<ColumnSpec> groups) {
        String quotedX = NodeSql.quote(x);
        String quotedY = NodeSql.quote(y);
        String window = window(groups);
        String projection = groupProjection(groups, "");
        String groupKeys = groupProjection(groups, "");
        String over = groups.isEmpty() ? "OVER ()" : "OVER dap_window";
        String windowClause = groups.isEmpty() ? "" : " WINDOW dap_window AS (" + window + ")";
        return "WITH dap_rows AS (SELECT " + (projection.isEmpty() ? "" : projection + ", ")
                + "CAST(" + quotedX + " AS DOUBLE) AS dap_x, CAST(" + quotedY
                + " AS DOUBLE) AS dap_y FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + quotedX + " IS NOT NULL AND " + quotedY + " IS NOT NULL),"
                + " dap_fitted AS (SELECT " + (groupKeys.isEmpty() ? "" : groupKeys + ", ")
                + "dap_x, dap_y,"
                + " REGR_SLOPE(dap_y, dap_x) " + over + " AS dap_slope,"
                + " REGR_INTERCEPT(dap_y, dap_x) " + over + " AS dap_intercept,"
                + " REGR_R2(dap_y, dap_x) " + over + " AS dap_r2,"
                + " REGR_COUNT(dap_y, dap_x) " + over + " AS dap_n,"
                + " AVG(dap_x) " + over + " AS dap_mean_x"
                + " FROM dap_rows" + windowClause + ")"
                + " SELECT " + (groupKeys.isEmpty() ? "" : groupKeys + ", ")
                + "MIN(dap_slope) AS dap_slope, MIN(dap_intercept) AS dap_intercept,"
                + " MIN(dap_r2) AS dap_r2, MIN(dap_n) AS dap_n, MIN(dap_mean_x) AS dap_mean_x,"
                + " SUM((dap_x - dap_mean_x) * (dap_x - dap_mean_x)) AS dap_sxx,"
                + " SUM((dap_y - (dap_intercept + dap_slope * dap_x))"
                + " * (dap_y - (dap_intercept + dap_slope * dap_x))) AS dap_sse"
                + " FROM dap_fitted" + groupBy(groups) + orderBy(groups);
    }

    private static String slopeOnlySql(LazyPlan input, String x, String y, List<ColumnSpec> groups) {
        String quotedX = NodeSql.quote(x);
        String quotedY = NodeSql.quote(y);
        String window = window(groups);
        String projection = groupProjection(groups, "");
        String groupKeys = groupProjection(groups, "");
        String over = groups.isEmpty() ? "OVER ()" : "OVER dap_window";
        String windowClause = groups.isEmpty() ? "" : " WINDOW dap_window AS (" + window + ")";
        return "WITH dap_rows AS (SELECT " + (projection.isEmpty() ? "" : projection + ", ")
                + "CAST(" + quotedX + " AS DOUBLE) AS dap_x, CAST(" + quotedY
                + " AS DOUBLE) AS dap_y FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + quotedX + " IS NOT NULL AND " + quotedY + " IS NOT NULL),"
                + " dap_fitted AS (SELECT " + (groupKeys.isEmpty() ? "" : groupKeys + ", ")
                + "dap_x, dap_y,"
                + " SUM(dap_x * dap_y) " + over + " / NULLIF(SUM(dap_x * dap_x) " + over + ", 0)"
                + " AS dap_slope, COUNT(dap_x) " + over + " AS dap_n"
                + " FROM dap_rows" + windowClause + ")"
                + " SELECT " + (groupKeys.isEmpty() ? "" : groupKeys + ", ")
                + "MIN(dap_slope) AS dap_slope, MIN(dap_n) AS dap_n,"
                + " SUM(dap_x * dap_x) AS dap_sxx, SUM(dap_y * dap_y) AS dap_syy,"
                + " SUM((dap_y - dap_slope * dap_x) * (dap_y - dap_slope * dap_x)) AS dap_sse"
                + " FROM dap_fitted" + groupBy(groups) + orderBy(groups);
    }

    private static Fit fit(ResultSet result, boolean intercept) throws SQLException {
        long n = result.getObject("dap_n") == null ? 0 : result.getLong("dap_n");
        Double slope = value(result, "dap_slope");
        Double sxx = value(result, "dap_sxx");
        Double sse = value(result, "dap_sse");
        if (intercept) {
            return new Fit(n, slope, value(result, "dap_intercept"), value(result, "dap_r2"),
                    value(result, "dap_mean_x"), sxx, sse, null);
        }
        // Intercept-free model: R2 is the uncentered one, 1 - SSE / sum(y^2).
        Double syy = value(result, "dap_syy");
        Double r2 = sse == null || syy == null || syy == 0.0 ? null : 1.0 - Math.max(0.0, sse) / syy;
        return new Fit(n, slope, 0.0, r2, null, sxx, sse, syy);
    }

    private record Term(String name, Double coefficient, Double stdError, Double tStatistic,
                        Double pValue) {
    }

    private static List<Term> terms(Fit fit, String x, boolean intercept) {
        List<Term> terms = new ArrayList<>(2);
        if (intercept) {
            terms.add(term("intercept", fit.intercept(), fit.n(), fit.sse(), fit.sxx(),
                    fit.meanX(), true, true));
        }
        terms.add(term(x, fit.slope(), fit.n(), fit.sse(), fit.sxx(), fit.meanX(), intercept, false));
        return terms;
    }

    /**
     * One coefficient row: std_error from MSE and the design sums, p from the two-sided
     * t tail. A zero standard error (perfect fit or undefined slope) leaves t and p NULL.
     */
    private static Term term(String name, Double coefficient, long n, Double sse, Double sxx,
                             Double meanX, boolean modelIntercept, boolean interceptTerm) {
        if (coefficient == null) {
            return new Term(name, null, null, null, null);
        }
        int df = (int) (n - (modelIntercept ? 2 : 1));
        if (df <= 0 || sse == null || sxx == null || sxx == 0.0) {
            return new Term(name, coefficient, null, null, null);
        }
        double mse = Math.max(0.0, sse) / df;
        double stdError = interceptTerm
                ? Math.sqrt(mse * (1.0 / n + meanX * meanX / sxx))
                : Math.sqrt(mse / sxx);
        if (stdError == 0.0) {
            return new Term(name, coefficient, 0.0, null, null);
        }
        double t = coefficient / stdError;
        double p = Math.max(0.0, Math.min(1.0, 2.0 * (1.0 - Distributions.tCdf(Math.abs(t), df))));
        return new Term(name, coefficient, stdError, t, p);
    }

    private static String row(List<Object> groupValues, List<ColumnSpec> groups, Term term, Fit fit) {
        StringJoiner select = new StringJoiner(", ");
        for (int i = 0; i < groups.size(); i++) {
            ColumnSpec column = groups.get(i);
            select.add(literal(column, groupValues.get(i)) + " AS " + NodeSql.quote(column.name()));
        }
        select.add(NodeSql.literal(term.name()) + " AS " + NodeSql.quote("term"));
        select.add(doubleLiteral(term.coefficient()) + " AS " + NodeSql.quote("coefficient"));
        select.add(doubleLiteral(term.stdError()) + " AS " + NodeSql.quote("std_error"));
        select.add(doubleLiteral(term.tStatistic()) + " AS " + NodeSql.quote("t_statistic"));
        select.add(doubleLiteral(term.pValue()) + " AS " + NodeSql.quote("p_value"));
        select.add(doubleLiteral(fit.r2()) + " AS " + NodeSql.quote("r2"));
        select.add(fit.n() + " AS " + NodeSql.quote("n"));
        return "SELECT " + select;
    }

    private static String emptyRow(List<ColumnSpec> groups) {
        StringJoiner select = new StringJoiner(", ");
        for (ColumnSpec column : groups) {
            select.add("CAST(NULL AS " + column.duckDbType() + ") AS " + NodeSql.quote(column.name()));
        }
        select.add("CAST(NULL AS VARCHAR) AS " + NodeSql.quote("term"));
        for (String name : List.of("coefficient", "std_error", "t_statistic", "p_value", "r2")) {
            select.add("CAST(NULL AS DOUBLE) AS " + NodeSql.quote(name));
        }
        select.add("CAST(NULL AS BIGINT) AS " + NodeSql.quote("n"));
        return "SELECT " + select + " WHERE FALSE";
    }

    // ---------------------------------------------------------------- predict

    private static LazyPlan predict(NodeContext context, LazyPlan input, JsonNode config, String x,
                                    String y, List<ColumnSpec> groups, boolean intercept) {
        String prediction = NodeSql.text(config, "output", "prediction");
        if (prediction.isBlank()) {
            throw new IllegalArgumentException("linear_regression: 'output' must not be blank");
        }
        for (ColumnSpec column : input.columns()) {
            if (column.name().equals(prediction) || column.name().equals(RESIDUAL)) {
                throw new IllegalArgumentException("linear_regression: output column '" + column.name()
                        + "' already exists in the input");
            }
        }
        if (prediction.equals(RESIDUAL)) {
            throw new IllegalArgumentException("linear_regression: 'output' must not be 'residual'");
        }
        String quotedX = NodeSql.quote(x);
        String quotedY = NodeSql.quote(y);
        String xExpression = "CAST(" + quotedX + " AS DOUBLE)";
        String yExpression = "CAST(" + quotedY + " AS DOUBLE)";
        String over = groups.isEmpty() ? "OVER ()"
                : "OVER (PARTITION BY " + quotedGroupList(groups) + ")";
        String fitted = intercept
                ? "(REGR_INTERCEPT(" + yExpression + ", " + xExpression + ") " + over
                        + " + REGR_SLOPE(" + yExpression + ", " + xExpression + ") " + over
                        + " * " + xExpression + ")"
                : "(SUM(" + xExpression + " * " + yExpression + ") " + over + " / NULLIF(SUM("
                        + xExpression + " * " + xExpression + ") " + over + ", 0) * "
                        + xExpression + ")";
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : input.columns()) {
            projection.add(NodeSql.quote(column.name()));
        }
        projection.add(fitted + " AS " + NodeSql.quote(prediction));
        projection.add("(" + yExpression + " - " + fitted + ") AS " + NodeSql.quote(RESIDUAL));
        List<ColumnSpec> output = new ArrayList<>(input.columns());
        output.add(ColumnSpec.of(prediction, "Float64"));
        output.add(ColumnSpec.of(RESIDUAL, "Float64"));
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, output);
    }

    // ---------------------------------------------------------------- helpers

    private static String window(List<ColumnSpec> groups) {
        return "PARTITION BY " + quotedGroupList(groups);
    }

    private static String quotedGroupList(List<ColumnSpec> groups) {
        StringJoiner joiner = new StringJoiner(", ");
        for (ColumnSpec group : groups) {
            joiner.add(NodeSql.quote(group.name()));
        }
        return joiner.toString();
    }

    private static String groupProjection(List<ColumnSpec> groups, String suffix) {
        StringJoiner joiner = new StringJoiner(", ");
        for (ColumnSpec group : groups) {
            joiner.add(NodeSql.quote(group.name()) + suffix);
        }
        return joiner.toString();
    }

    private static String groupBy(List<ColumnSpec> groups) {
        return groups.isEmpty() ? "" : " GROUP BY " + groupProjection(groups, "");
    }

    private static String orderBy(List<ColumnSpec> groups) {
        return groups.isEmpty() ? "" : " ORDER BY " + groupProjection(groups, "");
    }

    /** Numeric column; NaN/Infinity (an undefined fit) is normalised to NULL. */
    private static Double value(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        if (value == null) {
            return null;
        }
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) ? number : null;
    }

    private static String doubleLiteral(Double value) {
        return value == null
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
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

    private static String predictor(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("x");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("linear_regression: 'x' must be a non-empty array of column names");
        }
        if (node.size() > 1) {
            throw new IllegalArgumentException("linear_regression: exactly one 'x' column is supported, got "
                    + node.size());
        }
        JsonNode item = node.get(0);
        if (!item.isTextual() || item.textValue().isBlank()) {
            throw new IllegalArgumentException("linear_regression: 'x' must contain non-empty strings");
        }
        String name = item.textValue();
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
        return column.name();
    }

    private static String numericColumn(LazyPlan input, JsonNode config, String field) {
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
        return column.name();
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
}
