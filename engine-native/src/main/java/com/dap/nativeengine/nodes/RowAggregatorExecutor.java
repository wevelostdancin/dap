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

/** dap.row_aggregator: adds row-wise aggregates over the selected numeric columns. */
public final class RowAggregatorExecutor implements NodeExecutor {
    private static final List<String> FUNCTIONS = List.of("sum", "mean", "min", "max", "count");

    @Override
    public String nodeId() {
        return "dap.row_aggregator";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> selected = selectedColumns(input, config);
        List<Aggregation> aggregations = aggregations(config);

        StringJoiner select = new StringJoiner(", ");
        select.add(NodeSql.projection(input.columns()));
        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        Set<String> names = new LinkedHashSet<>();
        for (ColumnSpec column : input.columns()) {
            names.add(column.name());
        }
        for (Aggregation aggregation : aggregations) {
            if (!names.add(aggregation.output())) {
                throw new IllegalArgumentException("row_aggregator: output column '" + aggregation.output()
                        + "' duplicates an existing column");
            }
            select.add(aggregationSql(aggregation.fn(), selected) + " AS " + NodeSql.quote(aggregation.output()));
            columns.add(ColumnSpec.of(aggregation.output(), outputType(aggregation.fn(), selected)));
        }
        String sql = "SELECT " + select + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, columns);
    }

    private static List<ColumnSpec> selectedColumns(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("columns");
        if (node == null || node.isNull() || (node.isArray() && node.isEmpty())) {
            List<ColumnSpec> numeric = new ArrayList<>();
            for (ColumnSpec column : input.columns()) {
                if (column.numeric()) {
                    numeric.add(column);
                }
            }
            if (numeric.isEmpty()) {
                throw new IllegalArgumentException("row_aggregator: input has no numeric columns to aggregate");
            }
            return numeric;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("row_aggregator: 'columns' must be an array of column names");
        }
        List<ColumnSpec> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("row_aggregator: 'columns' must contain non-empty column names");
            }
            String name = item.textValue();
            ColumnSpec column = column(input.columns(), name);
            if (!column.numeric()) {
                throw new IllegalArgumentException("row_aggregator: column '" + name + "' is not numeric ("
                        + column.arrowType() + ")");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("row_aggregator: duplicate column '" + name + "' in 'columns'");
            }
            selected.add(column);
        }
        return selected;
    }

    private static List<Aggregation> aggregations(JsonNode config) {
        JsonNode node = config.get("aggregations");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                    "row_aggregator: 'aggregations' must be a non-empty array of {fn, output?}");
        }
        List<Aggregation> aggregations = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            int position = i + 1;
            if (!item.isObject()) {
                throw new IllegalArgumentException("row_aggregator: aggregation " + position
                        + " must be an object with 'fn'");
            }
            String fn = NodeSql.requiredText(item, "fn").toLowerCase(Locale.ROOT);
            if (!FUNCTIONS.contains(fn)) {
                throw new IllegalArgumentException("row_aggregator: aggregation " + position
                        + " has unknown function '" + fn + "' (expected sum, mean, min, max, count)");
            }
            String output = NodeSql.text(item, "output", fn);
            if (output.isBlank()) {
                throw new IllegalArgumentException("row_aggregator: aggregation " + position
                        + " has an empty 'output' name");
            }
            aggregations.add(new Aggregation(fn, output));
        }
        return aggregations;
    }

    private static ColumnSpec column(List<ColumnSpec> columns, String name) {
        try {
            return NodeSql.column(columns, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("row_aggregator: unknown column '" + name + "'");
        }
    }

    private static String aggregationSql(String fn, List<ColumnSpec> columns) {
        String type = duckDbType(fn, columns);
        return switch (fn) {
            case "sum" -> "CAST((" + sumExpression(columns) + ") AS " + type + ")";
            case "mean" -> "CAST((" + sumExpression(columns) + ") / " + columns.size() + " AS DOUBLE)";
            case "min" -> nullGuard(columns, "LEAST(" + quotedList(columns) + ")", type);
            case "max" -> nullGuard(columns, "GREATEST(" + quotedList(columns) + ")", type);
            case "count" -> countExpression(columns);
            default -> throw new IllegalArgumentException("row_aggregator: unknown function '" + fn + "'");
        };
    }

    /** LEAST/GREATEST ignore NULLs; the spec promises NULL propagation, so guard explicitly. */
    private static String nullGuard(List<ColumnSpec> columns, String expression, String type) {
        StringJoiner nulls = new StringJoiner(" OR ");
        for (ColumnSpec column : columns) {
            nulls.add(NodeSql.quote(column.name()) + " IS NULL");
        }
        return "CASE WHEN " + nulls + " THEN NULL ELSE CAST(" + expression + " AS " + type + ") END";
    }

    private static String sumExpression(List<ColumnSpec> columns) {
        StringJoiner joiner = new StringJoiner(" + ");
        for (ColumnSpec column : columns) {
            joiner.add(NodeSql.quote(column.name()));
        }
        return joiner.toString();
    }

    private static String quotedList(List<ColumnSpec> columns) {
        StringJoiner joiner = new StringJoiner(", ");
        for (ColumnSpec column : columns) {
            joiner.add(NodeSql.quote(column.name()));
        }
        return joiner.toString();
    }

    private static String countExpression(List<ColumnSpec> columns) {
        StringJoiner joiner = new StringJoiner(" + ");
        for (ColumnSpec column : columns) {
            joiner.add("CAST(" + NodeSql.quote(column.name()) + " IS NOT NULL AS BIGINT)");
        }
        return joiner.toString();
    }

    private static boolean allInt(List<ColumnSpec> columns) {
        for (ColumnSpec column : columns) {
            if (!"Int64".equals(column.arrowType())) {
                return false;
            }
        }
        return true;
    }

    private static String duckDbType(String fn, List<ColumnSpec> columns) {
        if ("count".equals(fn)) {
            return "BIGINT";
        }
        if ("mean".equals(fn)) {
            return "DOUBLE";
        }
        return allInt(columns) ? "BIGINT" : "DOUBLE";
    }

    private static String outputType(String fn, List<ColumnSpec> columns) {
        if ("count".equals(fn)) {
            return "Int64";
        }
        if ("mean".equals(fn)) {
            return "Float64";
        }
        return allInt(columns) ? "Int64" : "Float64";
    }

    private record Aggregation(String fn, String output) {
    }
}
