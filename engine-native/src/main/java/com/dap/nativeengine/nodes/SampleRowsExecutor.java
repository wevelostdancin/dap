package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/** dap.sample_rows: keeps the first n rows or draws a reproducible random reservoir sample. */
public final class SampleRowsExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.sample_rows";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String mode = NodeSql.requiredText(config, "mode").toLowerCase(Locale.ROOT);
        long n = positiveInt(config);
        String from = " FROM " + input.asTable() + " AS dap_input";
        String sql = switch (mode) {
            case "head" -> "SELECT *" + from + " LIMIT " + n;
            case "random" -> {
                Long seed = seed(config);
                yield "SELECT *" + from + " USING SAMPLE reservoir(" + n + " ROWS)"
                        + (seed == null ? "" : " REPEATABLE (" + seed + ")");
            }
            default -> throw new IllegalArgumentException("sample_rows: unknown mode '" + mode
                    + "' (expected head, random)");
        };
        return new LazyPlan(input.session(), sql, input.columns());
    }

    private static long positiveInt(JsonNode config) {
        JsonNode value = config.get("n");
        if (value == null || value.isNull() || !value.isIntegralNumber() || value.longValue() <= 0) {
            throw new IllegalArgumentException("sample_rows: config field 'n' must be a positive integer");
        }
        return value.longValue();
    }

    private static Long seed(JsonNode config) {
        JsonNode value = config.get("seed");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("sample_rows: config field 'seed' must be an integer");
        }
        return value.longValue();
    }
}
