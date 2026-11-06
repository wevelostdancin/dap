package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** dap.type_cast: CAST selected columns to another Arrow type, keeping names, order and other columns. */
public final class TypeCastExecutor implements NodeExecutor {
    private static final Pattern DECIMAL = Pattern.compile("Decimal128\\((\\d+),\\s*(\\d+)\\)");
    private static final List<String> SUPPORTED = List.of(
            "Int64", "Float64", "Utf8", "Boolean", "Date32", "Timestamp");

    @Override
    public String nodeId() {
        return "dap.type_cast";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode columns = context.config().get("columns");
        if (columns == null || columns.isNull()) {
            throw new IllegalArgumentException("type_cast: config field 'columns' is required");
        }
        if (!columns.isArray()) {
            throw new IllegalArgumentException("type_cast: 'columns' must be an array of {column,type}");
        }

        Map<String, String> targets = new LinkedHashMap<>();
        for (JsonNode item : columns) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(
                        "type_cast: every 'columns' entry must be an object with 'column' and 'type'");
            }
            String name = text(item, "column");
            String type = arrowType(text(item, "type"), name);
            if (targets.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException("type_cast: duplicate column '" + name + "'");
            }
        }
        if (targets.isEmpty()) {
            return input;
        }
        for (String name : targets.keySet()) {
            NodeSql.column(input.columns(), name);
        }

        List<ColumnSpec> output = new ArrayList<>(input.columns().size());
        List<String> projection = new ArrayList<>(input.columns().size());
        for (ColumnSpec column : input.columns()) {
            String type = targets.get(column.name());
            String quoted = NodeSql.quote(column.name());
            if (type == null) {
                output.add(column);
                projection.add(quoted);
                continue;
            }
            ColumnSpec cast = ColumnSpec.of(column.name(), type);
            output.add(cast);
            projection.add("CAST(" + quoted + " AS " + cast.duckDbType() + ") AS " + quoted);
        }

        String sql = "SELECT " + String.join(", ", projection) + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, output);
    }

    private static String text(JsonNode item, String field) {
        JsonNode value = item.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("type_cast: '" + field + "' must be a non-empty string");
        }
        return value.textValue();
    }

    private static String arrowType(String raw, String column) {
        String canonical = TypeMap.canonical(raw.trim());
        if (canonical.startsWith("Decimal128")) {
            Matcher matcher = DECIMAL.matcher(canonical);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("type_cast: column '" + column + "' has unsupported type '" + raw
                        + "'; write decimals as Decimal128(precision,scale), for example Decimal128(18,4)");
            }
            int precision = Integer.parseInt(matcher.group(1));
            int scale = Integer.parseInt(matcher.group(2));
            if (precision < 1 || precision > 38) {
                throw new IllegalArgumentException("type_cast: column '" + column + "' has unsupported type '" + raw
                        + "'; Decimal128 precision must be between 1 and 38");
            }
            if (scale < 0 || scale > precision) {
                throw new IllegalArgumentException("type_cast: column '" + column + "' has unsupported type '" + raw
                        + "'; Decimal128 scale must be between 0 and the precision");
            }
            return canonical;
        }
        if (!SUPPORTED.contains(canonical)) {
            throw new IllegalArgumentException("type_cast: column '" + column + "' has unsupported type '" + raw
                    + "'; supported types are " + String.join(", ", SUPPORTED) + ", Decimal128(precision,scale)");
        }
        return canonical;
    }
}
