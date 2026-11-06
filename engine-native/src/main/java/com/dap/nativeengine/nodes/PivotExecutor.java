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

/** dap.pivot: one output column per pivot value, aggregated per index group. */
public final class PivotExecutor implements NodeExecutor {
    private static final List<String> FUNCTIONS = List.of("sum", "mean", "count", "min", "max");

    @Override
    public String nodeId() {
        return "dap.pivot";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> index = indexColumns(input, config);
        ColumnSpec pivotColumn = column(input.columns(), NodeSql.requiredText(config, "pivotColumn"), "pivotColumn");
        ColumnSpec valueColumn = column(input.columns(), NodeSql.requiredText(config, "valueColumn"), "valueColumn");
        List<String> values = values(config);
        String agg = NodeSql.requiredText(config, "agg").toLowerCase(Locale.ROOT);
        if (!FUNCTIONS.contains(agg)) {
            throw new IllegalArgumentException("pivot: unknown aggregation '" + agg
                    + "' (expected sum, mean, count, min, max)");
        }
        if (("sum".equals(agg) || "mean".equals(agg)) && !valueColumn.numeric()) {
            throw new IllegalArgumentException("pivot: " + agg + " requires a numeric value column, got '"
                    + valueColumn.name() + "' (" + valueColumn.arrowType() + ")");
        }

        StringJoiner select = new StringJoiner(", ");
        List<ColumnSpec> columns = new ArrayList<>(index);
        Set<String> names = new LinkedHashSet<>();
        for (ColumnSpec column : index) {
            select.add(NodeSql.quote(column.name()));
            names.add(column.name());
        }
        String condition = "CASE WHEN CAST(" + NodeSql.quote(pivotColumn.name()) + " AS VARCHAR) = ";
        for (String value : values) {
            if (!names.add(value)) {
                throw new IllegalArgumentException("pivot: value '" + value
                        + "' collides with another output column");
            }
            String expression = condition + NodeSql.literal(value) + " THEN "
                    + NodeSql.quote(valueColumn.name()) + " END";
            select.add(aggregate(agg, expression, valueColumn) + " AS " + NodeSql.quote(value));
            columns.add(ColumnSpec.of(value, outputType(agg, valueColumn)));
        }

        StringJoiner groupBy = new StringJoiner(", ");
        for (ColumnSpec column : index) {
            groupBy.add(NodeSql.quote(column.name()));
        }
        String sql = "SELECT " + select + " FROM " + input.asTable() + " AS dap_input GROUP BY " + groupBy;
        return new LazyPlan(context.db(), sql, columns);
    }

    private static List<ColumnSpec> indexColumns(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("index");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("pivot: 'index' must be a non-empty array of column names");
        }
        List<ColumnSpec> index = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("pivot: 'index' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("pivot: duplicate index column '" + name + "'");
            }
            index.add(column(input.columns(), name, "index"));
        }
        return index;
    }

    private static List<String> values(JsonNode config) {
        JsonNode node = config.get("values");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("pivot: 'values' must be a non-empty array of pivot values");
        }
        List<String> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("pivot: 'values' must contain non-empty strings");
            }
            String value = item.textValue();
            if (!seen.add(value)) {
                throw new IllegalArgumentException("pivot: duplicate pivot value '" + value + "'");
            }
            values.add(value);
        }
        return values;
    }

    private static ColumnSpec column(List<ColumnSpec> columns, String name, String role) {
        try {
            return NodeSql.column(columns, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("pivot: unknown " + role + " column '" + name + "'");
        }
    }

    private static String aggregate(String fn, String expression, ColumnSpec value) {
        return switch (fn) {
            case "sum" -> "CAST(SUM(" + expression + ") AS " + value.duckDbType() + ")";
            case "mean" -> "CAST(AVG(" + expression + ") AS DOUBLE)";
            case "count" -> "CAST(COUNT(" + expression + ") AS BIGINT)";
            case "min" -> "MIN(" + expression + ")";
            case "max" -> "MAX(" + expression + ")";
            default -> throw new IllegalArgumentException("pivot: unknown aggregation '" + fn + "'");
        };
    }

    private static String outputType(String fn, ColumnSpec value) {
        return switch (fn) {
            case "mean" -> "Float64";
            case "count" -> "Int64";
            default -> value.arrowType();
        };
    }
}
