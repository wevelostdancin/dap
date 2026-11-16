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
    /**
     * KNIME Sorter compares strings with NATURAL order, so digit runs must compare
     * numerically: "1st-4th" sorts before "10th". DuckDB has no natural collation,
     * so a text value is expanded into alternating text-chunk / zero-padded-number
     * sort keys; the raw value remains the final tie-break.
     */
    private static final int NATURAL_DIGIT_RUNS = 4;
    private static final int NATURAL_NUMBER_WIDTH = 20;
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

        String collation = NodeSql.text(config, "collation", "natural");
        String normalized = collation.trim().toLowerCase(Locale.ROOT);
        boolean nocase = false;
        boolean natural = false;
        if (normalized.isEmpty() || "natural".equals(normalized)) {
            natural = true;
        } else if ("nocase".equals(normalized)) {
            nocase = true;
        } else if (!"binary".equals(normalized)) {
            throw new IllegalArgumentException("sorter: unsupported collation '" + collation
                    + "' (expected binary, nocase or natural)");
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
            String quoted = NodeSql.quote(column.name());
            String orderClause = direction.toUpperCase(Locale.ROOT) + " " + nulls;
            if (natural && "Utf8".equals(column.arrowType())) {
                addNaturalKeys(order, quoted, orderClause);
                continue;
            }
            StringBuilder expression = new StringBuilder(quoted);
            if (nocase && "Utf8".equals(column.arrowType())) {
                expression.append(" COLLATE NOCASE");
            }
            expression.append(' ').append(orderClause);
            order.add(expression.toString());
        }

        String sql = "SELECT * FROM " + input.asTable() + " AS dap_input ORDER BY " + String.join(", ", order);
        return new LazyPlan(context.db(), sql, input.columns());
    }

    private static void addNaturalKeys(List<String> order, String quoted, String orderClause) {
        String chunks = "string_split_regex(" + quoted + ", '[0-9]+')";
        String numbers = "regexp_extract_all(" + quoted + ", '[0-9]+')";
        for (int index = 1; index <= NATURAL_DIGIT_RUNS; index++) {
            order.add("COALESCE(" + chunks + "[" + index + "], '') " + orderClause);
            order.add("COALESCE(LPAD(" + numbers + "[" + index + "], "
                    + NATURAL_NUMBER_WIDTH + ", '0'), '') " + orderClause);
        }
        order.add(quoted + " " + orderClause);
    }
}
