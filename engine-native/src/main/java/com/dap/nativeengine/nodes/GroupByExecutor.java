package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** dap.group_by: Groups rows by key columns and aggregates per group, named like KNIME (Mean(x)). */
public final class GroupByExecutor implements NodeExecutor {
    private static final List<String> FUNCTIONS = List.of(
            "mean", "sum", "count", "min", "max", "count_distinct", "median", "percentile");
    private static final List<String> NUMERIC_FUNCTIONS = List.of("mean", "sum", "median", "percentile");

    @Override
    public String nodeId() {
        return "dap.group_by";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        JsonNode keyNodes = config.get("keys");
        if (keyNodes == null || !keyNodes.isArray()) {
            throw new IllegalArgumentException("group_by: 'keys' must be an array of column names");
        }
        if (keyNodes.isEmpty()) {
            throw new IllegalArgumentException("group_by: 'keys' must contain at least one column");
        }

        StringJoiner select = new StringJoiner(", ");
        StringJoiner groupBy = new StringJoiner(", ");
        List<ColumnSpec> output = new ArrayList<>();
        for (JsonNode key : keyNodes) {
            if (!key.isTextual() || key.textValue().isBlank()) {
                throw new IllegalArgumentException("group_by: 'keys' must contain non-empty strings");
            }
            ColumnSpec column = column(input.columns(), key.textValue(), "key");
            output.add(column);
            select.add(NodeSql.quote(column.name()));
            groupBy.add(NodeSql.quote(column.name()));
        }

        JsonNode aggregationNodes = config.get("aggregations");
        if (aggregationNodes == null || !aggregationNodes.isArray()) {
            throw new IllegalArgumentException("group_by: 'aggregations' must be an array");
        }
        for (JsonNode aggregation : aggregationNodes) {
            if (!aggregation.isObject()) {
                throw new IllegalArgumentException("group_by: every 'aggregations' entry must be an object");
            }
            ColumnSpec column = column(input.columns(), NodeSql.requiredText(aggregation, "column"), "aggregate");
            String fn = NodeSql.requiredText(aggregation, "fn").toLowerCase(Locale.ROOT);
            if (!FUNCTIONS.contains(fn)) {
                throw new IllegalArgumentException("group_by: unknown aggregation function '" + fn
                        + "' (expected mean, sum, count, min, max, count_distinct, median, percentile)");
            }
            if (NUMERIC_FUNCTIONS.contains(fn) && !column.numeric()) {
                throw new IllegalArgumentException("group_by: " + fn + " requires a numeric column, got '"
                        + column.name() + "' (" + column.arrowType() + ")");
            }
            Double probability = "percentile".equals(fn) ? probability(aggregation) : null;
            String name = aggregateName(fn, column.name(), probability);
            select.add(aggregateSql(fn, column, probability) + " AS " + NodeSql.quote(name));
            output.add(ColumnSpec.of(name, outputType(fn, column)));
        }

        String sql = "SELECT " + select + " FROM " + input.asTable() + " AS dap_input GROUP BY " + groupBy;
        return new LazyPlan(context.db(), sql, output);
    }

    private static ColumnSpec column(List<ColumnSpec> columns, String name, String role) {
        try {
            return NodeSql.column(columns, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("group_by: unknown " + role + " column '" + name + "'");
        }
    }

    private static String aggregateName(String fn, String column, Double probability) {
        return switch (fn) {
            case "mean" -> "Mean(" + column + ")";
            case "sum" -> "Sum(" + column + ")";
            case "count" -> "Count(" + column + ")";
            case "min" -> "Min(" + column + ")";
            case "max" -> "Max(" + column + ")";
            case "count_distinct" -> "CountDistinct(" + column + ")";
            case "median" -> "Median(" + column + ")";
            case "percentile" -> "Percentile(" + column + ", " + probability + ")";
            default -> throw new IllegalArgumentException("group_by: unknown aggregation function '" + fn + "'");
        };
    }

    private static String aggregateSql(String fn, ColumnSpec column, Double probability) {
        String quoted = NodeSql.quote(column.name());
        // Decimal inputs are cast to DOUBLE so the aggregate has a stable Arrow type.
        String numeric = "CAST(" + quoted + " AS DOUBLE)";
        return switch (fn) {
            case "mean" -> "CAST(AVG(" + quoted + ") AS DOUBLE)";
            case "sum" -> "CAST(SUM(" + quoted + ") AS " + column.duckDbType() + ")";
            case "count" -> "CAST(COUNT(" + quoted + ") AS BIGINT)";
            case "min" -> "MIN(" + quoted + ")";
            case "max" -> "MAX(" + quoted + ")";
            case "count_distinct" -> "CAST(COUNT(DISTINCT " + quoted + ") AS BIGINT)";
            case "median" -> "CAST(MEDIAN(" + numeric + ") AS DOUBLE)";
            case "percentile" -> "CAST(QUANTILE_CONT(" + numeric + ", " + probability + ") AS DOUBLE)";
            default -> throw new IllegalArgumentException("group_by: unknown aggregation function '" + fn + "'");
        };
    }

    /** Percentile probability from the aggregation's 'p', which must be a number in [0, 1]. */
    private static Double probability(JsonNode aggregation) {
        JsonNode value = aggregation.get("p");
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("group_by: percentile requires 'p' in [0, 1]");
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException("group_by: percentile 'p' must be a number in [0, 1]");
        }
        double probability = value.doubleValue();
        if (!(probability >= 0.0 && probability <= 1.0)) {
            throw new IllegalArgumentException("group_by: percentile 'p' must be within [0, 1], got " + probability);
        }
        return probability;
    }

    private static String outputType(String fn, ColumnSpec column) {
        return switch (fn) {
            case "mean", "median", "percentile" -> "Float64";
            case "count", "count_distinct" -> "Int64";
            default -> column.arrowType();
        };
    }
}
