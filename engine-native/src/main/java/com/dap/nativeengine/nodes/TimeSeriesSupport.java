package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared parsing for the time-series nodes: bucket intervals, order keys and partitions. */
final class TimeSeriesSupport {
    /** A positive "<n> <unit>" interval such as "1 day", "15 minutes" or "2 weeks". */
    private static final Pattern INTERVAL =
            Pattern.compile("^(\\d+)\\s+(second|minute|hour|day|week|month|year)s?$");

    private TimeSeriesSupport() {
    }

    /** Validate a positive interval and return it trimmed for an INTERVAL literal. */
    static String interval(JsonNode config, String field, String node) {
        String text = NodeSql.text(config, field, null);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(node + ": config field '" + field + "' is required");
        }
        String trimmed = text.trim();
        Matcher matcher = INTERVAL.matcher(trimmed.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException(node + ": '" + field
                    + "' must look like '1 day' or '15 minutes', got '" + trimmed + "'");
        }
        if (matcher.group(1).chars().allMatch(digit -> digit == '0')) {
            throw new IllegalArgumentException(node + ": '" + field
                    + "' must be a positive interval, got '" + trimmed + "'");
        }
        return trimmed;
    }

    /** One ORDER BY key: a resolved input column plus a validated direction. */
    record OrderKey(ColumnSpec column, String direction) {
        String sql() {
            return NodeSql.quote(column.name()) + " " + direction.toUpperCase(Locale.ROOT)
                    + ("asc".equals(direction) ? " NULLS FIRST" : " NULLS LAST");
        }
    }

    /** Required, non-empty ORDER BY list; every entry names an input column and an asc/desc direction. */
    static List<OrderKey> orderKeys(JsonNode config, LazyPlan input, String node) {
        JsonNode array = config == null ? null : config.get("orderBy");
        if (array == null || array.isNull() || !array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException(node
                    + ": 'orderBy' must be a non-empty array of {column, direction}");
        }
        List<OrderKey> keys = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonNode item = array.get(index);
            int position = index + 1;
            if (!item.isObject()) {
                throw new IllegalArgumentException(node + ": 'orderBy' entry " + position
                        + " must be an object with 'column' and 'direction'");
            }
            String name = NodeSql.requiredText(item, "column");
            ColumnSpec column;
            try {
                column = NodeSql.column(input.columns(), name);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(node + ": unknown order column '" + name + "'");
            }
            String direction = NodeSql.text(item, "direction", "asc").toLowerCase(Locale.ROOT);
            if (!"asc".equals(direction) && !"desc".equals(direction)) {
                throw new IllegalArgumentException(node + ": 'orderBy' entry " + position
                        + " has direction '" + direction + "' (expected asc or desc)");
            }
            keys.add(new OrderKey(column, direction));
        }
        return keys;
    }

    /** Optional PARTITION BY list; every entry names an input column exactly once. */
    static List<ColumnSpec> partitionColumns(JsonNode config, LazyPlan input, String node) {
        JsonNode array = config == null ? null : config.get("partitionBy");
        List<ColumnSpec> columns = new ArrayList<>();
        if (array == null || array.isNull()) {
            return columns;
        }
        if (!array.isArray()) {
            throw new IllegalArgumentException(node + ": 'partitionBy' must be an array of column names");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(node + ": 'partitionBy' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException(node + ": duplicate partition column '" + name + "'");
            }
            try {
                columns.add(NodeSql.column(input.columns(), name));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(node + ": unknown partition column '" + name + "'");
            }
        }
        return columns;
    }

    /** OVER (...) body: the partition, the required ordering and an optional frame. */
    static String overClause(List<ColumnSpec> partitionBy, List<OrderKey> orderBy, String frame) {
        StringJoiner parts = new StringJoiner(" ");
        if (!partitionBy.isEmpty()) {
            StringJoiner partition = new StringJoiner(", ");
            for (ColumnSpec column : partitionBy) {
                partition.add(NodeSql.quote(column.name()));
            }
            parts.add("PARTITION BY " + partition);
        }
        StringJoiner order = new StringJoiner(", ");
        for (OrderKey key : orderBy) {
            order.add(key.sql());
        }
        parts.add("ORDER BY " + order);
        if (frame != null && !frame.isBlank()) {
            parts.add(frame);
        }
        return parts.toString();
    }

    /** Resolve an input column by name with the node name in the error message. */
    static ColumnSpec column(List<ColumnSpec> columns, String name, String role, String node) {
        try {
            return NodeSql.column(columns, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(node + ": unknown " + role + " column '" + name + "'");
        }
    }
}
