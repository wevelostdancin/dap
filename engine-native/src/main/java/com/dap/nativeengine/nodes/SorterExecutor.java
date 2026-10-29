package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** dap.sorter: multi-key ORDER BY with KNIME null placement (ASC first, DESC last). */
public final class SorterExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.sorter";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        JsonNode keys = config.get("keys");
        if (keys == null || keys.isNull() || (keys.isArray() && keys.isEmpty())) {
            return input;
        }
        if (!keys.isArray()) {
            throw new IllegalArgumentException("sorter: 'keys' must be an array of {column,direction}");
        }

        String collation = NodeSql.text(config, "collation", "binary");
        String normalized = collation.trim().toLowerCase(Locale.ROOT);
        boolean nocase;
        if (normalized.isEmpty() || "binary".equals(normalized)) {
            nocase = false;
        } else if ("nocase".equals(normalized)) {
            nocase = true;
        } else {
            throw new IllegalArgumentException("sorter: unsupported collation '" + collation
                    + "' (expected binary or nocase)");
        }

        List<String> order = new ArrayList<>(keys.size());
        for (JsonNode key : keys) {
            if (!key.isObject()) {
                throw new IllegalArgumentException("sorter: every 'keys' entry must be an object");
            }
            ColumnSpec column = NodeSql.column(input.columns(), NodeSql.requiredText(key, "column"));
            String direction = NodeSql.text(key, "direction", "asc").toLowerCase(Locale.ROOT);
            String nulls;
            if ("asc".equals(direction)) {
                nulls = "NULLS FIRST";
            } else if ("desc".equals(direction)) {
                nulls = "NULLS LAST";
            } else {
                throw new IllegalArgumentException("sorter: direction must be 'asc' or 'desc', got '" + direction + "'");
            }
            StringBuilder expression = new StringBuilder(NodeSql.quote(column.name()));
            if (nocase && "Utf8".equals(column.arrowType())) {
                expression.append(" COLLATE NOCASE");
            }
            expression.append(' ').append(direction.toUpperCase(Locale.ROOT)).append(' ').append(nulls);
            order.add(expression.toString());
        }

        String sql = "SELECT * FROM " + input.asTable() + " AS dap_input ORDER BY " + String.join(", ", order);
        return new LazyPlan(context.db(), sql, input.columns());
    }
}
