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

/** dap.outlier_detect: flag rows with a value outside per-column IQR fences or z-score bounds. */
public final class OutlierDetectExecutor implements NodeExecutor {
    private static final double IQR_THRESHOLD = 1.5;
    private static final double ZSCORE_THRESHOLD = 3.0;
    private static final String OUTPUT = "outlier";

    @Override
    public String nodeId() {
        return "dap.outlier_detect";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String method = NodeSql.requiredText(config, "method").toLowerCase(Locale.ROOT);
        if (!"iqr".equals(method) && !"zscore".equals(method)) {
            throw new IllegalArgumentException("outlier_detect: unknown method '" + method
                    + "' (expected iqr or zscore)");
        }
        double threshold = threshold(config, method);
        List<ColumnSpec> columns = columns(config, input, method);
        TransformSupport.requireNewName(input.columns(), OUTPUT, "outlier_detect");

        String table = input.asTable();
        StringJoiner stats = new StringJoiner(", ");
        StringJoiner flags = new StringJoiner(" OR ");
        for (int index = 0; index < columns.size(); index++) {
            ColumnSpec column = columns.get(index);
            String value = "dap_input." + NodeSql.quote(column.name());
            // Statistics read the input as their own table expression: referencing dap_input
            // would make the join LATERAL, and DuckDB forbids aggregates in a lateral.
            String source = "dap_outlier_source." + NodeSql.quote(column.name());
            if ("iqr".equals(method)) {
                String q1 = "quantile_cont(" + source + ", 0.25)";
                String q3 = "quantile_cont(" + source + ", 0.75)";
                String spread = "(" + q3 + " - " + q1 + ")";
                stats.add(q1 + " - " + threshold + " * " + spread + " AS dap_lo_" + index);
                stats.add(q3 + " + " + threshold + " * " + spread + " AS dap_hi_" + index);
                flags.add("(" + value + " IS NOT NULL AND (" + value + " < dap_stats.dap_lo_" + index
                        + " OR " + value + " > dap_stats.dap_hi_" + index + "))");
            } else {
                stats.add("avg(" + source + ") AS dap_mean_" + index);
                stats.add("COALESCE(stddev(" + source + "), 0) AS dap_sd_" + index);
                flags.add("(" + value + " IS NOT NULL AND abs(" + value + " - dap_stats.dap_mean_" + index
                        + ") > " + threshold + " * dap_stats.dap_sd_" + index + ")");
            }
        }

        String sql = "SELECT dap_input.*, COALESCE(" + flags + ", FALSE) AS " + NodeSql.quote(OUTPUT)
                + " FROM " + table + " AS dap_input CROSS JOIN (SELECT " + stats + " FROM " + table
                + " AS dap_outlier_source) AS dap_stats";
        List<ColumnSpec> output = new ArrayList<>(input.columns());
        output.add(ColumnSpec.of(OUTPUT, "Boolean"));
        return new LazyPlan(context.db(), sql, output);
    }

    private static List<ColumnSpec> columns(JsonNode config, LazyPlan input, String method) {
        JsonNode node = config.get("columns");
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("outlier_detect: config field 'columns' is required");
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                    "outlier_detect: 'columns' must be a non-empty array of column names");
        }
        List<ColumnSpec> columns = new ArrayList<>(node.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(
                        "outlier_detect: 'columns' must contain non-empty column names");
            }
            ColumnSpec column = TimeSeriesSupport.column(input.columns(), item.textValue(), "value",
                    "outlier_detect");
            if (!column.numeric()) {
                throw new IllegalArgumentException("outlier_detect: column '" + column.name() + "' is "
                        + column.arrowType() + "; method '" + method + "' requires a numeric column");
            }
            if (seen.add(column.name())) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static double threshold(JsonNode config, String method) {
        JsonNode node = config.get("threshold");
        if (node == null || node.isNull()) {
            return "iqr".equals(method) ? IQR_THRESHOLD : ZSCORE_THRESHOLD;
        }
        if (!node.isNumber()) {
            throw new IllegalArgumentException("outlier_detect: 'threshold' must be a positive number");
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("outlier_detect: 'threshold' must be a positive finite number, got "
                    + node.asText());
        }
        return value;
    }
}
