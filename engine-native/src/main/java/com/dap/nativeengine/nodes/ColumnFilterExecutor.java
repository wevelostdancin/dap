package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** dap.column_filter: keep or drop a named set of columns, preserving input order. */
public final class ColumnFilterExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.column_filter";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        JsonNode include = config.get("include");
        JsonNode exclude = config.get("exclude");
        boolean hasInclude = include != null && !include.isNull();
        boolean hasExclude = exclude != null && !exclude.isNull();
        if (hasInclude == hasExclude) {
            throw new IllegalArgumentException("column_filter: exactly one of 'include' or 'exclude' must be set");
        }

        Set<String> names = names(hasInclude ? include : exclude, hasInclude ? "include" : "exclude");
        for (String name : names) {
            NodeSql.column(input.columns(), name);
        }

        List<ColumnSpec> selected = new ArrayList<>();
        for (ColumnSpec column : input.columns()) {
            if (hasInclude == names.contains(column.name())) {
                selected.add(column);
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("column_filter: no columns would remain");
        }

        String sql = "SELECT " + NodeSql.projection(selected) + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, selected);
    }

    private static Set<String> names(JsonNode node, String field) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("column_filter: '" + field + "' must be an array of column names");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("column_filter: '" + field + "' must contain non-empty strings");
            }
            names.add(item.textValue());
        }
        return names;
    }
}
