package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * dap.histogram: equal-width bins over the observed min/max. Every configured bin is
 * emitted, empty ones included, via a generated series LEFT JOINed with the counts.
 */
public final class HistogramExecutor implements NodeExecutor {
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("bin_start", "Float64"),
            ColumnSpec.of("bin_end", "Float64"),
            ColumnSpec.of("count", "Int64"));

    @Override
    public String nodeId() {
        return "dap.histogram";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        String name = NodeSql.requiredText(context.config(), "column");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("histogram: unknown column '" + name + "'");
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException("histogram: column '" + name + "' is not numeric ("
                    + column.arrowType() + ")");
        }
        int bins = bins(context.config());
        String quoted = NodeSql.quote(column.name());
        String table = input.asTable();
        String sql = "WITH dap_bounds AS ("
                + "SELECT CAST(MIN(" + quoted + ") AS DOUBLE) AS lo, CAST(MAX(" + quoted + ") AS DOUBLE) AS hi"
                + " FROM " + table + " AS dap_input), "
                + "dap_bins AS ("
                + "SELECT CAST(t.i AS BIGINT) AS \"bin\", "
                + "b.lo + (b.hi - b.lo) * t.i / " + bins + " AS \"bin_start\", "
                + "b.lo + (b.hi - b.lo) * (t.i + 1) / " + bins + " AS \"bin_end\""
                + " FROM dap_bounds AS b, generate_series(0, " + (bins - 1) + ") AS t(i)"
                + " WHERE b.lo IS NOT NULL), "
                + "dap_counts AS ("
                + "SELECT CASE WHEN b.hi = b.lo THEN CAST(0 AS BIGINT) "
                + "ELSE LEAST(CAST(FLOOR((d." + quoted + " - b.lo) / ((b.hi - b.lo) / " + bins + ")) AS BIGINT), "
                + (bins - 1) + ") END AS \"bin\", COUNT(*) AS n"
                + " FROM " + table + " AS d, dap_bounds AS b"
                + " WHERE d." + quoted + " IS NOT NULL AND b.lo IS NOT NULL"
                + " GROUP BY 1) "
                + "SELECT CAST(b.\"bin_start\" AS DOUBLE) AS \"bin_start\", "
                + "CAST(b.\"bin_end\" AS DOUBLE) AS \"bin_end\", "
                + "CAST(COALESCE(c.n, 0) AS BIGINT) AS \"count\""
                + " FROM dap_bins AS b LEFT JOIN dap_counts AS c ON c.\"bin\" = b.\"bin\""
                + " ORDER BY b.\"bin\"";
        return new LazyPlan(context.db(), sql, OUTPUT);
    }

    private static int bins(JsonNode config) {
        JsonNode node = config.get("bins");
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("config field 'bins' is required");
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalArgumentException("histogram: 'bins' must be an integer greater than 0");
        }
        int bins = node.intValue();
        if (bins <= 0) {
            throw new IllegalArgumentException("histogram: 'bins' must be greater than 0, got " + bins);
        }
        return bins;
    }
}
