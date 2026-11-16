package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** dap.row_filter: keep rows matching a conjunction/disjunction of comparisons. */
public final class RowFilterExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.row_filter";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        JsonNode where = config.get("where");
        if (where == null || where.isNull() || (where.isArray() && where.isEmpty())) {
            return input;
        }
        if (!where.isArray()) {
            throw new IllegalArgumentException("row_filter: 'where' must be an array of conditions");
        }

        String logic = NodeSql.text(config, "logic", "and").toLowerCase(Locale.ROOT);
        String separator;
        if ("and".equals(logic)) {
            separator = " AND ";
        } else if ("or".equals(logic)) {
            separator = " OR ";
        } else {
            throw new IllegalArgumentException("row_filter: logic must be 'and' or 'or', got '" + logic + "'");
        }

        List<String> predicates = new ArrayList<>(where.size());
        for (JsonNode condition : where) {
            if (!condition.isObject()) {
                throw new IllegalArgumentException("row_filter: every 'where' entry must be an object");
            }
            ColumnSpec column = NodeSql.column(input.columns(), NodeSql.requiredText(condition, "column"));
            String op = NodeSql.requiredText(condition, "op").toLowerCase(Locale.ROOT);
            String quoted = NodeSql.quote(column.name());
            String predicate;
            switch (op) {
                case "eq" -> predicate = quoted + " = " + NodeSql.literal(column, condition.get("value"));
                case "ne" -> predicate = quoted + " <> " + NodeSql.literal(column, condition.get("value"));
                case "lt" -> predicate = quoted + " < " + NodeSql.literal(column, condition.get("value"));
                case "le" -> predicate = quoted + " <= " + NodeSql.literal(column, condition.get("value"));
                case "gt" -> predicate = quoted + " > " + NodeSql.literal(column, condition.get("value"));
                case "ge" -> predicate = quoted + " >= " + NodeSql.literal(column, condition.get("value"));
                case "is_null" -> predicate = quoted + " IS NULL";
                case "not_null" -> predicate = quoted + " IS NOT NULL";
                default -> throw new IllegalArgumentException("row_filter: unknown operator '" + op
                        + "' (expected eq, ne, lt, le, gt, ge, is_null, not_null)");
            }
            predicates.add("(" + predicate + ")");
        }

        String sql = "SELECT * FROM " + input.asTable() + " AS dap_input WHERE "
                + String.join(separator, predicates);
        return new LazyPlan(context.db(), sql, input.columns());
    }
}
