package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** dap.rolling: trailing-window aggregate over an ordered (optionally partitioned) input. */
public final class RollingExecutor implements NodeExecutor {
    private static final List<String> FUNCTIONS = List.of("mean", "sum", "min", "max");

    @Override
    public String nodeId() {
        return "dap.rolling";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        ColumnSpec column = TimeSeriesSupport.column(input.columns(), NodeSql.requiredText(config, "column"),
                "value", "rolling");
        String fn = NodeSql.requiredText(config, "fn").toLowerCase(Locale.ROOT);
        if (!FUNCTIONS.contains(fn)) {
            throw new IllegalArgumentException("rolling: unknown 'fn' '" + fn
                    + "' (expected mean, sum, min, max)");
        }
        if (("mean".equals(fn) || "sum".equals(fn)) && !column.numeric()) {
            throw new IllegalArgumentException("rolling: '" + fn + "' requires a numeric column, got '"
                    + column.name() + "' (" + column.arrowType() + ")");
        }
        String output = NodeSql.requiredText(config, "output");
        TransformSupport.requireNewName(input.columns(), output, "rolling");
        int window = window(config);
        List<ColumnSpec> partitionBy = TimeSeriesSupport.partitionColumns(config, input, "rolling");
        List<TimeSeriesSupport.OrderKey> orderBy = TimeSeriesSupport.orderKeys(config, input, "rolling");

        String frame = "ROWS BETWEEN " + (window - 1) + " PRECEDING AND CURRENT ROW";
        String over = TimeSeriesSupport.overClause(partitionBy, orderBy, frame);
        String quoted = "dap_input." + NodeSql.quote(column.name());
        String expression = switch (fn) {
            case "mean" -> "CAST(AVG(" + quoted + ") OVER (" + over + ") AS DOUBLE)";
            case "sum" -> "CAST(SUM(" + quoted + ") OVER (" + over + ") AS " + column.duckDbType() + ")";
            case "min" -> "CAST(MIN(" + quoted + ") OVER (" + over + ") AS " + column.duckDbType() + ")";
            case "max" -> "CAST(MAX(" + quoted + ") OVER (" + over + ") AS " + column.duckDbType() + ")";
            default -> throw new IllegalArgumentException("rolling: unknown 'fn' '" + fn + "'");
        };

        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        columns.add(ColumnSpec.of(output, "mean".equals(fn) ? "Float64" : column.arrowType()));
        String sql = "SELECT " + NodeSql.projection(input.columns()) + ", " + expression
                + " AS " + NodeSql.quote(output) + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, columns);
    }

    private static int window(JsonNode config) {
        JsonNode value = config.get("window");
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("rolling: config field 'window' is required");
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException("rolling: 'window' must be a positive integer, got "
                    + value.asText());
        }
        return value.intValue();
    }
}
