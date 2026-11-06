package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.describe: one row of per-column statistics (type, non-null count, missing,
 * distinct, and numeric min/max/mean/stddev) built as a UNION ALL over the input columns.
 */
public final class DescribeExecutor implements NodeExecutor {
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("column", "Utf8"),
            ColumnSpec.of("type", "Utf8"),
            ColumnSpec.of("count", "Int64"),
            ColumnSpec.of("missing", "Int64"),
            ColumnSpec.of("distinct", "Int64"),
            ColumnSpec.of("min", "Float64"),
            ColumnSpec.of("max", "Float64"),
            ColumnSpec.of("mean", "Float64"),
            ColumnSpec.of("stddev", "Float64"));

    @Override
    public String nodeId() {
        return "dap.describe";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        List<ColumnSpec> selected = selectedColumns(input.columns(), context.config());
        if (selected.isEmpty()) {
            return new LazyPlan(context.db(), emptySql(), OUTPUT);
        }
        String table = input.asTable();
        StringJoiner union = new StringJoiner("\nUNION ALL\n");
        int ordinal = 0;
        for (ColumnSpec column : selected) {
            union.add(row(ordinal++, column, table));
        }
        String sql = "SELECT " + NodeSql.projection(OUTPUT) + " FROM (" + union + ") AS dap_describe"
                + " ORDER BY \"dap_ordinal\"";
        return new LazyPlan(context.db(), sql, OUTPUT);
    }

    private static String row(int ordinal, ColumnSpec column, String table) {
        String quoted = NodeSql.quote(column.name());
        String stats;
        if (column.numeric()) {
            stats = "CAST(MIN(" + quoted + ") AS DOUBLE) AS \"min\", "
                    + "CAST(MAX(" + quoted + ") AS DOUBLE) AS \"max\", "
                    + "CAST(AVG(" + quoted + ") AS DOUBLE) AS \"mean\", "
                    + "CAST(STDDEV_SAMP(" + quoted + ") AS DOUBLE) AS \"stddev\"";
        } else {
            stats = "CAST(NULL AS DOUBLE) AS \"min\", CAST(NULL AS DOUBLE) AS \"max\", "
                    + "CAST(NULL AS DOUBLE) AS \"mean\", CAST(NULL AS DOUBLE) AS \"stddev\"";
        }
        return "SELECT " + ordinal + " AS \"dap_ordinal\", "
                + NodeSql.literal(column.name()) + " AS \"column\", "
                + NodeSql.literal(column.knimeType()) + " AS \"type\", "
                + "CAST(COUNT(" + quoted + ") AS BIGINT) AS \"count\", "
                + "CAST(COUNT(*) - COUNT(" + quoted + ") AS BIGINT) AS \"missing\", "
                + "CAST(COUNT(DISTINCT " + quoted + ") AS BIGINT) AS \"distinct\", "
                + stats
                + " FROM " + table + " AS dap_input";
    }

    private static List<ColumnSpec> selectedColumns(List<ColumnSpec> columns, JsonNode config) {
        JsonNode node = config.get("columns");
        if (node == null || node.isNull()) {
            return List.copyOf(columns);
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("describe: 'columns' must be an array of column names");
        }
        if (node.isEmpty()) {
            return List.copyOf(columns);
        }
        List<ColumnSpec> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("describe: 'columns' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("describe: duplicate column '" + name + "'");
            }
            try {
                selected.add(NodeSql.column(columns, name));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("describe: unknown column '" + name + "'");
            }
        }
        return selected;
    }

    private static String emptySql() {
        return "SELECT CAST(NULL AS VARCHAR) AS \"column\", CAST(NULL AS VARCHAR) AS \"type\", "
                + "CAST(NULL AS BIGINT) AS \"count\", CAST(NULL AS BIGINT) AS \"missing\", "
                + "CAST(NULL AS BIGINT) AS \"distinct\", CAST(NULL AS DOUBLE) AS \"min\", "
                + "CAST(NULL AS DOUBLE) AS \"max\", CAST(NULL AS DOUBLE) AS \"mean\", "
                + "CAST(NULL AS DOUBLE) AS \"stddev\" WHERE FALSE";
    }
}
