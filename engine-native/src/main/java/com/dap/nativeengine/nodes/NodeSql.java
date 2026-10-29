package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.StringJoiner;

/** SQL building blocks shared by the native node executors. */
final class NodeSql {
    private NodeSql() {
    }

    /** The only input this engine supports today. */
    static LazyPlan input(List<LazyPlan> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.get(0) == null) {
            throw new IllegalArgumentException("node requires a connected input");
        }
        return inputs.get(0);
    }

    static ColumnSpec column(List<ColumnSpec> columns, String name) {
        for (ColumnSpec column : columns) {
            if (column.name().equals(name)) {
                return column;
            }
        }
        throw new IllegalArgumentException("unknown column '" + name + "'");
    }

    static String quote(String identifier) {
        return DuckDbSession.quote(identifier);
    }

    static String projection(List<ColumnSpec> columns) {
        StringJoiner joiner = new StringJoiner(", ");
        for (ColumnSpec column : columns) {
            joiner.add(quote(column.name()));
        }
        return joiner.toString();
    }

    /** Config/rule field that must be present and textual. */
    static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
            throw new IllegalArgumentException("config field '" + field + "' is required");
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("config field '" + field + "' must be a string");
        }
        return value.textValue();
    }

    static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("config field '" + field + "' must be a string");
        }
        return value.textValue();
    }

    static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("config field '" + field + "' must be a boolean");
        }
        return value.booleanValue();
    }

    /** Single-quoted SQL string literal with internal quotes doubled. */
    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** SQL literal for a config value, typed by the target column. */
    static String literal(ColumnSpec column, JsonNode value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("missing value for column '" + column.name() + "'");
        }
        String type = column.arrowType();
        try {
            if (column.numeric()) {
                return numericLiteral(column, value);
            }
            if ("Boolean".equals(type)) {
                return booleanLiteral(column, value);
            }
            String text = value.isTextual() ? value.textValue() : value.asText();
            return switch (type) {
                case "Date32" -> "CAST(" + literal(LocalDate.parse(text.trim()).toString()) + " AS DATE)";
                case "Time64" -> "CAST(" + literal(LocalTime.parse(text.trim()).toString()) + " AS TIME)";
                case "Timestamp" -> "CAST(" + literal(timestamp(text)) + " AS TIMESTAMP)";
                default -> literal(text);
            };
        } catch (NumberFormatException | java.time.format.DateTimeParseException failure) {
            throw new IllegalArgumentException("invalid value '" + value.asText() + "' for " + type
                    + " column '" + column.name() + "': " + failure.getMessage(), failure);
        }
    }

    private static String numericLiteral(ColumnSpec column, JsonNode value) {
        String type = column.arrowType();
        if (value.isNumber()) {
            if ("Int64".equals(type)) {
                if (!value.isIntegralNumber()) {
                    throw new IllegalArgumentException("value for Int64 column '" + column.name()
                            + "' must be an integer");
                }
                return Long.toString(value.longValue());
            }
            if ("Float64".equals(type)) {
                return Double.toString(value.doubleValue());
            }
            return value.decimalValue().toPlainString();
        }
        String text = value.asText().trim();
        if ("Int64".equals(type)) {
            return Long.toString(Long.parseLong(text));
        }
        if ("Float64".equals(type)) {
            return Double.toString(Double.parseDouble(text));
        }
        return new BigDecimal(text).toPlainString();
    }

    private static String booleanLiteral(ColumnSpec column, JsonNode value) {
        if (value.isBoolean()) {
            return value.booleanValue() ? "TRUE" : "FALSE";
        }
        String text = value.asText().trim();
        if ("true".equalsIgnoreCase(text)) {
            return "TRUE";
        }
        if ("false".equalsIgnoreCase(text)) {
            return "FALSE";
        }
        throw new IllegalArgumentException("invalid boolean value '" + text + "' for column '" + column.name() + "'");
    }

    private static String timestamp(String text) {
        return LocalDateTime.parse(text.trim().replace(' ', 'T')).toString();
    }
}
