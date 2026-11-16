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

/** dap.outlier_filter: drop rows outside per-column IQR fences or z-score bounds; NULLs pass. */
public final class OutlierFilterExecutor implements NodeExecutor {
    private static final double IQR_THRESHOLD = 1.5;
    private static final double ZSCORE_THRESHOLD = 3.0;

    @Override
    public String nodeId() {
        return "dap.outlier_filter";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        List<ColumnSpec> columns = columns(context.config(), input);
        String method = NodeSql.requiredText(context.config(), "method").toLowerCase(Locale.ROOT);
        if (!"iqr".equals(method) && !"zscore".equals(method)) {
            throw new IllegalArgumentException("outlier_filter: unknown method '" + method
                    + "' (expected iqr or zscore)");
        }
        double threshold = threshold(context.config(), method);
        for (ColumnSpec column : columns) {
            if (!column.numeric()) {
                throw new IllegalArgumentException("outlier_filter: column '" + column.name() + "' is "
                        + column.arrowType() + "; method '" + method + "' requires a numeric column");
            }
        }

        String table = input.asTable();
        StringJoiner stats = new StringJoiner(", ");
        List<String> predicates = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            ColumnSpec column = columns.get(index);
            String value = "dap_input." + NodeSql.quote(column.name());
            // Statistics read the input as their own table expression: referencing dap_input
            // here would make the join LATERAL, and DuckDB forbids aggregates in a lateral.
            String source = "dap_outlier_source." + NodeSql.quote(column.name());
            if ("iqr".equals(method)) {
                String q1 = "quantile_cont(" + source + ", 0.25)";
                String q3 = "quantile_cont(" + source + ", 0.75)";
                String spread = "(" + q3 + " - " + q1 + ")";
                stats.add(q1 + " - " + threshold + " * " + spread + " AS dap_lo_" + index);
                stats.add(q3 + " + " + threshold + " * " + spread + " AS dap_hi_" + index);
                predicates.add("(" + value + " IS NULL OR (" + value + " >= dap_stats.dap_lo_" + index
                        + " AND " + value + " <= dap_stats.dap_hi_" + index + "))");
            } else {
                stats.add("avg(" + source + ") AS dap_mean_" + index);
                stats.add("COALESCE(stddev(" + source + "), 0) AS dap_sd_" + index);
                predicates.add("(" + value + " IS NULL OR abs(" + value + " - dap_stats.dap_mean_" + index
                        + ") <= " + threshold + " * dap_stats.dap_sd_" + index + ")");
            }
        }

        String sql = "SELECT dap_input.* FROM " + table + " AS dap_input CROSS JOIN (SELECT " + stats
                + " FROM " + table + " AS dap_outlier_source) AS dap_stats WHERE "
                + String.join(" AND ", predicates);
        return new LazyPlan(context.db(), sql, input.columns());
    }

    private static List<ColumnSpec> columns(JsonNode config, LazyPlan input) {
        JsonNode node = config.get("columns");
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("outlier_filter: config field 'columns' is required");
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                    "outlier_filter: 'columns' must be a non-empty array of column names");
        }
        List<ColumnSpec> columns = new ArrayList<>(node.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(
                        "outlier_filter: 'columns' must contain non-empty column names");
            }
            ColumnSpec column = NodeSql.column(input.columns(), item.textValue());
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
            throw new IllegalArgumentException("outlier_filter: 'threshold' must be a positive number");
        }
        double value = node.doubleValue();
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("outlier_filter: 'threshold' must be a positive finite number, got "
                    + node.asText());
        }
        return value;
    }
}
