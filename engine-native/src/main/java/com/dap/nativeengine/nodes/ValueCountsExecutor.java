package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** dap.value_counts: frequency table of one column, NULLs excluded. */
public final class ValueCountsExecutor implements NodeExecutor {
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("value", "Utf8"),
            ColumnSpec.of("count", "Int64"));
    private static final int DEFAULT_LIMIT = 20;

    @Override
    public String nodeId() {
        return "dap.value_counts";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        String name = NodeSql.requiredText(context.config(), "column");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("value_counts: unknown column '" + name + "'");
        }
        int limit = limit(context.config());
        String quoted = NodeSql.quote(column.name());
        String sql = "SELECT CAST(" + quoted + " AS VARCHAR) AS \"value\", CAST(COUNT(*) AS BIGINT) AS \"count\""
                + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + quoted + " IS NOT NULL"
                + " GROUP BY 1"
                + " ORDER BY 2 DESC, 1 ASC"
                + " LIMIT " + limit;
        return new LazyPlan(context.db(), sql, OUTPUT);
    }

    private static int limit(JsonNode config) {
        JsonNode node = config.get("limit");
        if (node == null || node.isNull()) {
            return DEFAULT_LIMIT;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalArgumentException("value_counts: 'limit' must be an integer greater than 0");
        }
        int limit = node.intValue();
        if (limit <= 0) {
            throw new IllegalArgumentException("value_counts: 'limit' must be greater than 0, got " + limit);
        }
        return limit;
    }
}
