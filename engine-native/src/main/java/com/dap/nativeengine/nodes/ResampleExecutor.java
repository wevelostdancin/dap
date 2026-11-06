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

/** dap.resample: bucket a timestamp column into fixed intervals and aggregate each bucket. */
public final class ResampleExecutor implements NodeExecutor {
    private static final List<String> FUNCTIONS = List.of("mean", "sum", "count", "min", "max");

    @Override
    public String nodeId() {
        return "dap.resample";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        ColumnSpec time = TimeSeriesSupport.column(input.columns(), NodeSql.requiredText(config, "timeColumn"),
                "time", "resample");
        if (!"Timestamp".equals(time.arrowType())) {
            throw new IllegalArgumentException("resample: time column '" + time.name() + "' is "
                    + time.arrowType() + "; resample requires a Timestamp column");
        }
        String every = TimeSeriesSupport.interval(config, "every", "resample");

        JsonNode aggregations = config.get("aggregations");
        if (aggregations == null || aggregations.isNull() || !aggregations.isArray() || aggregations.isEmpty()) {
            throw new IllegalArgumentException(
                    "resample: 'aggregations' must be a non-empty array of {column, fn, output?}");
        }

        String bucket = "time_bucket(INTERVAL " + NodeSql.literal(every) + ", dap_input."
                + NodeSql.quote(time.name()) + ")";
        StringJoiner select = new StringJoiner(", ");
        select.add(bucket + " AS " + NodeSql.quote(time.name()));
        List<ColumnSpec> columns = new ArrayList<>();
        columns.add(time);
        Set<String> names = new LinkedHashSet<>();
        names.add(time.name());

        for (int index = 0; index < aggregations.size(); index++) {
            JsonNode aggregation = aggregations.get(index);
            int position = index + 1;
            if (!aggregation.isObject()) {
                throw new IllegalArgumentException("resample: aggregation " + position
                        + " must be an object with 'column' and 'fn'");
            }
            String columnName = NodeSql.requiredText(aggregation, "column");
            ColumnSpec column = TimeSeriesSupport.column(input.columns(), columnName, "aggregate", "resample");
            String fn = NodeSql.requiredText(aggregation, "fn").toLowerCase(Locale.ROOT);
            if (!FUNCTIONS.contains(fn)) {
                throw new IllegalArgumentException("resample: aggregation " + position + " has unknown 'fn' '" + fn
                        + "' (expected mean, sum, count, min, max)");
            }
            if (("mean".equals(fn) || "sum".equals(fn)) && !column.numeric()) {
                throw new IllegalArgumentException("resample: aggregation " + position + " ('" + fn
                        + "') requires a numeric column, got '" + column.name() + "' (" + column.arrowType() + ")");
            }
            String output = output(aggregation, fn, column.name());
            if (!names.add(output)) {
                throw new IllegalArgumentException("resample: output '" + output
                        + "' duplicates an existing column");
            }
            select.add(aggregateSql(fn, column) + " AS " + NodeSql.quote(output));
            columns.add(ColumnSpec.of(output, outputType(fn, column)));
        }

        String sql = "SELECT " + select + " FROM " + input.asTable()
                + " AS dap_input WHERE dap_input." + NodeSql.quote(time.name()) + " IS NOT NULL"
                + " GROUP BY 1 ORDER BY 1";
        return new LazyPlan(context.db(), sql, columns);
    }

    private static String output(JsonNode aggregation, String fn, String column) {
        String output = NodeSql.text(aggregation, "output", null);
        if (output == null || output.isBlank()) {
            return aggregateName(fn, column);
        }
        return output;
    }

    private static String aggregateName(String fn, String column) {
        return switch (fn) {
            case "mean" -> "Mean(" + column + ")";
            case "sum" -> "Sum(" + column + ")";
            case "count" -> "Count(" + column + ")";
            case "min" -> "Min(" + column + ")";
            case "max" -> "Max(" + column + ")";
            default -> throw new IllegalArgumentException("resample: unknown aggregation function '" + fn + "'");
        };
    }

    private static String aggregateSql(String fn, ColumnSpec column) {
        String quoted = "dap_input." + NodeSql.quote(column.name());
        return switch (fn) {
            case "mean" -> "CAST(AVG(" + quoted + ") AS DOUBLE)";
            case "sum" -> "CAST(SUM(" + quoted + ") AS " + column.duckDbType() + ")";
            case "count" -> "CAST(COUNT(" + quoted + ") AS BIGINT)";
            case "min" -> "MIN(" + quoted + ")";
            case "max" -> "MAX(" + quoted + ")";
            default -> throw new IllegalArgumentException("resample: unknown aggregation function '" + fn + "'");
        };
    }

    private static String outputType(String fn, ColumnSpec column) {
        return switch (fn) {
            case "mean" -> "Float64";
            case "count" -> "Int64";
            default -> column.arrowType();
        };
    }
}
