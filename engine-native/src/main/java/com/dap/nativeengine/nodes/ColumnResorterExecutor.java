package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** dap.column_resorter: project the input columns in a new order; the list must be a permutation. */
public final class ColumnResorterExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.column_resorter";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode columns = context.config().get("columns");
        if (columns == null || columns.isNull()) {
            throw new IllegalArgumentException("column_resorter: config field 'columns' is required");
        }
        if (!columns.isArray()) {
            throw new IllegalArgumentException("column_resorter: 'columns' must be an array of column names");
        }

        Map<String, ColumnSpec> byName = new LinkedHashMap<>();
        for (ColumnSpec column : input.columns()) {
            byName.put(column.name(), column);
        }
        List<ColumnSpec> output = new ArrayList<>(input.columns().size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : columns) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(
                        "column_resorter: 'columns' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("column_resorter: duplicate column '" + name + "'");
            }
            ColumnSpec column = byName.get(name);
            if (column == null) {
                throw new IllegalArgumentException("column_resorter: unknown column '" + name + "'");
            }
            output.add(column);
        }
        if (output.size() != input.columns().size()) {
            throw new IllegalArgumentException("column_resorter: 'columns' must be a permutation of the input columns ("
                    + input.columns().size() + " expected, " + output.size() + " given)");
        }

        String sql = "SELECT " + NodeSql.projection(output) + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, output);
    }
}
