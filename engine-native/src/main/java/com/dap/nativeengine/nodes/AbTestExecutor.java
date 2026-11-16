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
import java.util.TreeMap;

/**
 * dap.ab_test: two-proportion z-test plus the power and the required sample size.
 *
 * <p>The counts are aggregated in SQL (COUNT/SUM per group), so the node never reads the
 * rows into the JVM. The two levels of {@code group} are ordered lexicographically: the
 * first is the control, the second the treatment (deterministic and documented). The
 * output is one row: observed rates, relative lift, the pooled two-proportion z and its
 * two-sided p-value, the achieved power for the observed effect and sample sizes, the
 * required n per group for the design effect (the {@code mde} relative lift, or the
 * observed lift when {@code mde} is omitted; the control rate is {@code baseline} or the
 * observed control rate), the MDE used and alpha.</p>
 *
 * <p>Formulas (Fleiss): required n per group
 * {@code (z(1-a/2) sqrt(2 pbar (1-pbar)) + z(power) sqrt(p1(1-p1) + p2(1-p2)))^2 / (p2-p1)^2},
 * achieved power {@code Phi((|p2-p1| sqrt(n_eff) - z(1-a/2) sqrt(2 pbar (1-pbar)))
 * / sqrt(p1(1-p1) + p2(1-p2)))} with {@code n_eff = 2 n1 n2 / (n1 + n2)}.</p>
 */
public final class AbTestExecutor implements NodeExecutor {
    private static final String NODE = "ab_test";
    private static final double DEFAULT_ALPHA = 0.05;
    private static final double DEFAULT_POWER = 0.8;

    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("control_rate", "Float64"),
            ColumnSpec.of("treatment_rate", "Float64"),
            ColumnSpec.of("lift", "Float64"),
            ColumnSpec.of("z_statistic", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("power", "Float64"),
            ColumnSpec.of("required_n_per_group", "Int64"),
            ColumnSpec.of("mde", "Float64"),
            ColumnSpec.of("alpha", "Float64"));

    @Override
    public String nodeId() {
        return "dap.ab_test";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        ColumnSpec group = groupColumn(config, input);
        ColumnSpec value = valueColumn(config, input);
        double alpha = alpha(config);
        double power = power(config);
        Double mde = optionalPositive(config, "mde");
        Double baseline = optionalProbability(config, "baseline");

        TreeMap<String, long[]> counts = counts(context, input, group, value);
        if (counts.isEmpty()) {
            return emptyPlan(context);
        }
        if (counts.size() != 2) {
            throw new IllegalArgumentException(NODE + ": 'group' column '" + group.name()
                    + "' needs exactly two groups, got " + counts.size());
        }
        List<String> levels = new ArrayList<>(counts.keySet());
        long[] control = counts.get(levels.get(0));
        long[] treatment = counts.get(levels.get(1));
        long n1 = control[0];
        long n2 = treatment[0];
        double p1 = (double) control[1] / n1;
        double p2 = (double) treatment[1] / n2;

        double pooled = (double) (control[1] + treatment[1]) / (n1 + n2);
        double standardError = Math.sqrt(pooled * (1.0 - pooled) * (1.0 / n1 + 1.0 / n2));
        Double z = standardError > 0.0 ? (p2 - p1) / standardError : null;
        Double pValue = z == null ? null : clamp(2.0 * (1.0 - Distributions.normalCdf(Math.abs(z))));
        Double lift = p1 > 0.0 ? p2 / p1 - 1.0 : null;

        double designRate = baseline != null ? baseline : p1;
        Double designMde = mde != null ? mde : (lift != null && lift > 0.0 ? lift : null);
        Long required = requiredPerGroup(designRate, designMde, alpha, power);
        Double achieved = achievedPower(p1, p2, n1, n2, alpha);

        StringJoiner select = new StringJoiner(", ");
        select.add(doubleLiteral(p1) + " AS " + NodeSql.quote("control_rate"));
        select.add(doubleLiteral(p2) + " AS " + NodeSql.quote("treatment_rate"));
        select.add(doubleLiteral(lift) + " AS " + NodeSql.quote("lift"));
        select.add(doubleLiteral(z) + " AS " + NodeSql.quote("z_statistic"));
        select.add(doubleLiteral(pValue) + " AS " + NodeSql.quote("p_value"));
        select.add(doubleLiteral(achieved) + " AS " + NodeSql.quote("power"));
        select.add((required == null ? "CAST(NULL AS BIGINT)" : Long.toString(required))
                + " AS " + NodeSql.quote("required_n_per_group"));
        select.add(doubleLiteral(designMde) + " AS " + NodeSql.quote("mde"));
        select.add(doubleLiteral(alpha) + " AS " + NodeSql.quote("alpha"));
        return new LazyPlan(context.db(), "SELECT " + select, COLUMNS);
    }

    // ------------------------------------------------------------------ data

