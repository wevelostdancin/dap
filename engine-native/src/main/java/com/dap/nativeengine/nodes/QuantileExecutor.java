package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** dap.quantile: continuous quantiles of one numeric column, one output row per quantile. */
public final class QuantileExecutor implements NodeExecutor {
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("quantile", "Float64"),
            ColumnSpec.of("value", "Float64"));

    @Override
    public String nodeId() {
        return "dap.quantile";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        String name = NodeSql.requiredText(context.config(), "column");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("quantile: unknown column '" + name + "'");
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException("quantile: column '" + name + "' is not numeric ("
                    + column.arrowType() + ")");
        }
        List<Double> quantiles = quantiles(context.config());
        String quoted = NodeSql.quote(column.name());
        String table = input.asTable();
        StringJoiner union = new StringJoiner("\nUNION ALL\n");
        int ordinal = 0;
        for (double value : quantiles) {
            String literal = BigDecimal.valueOf(value).toPlainString();
            union.add("SELECT " + ordinal++ + " AS \"dap_ordinal\", "
                    + "CAST(" + literal + " AS DOUBLE) AS \"quantile\", "
                    + "CAST(QUANTILE_CONT(" + quoted + ", " + literal + ") AS DOUBLE) AS \"value\""
                    + " FROM " + table + " AS dap_input");
        }
        String sql = "SELECT " + NodeSql.projection(OUTPUT) + " FROM (" + union + ") AS dap_quantile"
                + " ORDER BY \"dap_ordinal\"";
        return new LazyPlan(context.db(), sql, OUTPUT);
    }

    private static List<Double> quantiles(JsonNode config) {
        JsonNode node = config.get("quantiles");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                    "quantile: 'quantiles' must be a non-empty array of numbers between 0 and 1");
        }
        List<Double> quantiles = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isNumber()) {
                throw new IllegalArgumentException("quantile: 'quantiles' must contain numbers, got '" + item + "'");
            }
            double value = item.doubleValue();
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException("quantile: quantile " + item + " is outside [0, 1]");
            }
            quantiles.add(value);
        }
        return quantiles;
    }
}
