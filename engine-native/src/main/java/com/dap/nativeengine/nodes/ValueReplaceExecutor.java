package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** dap.value_replace: replace exact scalar values per column, keeping the column type. */
public final class ValueReplaceExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.value_replace";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode rules = context.config().get("rules");
        if (rules == null || rules.isNull()) {
            throw new IllegalArgumentException("value_replace: config field 'rules' is required");
        }
        if (!rules.isArray()) {
            throw new IllegalArgumentException("value_replace: 'rules' must be an array of {column,from,to}");
        }
        if (rules.isEmpty()) {
            return input;
        }

        Map<String, List<Rule>> byColumn = new LinkedHashMap<>();
        for (JsonNode rule : rules) {
            if (!rule.isObject()) {
                throw new IllegalArgumentException(
                        "value_replace: every rule must be an object with 'column', 'from' and 'to'");
            }
            ColumnSpec column = NodeSql.column(input.columns(), NodeSql.requiredText(rule, "column"));
            Rule replacement = new Rule(scalar(rule, "from", column), scalar(rule, "to", column));
            byColumn.computeIfAbsent(column.name(), name -> new ArrayList<>()).add(replacement);
        }

        List<String> projection = new ArrayList<>(input.columns().size());
        for (ColumnSpec column : input.columns()) {
            String quoted = NodeSql.quote(column.name());
            List<Rule> columnRules = byColumn.get(column.name());
            projection.add(columnRules == null ? quoted : expression(column, columnRules) + " AS " + quoted);
        }
        String sql = "SELECT " + String.join(", ", projection) + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, input.columns());
    }

    /** One CASE per column: the first matching rule wins and the column type is preserved. */
    private static String expression(ColumnSpec column, List<Rule> rules) {
        String quoted = NodeSql.quote(column.name());
        StringBuilder sql = new StringBuilder("CASE");
        for (Rule rule : rules) {
            sql.append(" WHEN ").append(quoted).append(" IS NOT DISTINCT FROM ").append(literal(column, rule.from()))
                    .append(" THEN CAST(").append(literal(column, rule.to()))
                    .append(" AS ").append(column.duckDbType()).append(')');
        }
        return sql.append(" ELSE ").append(quoted).append(" END").toString();
    }

    private static String literal(ColumnSpec column, JsonNode value) {
        return value.isNull() ? "NULL" : NodeSql.literal(column, value);
    }

    private static JsonNode scalar(JsonNode rule, String field, ColumnSpec column) {
        if (!rule.has(field)) {
            throw new IllegalArgumentException("value_replace: rule for column '" + column.name()
                    + "' requires '" + field + "' (use null for SQL NULL)");
        }
        JsonNode value = rule.get(field);
        if (value.isContainerNode()) {
            throw new IllegalArgumentException("value_replace: '" + field + "' for column '" + column.name()
                    + "' must be a JSON scalar (string, number, boolean or null)");
        }
        return value;
    }

    private record Rule(JsonNode from, JsonNode to) {
    }
}