    /** level -> {n, conversions}; a non-0/1 value anywhere is an error. */
    private static TreeMap<String, long[]> counts(NodeContext context, LazyPlan input,
                                                  ColumnSpec group, ColumnSpec value)
            throws SQLException {
        String valueExpression = "Boolean".equals(value.arrowType())
                ? "CAST(" + NodeSql.quote(value.name()) + " AS INTEGER)"
                : "CAST(" + NodeSql.quote(value.name()) + " AS DOUBLE)";
        String sql = "SELECT CAST(" + NodeSql.quote(group.name()) + " AS VARCHAR) AS dap_group"
                + ", COUNT(*) AS dap_n"
                + ", SUM(CASE WHEN " + valueExpression + " = 1 THEN 1 ELSE 0 END) AS dap_conversions"
                + ", SUM(CASE WHEN " + valueExpression + " <> 0 AND " + valueExpression
                + " <> 1 THEN 1 ELSE 0 END) AS dap_invalid"
                + " FROM " + input.asTable() + " AS dap_input WHERE "
                + NodeSql.quote(value.name()) + " IS NOT NULL AND "
                + NodeSql.quote(group.name()) + " IS NOT NULL GROUP BY 1 ORDER BY 1";
        TreeMap<String, long[]> counts = new TreeMap<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                long invalid = result.getLong(4);
                if (invalid > 0) {
                    throw new IllegalArgumentException(NODE + ": 'value' column '" + value.name()
                            + "' must be 0/1 or boolean, got " + invalid + " other value(s)");
                }
                counts.put(result.getString(1),
                        new long[]{result.getLong(2), result.getLong(3)});
            }
        }
        return counts;
    }

    // ---------------------------------------------------------------- design

    private static Long requiredPerGroup(double controlRate, Double mde, double alpha, double power) {
        if (mde == null || !(controlRate > 0.0) || !(controlRate < 1.0)) {
            return null;
        }
        double treatmentRate = controlRate * (1.0 + mde);
        if (!(treatmentRate > 0.0) || !(treatmentRate < 1.0)) {
            return null;
        }
        double pooled = (controlRate + treatmentRate) / 2.0;
        double zAlpha = Distributions.normalQuantile(1.0 - alpha / 2.0);
        double zPower = Distributions.normalQuantile(power);
        double numerator = zAlpha * Math.sqrt(2.0 * pooled * (1.0 - pooled))
                + zPower * Math.sqrt(controlRate * (1.0 - controlRate)
                + treatmentRate * (1.0 - treatmentRate));
        double difference = treatmentRate - controlRate;
        double n = numerator * numerator / (difference * difference);
        return (long) Math.ceil(n);
    }

    private static Double achievedPower(double controlRate, double treatmentRate, long n1, long n2,
                                        double alpha) {
        double effective = 2.0 * n1 * n2 / (double) (n1 + n2);
        double pooled = (controlRate + treatmentRate) / 2.0;
        double variance = controlRate * (1.0 - controlRate) + treatmentRate * (1.0 - treatmentRate);
        if (!(variance > 0.0)) {
            return null;
        }
        double zAlpha = Distributions.normalQuantile(1.0 - alpha / 2.0);
        double zPower = (Math.abs(treatmentRate - controlRate) * Math.sqrt(effective)
                - zAlpha * Math.sqrt(2.0 * pooled * (1.0 - pooled))) / Math.sqrt(variance);
        return clamp(Distributions.normalCdf(zPower));
    }

    // -------------------------------------------------------------- config

    private static ColumnSpec groupColumn(JsonNode config, LazyPlan input) {
        String name = required(config, "group");
        try {
            return NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown 'group' column '" + name + "'");
        }
    }

    private static ColumnSpec valueColumn(JsonNode config, LazyPlan input) {
        String name = required(config, "value");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown 'value' column '" + name + "'");
        }
        if (!column.numeric() && !"Boolean".equals(column.arrowType())) {
            throw new IllegalArgumentException(NODE + ": 'value' column '" + name
                    + "' must be boolean or numeric 0/1, got " + column.arrowType());
        }
        return column;
    }

    private static double alpha(JsonNode config) {
        JsonNode node = config.get("alpha");
        if (node == null || node.isNull()) {
            return DEFAULT_ALPHA;
        }
        if (!node.isNumber() || !(node.doubleValue() > 0.0) || !(node.doubleValue() < 1.0)) {
            throw new IllegalArgumentException(NODE + ": 'alpha' must be a number in (0, 1)");
        }
        return node.doubleValue();
    }

    private static double power(JsonNode config) {
        JsonNode node = config.get("power");
        if (node == null || node.isNull()) {
            return DEFAULT_POWER;
        }
        if (!node.isNumber() || !(node.doubleValue() > 0.0) || !(node.doubleValue() < 1.0)) {
            throw new IllegalArgumentException(NODE + ": 'power' must be a number in (0, 1)");
        }
        return node.doubleValue();
    }

    private static Double optionalPositive(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber() || !(node.doubleValue() > 0.0) || Double.isInfinite(node.doubleValue())) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be a positive number");
        }
        return node.doubleValue();
    }

    private static Double optionalProbability(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber() || !(node.doubleValue() > 0.0) || !(node.doubleValue() < 1.0)) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be a number in (0, 1)");
        }
        return node.doubleValue();
    }

    private static String required(JsonNode config, String field) {
        try {
            return NodeSql.requiredText(config, field);
        } catch (IllegalArgumentException missing) {
            throw new IllegalArgumentException(NODE + ": config field '" + field + "' is required");
        }
    }

    private static LazyPlan emptyPlan(NodeContext context) {
        StringJoiner select = new StringJoiner(", ");
        for (ColumnSpec column : COLUMNS) {
            select.add("CAST(NULL AS " + column.duckDbType() + ") AS " + NodeSql.quote(column.name()));
        }
        return new LazyPlan(context.db(), "SELECT " + select + " WHERE FALSE", COLUMNS);
    }

    private static double clamp(double probability) {
        return Math.max(0.0, Math.min(1.0, probability));
    }

    private static String doubleLiteral(Double value) {
        return value == null || !Double.isFinite(value)
                ? "CAST(NULL AS DOUBLE)"
                : "CAST(" + NodeSql.literal(Double.toString(value)) + " AS DOUBLE)";
    }
}
