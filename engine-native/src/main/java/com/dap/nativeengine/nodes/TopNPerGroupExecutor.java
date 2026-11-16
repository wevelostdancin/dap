package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/** dap.top_n_per_group: keeps the first n rows of every group using QUALIFY ROW_NUMBER(). */
public final class TopNPerGroupExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.top_n_per_group";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> groupBy = groupColumns(input, config);
        List<OrderKey> orderBy = orderKeys(input, config);
        long n = limit(config);

        StringJoiner partition = new StringJoiner(", ");
        for (ColumnSpec column : groupBy) {
            partition.add(NodeSql.quote(column.name()));
        }
        StringJoiner order = new StringJoiner(", ");
        for (OrderKey key : orderBy) {
            order.add(NodeSql.quote(key.column().name()) + " "
                    + key.direction().toUpperCase(Locale.ROOT)
                    + ("asc".equals(key.direction()) ? " NULLS FIRST" : " NULLS LAST"));
        }

        String sql = "SELECT * FROM " + input.asTable() + " AS dap_input QUALIFY ROW_NUMBER() OVER (PARTITION BY "
                + partition + " ORDER BY " + order + ") <= " + n;
        return new LazyPlan(context.db(), sql, input.columns());
    }

    private static List<ColumnSpec> groupColumns(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("groupBy");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("top_n_per_group: 'groupBy' must be a non-empty array of column names");
        }
        List<ColumnSpec> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("top_n_per_group: 'groupBy' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("top_n_per_group: duplicate group column '" + name + "'");
            }
            columns.add(column(input.columns(), name, "group"));
        }
        return columns;
    }

    private static List<OrderKey> orderKeys(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("orderBy");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("top_n_per_group: 'orderBy' must be a non-empty array of {column, direction}");
        }
        List<OrderKey> keys = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            int position = i + 1;
            if (!item.isObject()) {
                throw new IllegalArgumentException("top_n_per_group: 'orderBy' entry " + position
                        + " must be an object with 'column' and 'direction'");
            }
            String name = NodeSql.requiredText(item, "column");
            ColumnSpec column = column(input.columns(), name, "order");
            String direction = NodeSql.text(item, "direction", "asc").toLowerCase(Locale.ROOT);
            if (!"asc".equals(direction) && !"desc".equals(direction)) {
                throw new IllegalArgumentException("top_n_per_group: 'orderBy' entry " + position
                        + " has direction '" + direction + "' (expected asc or desc)");
            }
            keys.add(new OrderKey(column, direction));
        }
        return keys;
    }

    private static long limit(JsonNode config) {
        JsonNode value = config.get("n");
        if (value == null || value.isNull() || !value.isIntegralNumber()) {
            throw new IllegalArgumentException("top_n_per_group: 'n' must be a positive integer");
        }
        long n = value.longValue();
        if (n <= 0) {
            throw new IllegalArgumentException("top_n_per_group: 'n' must be greater than zero, got " + n);
        }
        return n;
    }

    private static ColumnSpec column(List<ColumnSpec> columns, String name, String role) {
        try {
            return NodeSql.column(columns, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("top_n_per_group: unknown " + role + " column '" + name + "'");
        }
    }

    private record OrderKey(ColumnSpec column, String direction) {
    }
}
