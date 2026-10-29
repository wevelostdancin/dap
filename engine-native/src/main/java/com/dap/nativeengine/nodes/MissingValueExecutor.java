package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** dap.missing_value: fill NULLs via constant, column mean or mode, rule by rule. */
public final class MissingValueExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.missing_value";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan plan = NodeSql.input(inputs);
        JsonNode rules = context.config().get("rules");
        if (rules == null || rules.isNull()) {
            return plan;
        }
        if (!rules.isArray()) {
            throw new IllegalArgumentException("missing_value: 'rules' must be an array of {column,method,value}");
        }
        for (JsonNode rule : rules) {
            if (!rule.isObject()) {
                throw new IllegalArgumentException("missing_value: every rule must be an object");
            }
            ColumnSpec column = NodeSql.column(plan.columns(), NodeSql.requiredText(rule, "column"));
            String method = NodeSql.requiredText(rule, "method").toLowerCase(Locale.ROOT);
            String expression = switch (method) {
                case "constant" -> "COALESCE(" + NodeSql.quote(column.name()) + ", "
                        + NodeSql.literal(column, rule.get("value")) + ")";
                case "mean" -> mean(plan, column);
                case "mode" -> mode(plan, column);
                default -> throw new IllegalArgumentException("missing_value: unknown method '" + method
                        + "' (expected constant, mean, mode)");
            };
            plan = replace(plan, column, expression);
        }
        return plan;
    }

    private static String mean(LazyPlan plan, ColumnSpec column) {
        if (!"Float64".equals(column.arrowType())) {
            throw new IllegalArgumentException("missing_value: method 'mean' is only supported on Float64 columns; column '"
                    + column.name() + "' is " + column.arrowType());
        }
        String quoted = NodeSql.quote(column.name());
        return "COALESCE(" + quoted + ", (SELECT AVG(" + quoted + ") FROM " + plan.asTable() + " AS dap_mean))";
    }

    private static String mode(LazyPlan plan, ColumnSpec column) {
        String quoted = NodeSql.quote(column.name());
        return "COALESCE(" + quoted + ", (SELECT " + quoted + " FROM " + plan.asTable() + " AS dap_mode"
                + " WHERE " + quoted + " IS NOT NULL GROUP BY " + quoted
                + " ORDER BY COUNT(*) DESC, " + quoted + " ASC LIMIT 1))";
    }

    /** One nested SELECT that rewrites only the target column, keeping schema order. */
    private static LazyPlan replace(LazyPlan plan, ColumnSpec target, String expression) {
        List<String> projection = new ArrayList<>(plan.columns().size());
        for (ColumnSpec column : plan.columns()) {
            String quoted = NodeSql.quote(column.name());
            projection.add(column.name().equals(target.name()) ? expression + " AS " + quoted : quoted);
        }
        String sql = "SELECT " + String.join(", ", projection) + " FROM " + plan.asTable() + " AS dap_input";
        return new LazyPlan(plan.session(), sql, plan.columns());
    }
}
