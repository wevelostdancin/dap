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

/** dap.window: adds SQL window function columns over partitions and orderings. */
public final class WindowExecutor implements NodeExecutor {
    private static final List<String> FUNCTIONS =
            List.of("row_number", "rank", "dense_rank", "lag", "lead", "sum", "mean");

    @Override
    public String nodeId() {
        return "dap.window";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> partitionBy = partitionColumns(input, config);
        List<OrderKey> orderBy = orderKeys(input, config);
        JsonNode functions = config.get("functions");
        if (functions == null || functions.isNull() || !functions.isArray() || functions.isEmpty()) {
            throw new IllegalArgumentException(
                    "window: 'functions' must be a non-empty array of {fn, column?, offset?, output}");
        }

        String over = overClause(partitionBy, orderBy);
        StringJoiner select = new StringJoiner(", ");
        select.add(NodeSql.projection(input.columns()));
        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        Set<String> names = new LinkedHashSet<>();
        for (ColumnSpec column : input.columns()) {
            names.add(column.name());
        }

        for (int i = 0; i < functions.size(); i++) {
            JsonNode function = functions.get(i);
            int position = i + 1;
            if (!function.isObject()) {
                throw new IllegalArgumentException("window: function " + position
                        + " must be an object with 'fn' and 'output'");
            }
            String fn = NodeSql.requiredText(function, "fn").toLowerCase(Locale.ROOT);
            if (!FUNCTIONS.contains(fn)) {
                throw new IllegalArgumentException("window: function " + position + " has unknown 'fn' '" + fn
                        + "' (expected row_number, rank, dense_rank, lag, lead, sum, mean)");
            }
            String output = NodeSql.requiredText(function, "output");
            if (!names.add(output)) {
                throw new IllegalArgumentException("window: output '" + output
                        + "' duplicates an existing column");
            }
            requireOrdering(fn, position, orderBy);
            select.add(windowSql(fn, function, position, input, over) + " AS " + NodeSql.quote(output));
            columns.add(ColumnSpec.of(output, outputType(fn, function, position, input)));
        }

        String sql = "SELECT " + select + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, columns);
    }

    private static List<ColumnSpec> partitionColumns(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("partitionBy");
        List<ColumnSpec> columns = new ArrayList<>();
        if (node == null || node.isNull()) {
            return columns;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("window: 'partitionBy' must be an array of column names");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("window: 'partitionBy' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("window: duplicate partition column '" + name + "'");
            }
            columns.add(column(input.columns(), name, "partition"));
        }
        return columns;
    }

    private static List<OrderKey> orderKeys(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("orderBy");
        List<OrderKey> keys = new ArrayList<>();
        if (node == null || node.isNull()) {
            return keys;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("window: 'orderBy' must be an array of {column, direction}");
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            int position = i + 1;
            if (!item.isObject()) {
                throw new IllegalArgumentException("window: 'orderBy' entry " + position
                        + " must be an object with 'column' and 'direction'");
            }
            String name = NodeSql.requiredText(item, "column");
            ColumnSpec column = column(input.columns(), name, "order");
            String direction = NodeSql.text(item, "direction", "asc").toLowerCase(Locale.ROOT);
            if (!"asc".equals(direction) && !"desc".equals(direction)) {
                throw new IllegalArgumentException("window: 'orderBy' entry " + position
                        + " has direction '" + direction + "' (expected asc or desc)");
            }
            keys.add(new OrderKey(column, direction));
        }
        return keys;
    }

    private static ColumnSpec column(List<ColumnSpec> columns, String name, String role) {
        try {
            return NodeSql.column(columns, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("window: unknown " + role + " column '" + name + "'");
        }
    }

    private static void requireOrdering(String fn, int position, List<OrderKey> orderBy) {
        boolean needsOrder = "rank".equals(fn) || "dense_rank".equals(fn)
                || "lag".equals(fn) || "lead".equals(fn);
        if (needsOrder && orderBy.isEmpty()) {
            throw new IllegalArgumentException("window: function " + position + " ('" + fn
                    + "') requires a non-empty 'orderBy'");
        }
    }

    private static String overClause(List<ColumnSpec> partitionBy, List<OrderKey> orderBy) {
        StringJoiner parts = new StringJoiner(" ");
        if (!partitionBy.isEmpty()) {
            StringJoiner partition = new StringJoiner(", ");
            for (ColumnSpec column : partitionBy) {
                partition.add(NodeSql.quote(column.name()));
            }
            parts.add("PARTITION BY " + partition);
        }
        if (!orderBy.isEmpty()) {
            StringJoiner order = new StringJoiner(", ");
            for (OrderKey key : orderBy) {
                order.add(NodeSql.quote(key.column().name()) + " "
                        + key.direction().toUpperCase(Locale.ROOT)
                        + ("asc".equals(key.direction()) ? " NULLS FIRST" : " NULLS LAST"));
            }
            parts.add("ORDER BY " + order);
        }
        return parts.toString();
    }

    private static String windowSql(String fn, JsonNode function, int position, LazyPlan input, String over) {
        return switch (fn) {
            case "row_number" -> "ROW_NUMBER() OVER (" + over + ")";
            case "rank" -> "RANK() OVER (" + over + ")";
            case "dense_rank" -> "DENSE_RANK() OVER (" + over + ")";
            case "lag" -> "LAG(" + NodeSql.quote(columnName(function, "lag", position, input)) + ", "
                    + offset(function, position) + ") OVER (" + over + ")";
            case "lead" -> "LEAD(" + NodeSql.quote(columnName(function, "lead", position, input)) + ", "
                    + offset(function, position) + ") OVER (" + over + ")";
            case "sum" -> "CAST(SUM(" + NodeSql.quote(columnName(function, "sum", position, input))
                    + ") OVER (" + over + ") AS "
                    + column(function, "sum", position, input).duckDbType() + ")";
            case "mean" -> "CAST(AVG(" + NodeSql.quote(columnName(function, "mean", position, input))
                    + ") OVER (" + over + ") AS DOUBLE)";
            default -> throw new IllegalArgumentException("window: unknown function '" + fn + "'");
        };
    }

    private static ColumnSpec column(JsonNode function, String fn, int position, LazyPlan input) {
        String name = columnName(function, fn, position, input);
        ColumnSpec column = column(input.columns(), name, "function");
        if (("sum".equals(fn) || "mean".equals(fn)) && !column.numeric()) {
            throw new IllegalArgumentException("window: function " + position + " ('" + fn
                    + "') requires a numeric column, got '" + name + "' (" + column.arrowType() + ")");
        }
        return column;
    }

    private static String columnName(JsonNode function, String fn, int position, LazyPlan input) {
        JsonNode value = function.get("column");
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("window: function " + position + " ('" + fn
                    + "') requires a non-empty 'column'");
        }
        return value.textValue();
    }

    private static int offset(JsonNode function, int position) {
        JsonNode value = function.get("offset");
        if (value == null || value.isNull()) {
            return 1;
        }
        if (!value.isIntegralNumber() || value.longValue() < 0 || value.longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("window: function " + position
                    + " has an invalid 'offset' (expected a non-negative integer)");
        }
        return value.intValue();
    }

    private static String outputType(String fn, JsonNode function, int position, LazyPlan input) {
        return switch (fn) {
            case "row_number", "rank", "dense_rank" -> "Int64";
            case "mean" -> "Float64";
            case "sum" -> column(function, "sum", position, input).arrowType();
            case "lag", "lead" -> column(function, fn, position, input).arrowType();
            default -> throw new IllegalArgumentException("window: unknown function '" + fn + "'");
        };
    }

    private record OrderKey(ColumnSpec column, String direction) {
    }
}
