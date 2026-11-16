package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** dap.date_time: parse, format or extract a date/time column, keeping the source column. */
public final class DateTimeExecutor implements NodeExecutor {
    private static final Set<String> UNITS = Set.of("year", "month", "day", "hour", "dow", "quarter", "week");

    @Override
    public String nodeId() {
        return "dap.date_time";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        ColumnSpec column = NodeSql.column(input.columns(), NodeSql.requiredText(config, "column"));
        String output = NodeSql.requiredText(config, "output");
        TransformSupport.requireNewName(input.columns(), output, "date_time");
        String op = NodeSql.requiredText(config, "op").toLowerCase(Locale.ROOT);
        String quoted = NodeSql.quote(column.name());

        Operation operation = switch (op) {
            case "parse" -> {
                if (!"Utf8".equals(column.arrowType())) {
                    throw new IllegalArgumentException("date_time: op 'parse' requires a Utf8 column, but '"
                            + column.name() + "' is " + column.arrowType());
                }
                String format = NodeSql.requiredText(config, "format");
                yield new Operation("strptime(" + quoted + ", " + NodeSql.literal(format) + ")", "Timestamp");
            }
            case "format" -> {
                requireTemporal(column, op);
                String format = NodeSql.requiredText(config, "format");
                yield new Operation("strftime(" + quoted + ", " + NodeSql.literal(format) + ")", "Utf8");
            }
            case "extract" -> {
                requireTemporal(column, op);
                String unit = NodeSql.requiredText(config, "unit").toLowerCase(Locale.ROOT);
                if (!UNITS.contains(unit)) {
                    throw new IllegalArgumentException("date_time: unknown unit '" + unit
                            + "' (expected year, month, day, hour, dow, quarter, week)");
                }
                if ("Time64".equals(column.arrowType()) && !"hour".equals(unit)) {
                    throw new IllegalArgumentException("date_time: Time64 supports only unit 'hour', got '"
                            + unit + "'");
                }
                // DuckDB spells day-of-week as dayofweek; keep the public vocabulary on dow.
                String part = "dow".equals(unit) ? "dayofweek" : unit;
                yield new Operation("CAST(date_part(" + NodeSql.literal(part) + ", " + quoted
                        + ") AS BIGINT)", "Int64");
            }
            default -> throw new IllegalArgumentException("date_time: unknown op '" + op
                    + "' (expected parse, format, extract)");
        };

        return TransformSupport.append(input,
                List.of(operation.expression() + " AS " + NodeSql.quote(output)),
                List.of(ColumnSpec.of(output, operation.arrowType())));
    }

    private static void requireTemporal(ColumnSpec column, String op) {
        String type = column.arrowType();
        boolean temporal = "Timestamp".equals(type) || "Date32".equals(type)
                || ("extract".equals(op) && "Time64".equals(type));
        if (!temporal) {
            throw new IllegalArgumentException("date_time: op '" + op
                    + "' requires a Timestamp or Date32 column, but '" + column.name() + "' is " + type);
        }
    }

    private record Operation(String expression, String arrowType) {
    }
}
