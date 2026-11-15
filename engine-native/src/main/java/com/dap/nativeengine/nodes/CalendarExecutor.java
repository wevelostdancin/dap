package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.calendar: timezone-aware calendar features of a date or timestamp column — year,
 * quarter, month, week, day, day of week, day of year, weekend and a holiday flag.
 *
 * <p>A Timestamp column is interpreted as UTC (the session timezone) and converted to the
 * requested zone in SQL, so 2024-01-01 22:00 UTC is already 2024-01-02 in Europe/Moscow.
 * The original columns stay in place.
 */
public final class CalendarExecutor implements NodeExecutor {
    private static final Set<String> FEATURES = Set.of("year", "quarter", "month", "week", "day",
            "dow", "doy", "is_weekend", "is_holiday");
    private static final List<String> DEFAULT_FEATURES = List.of("year", "quarter", "month", "week",
            "day", "dow", "doy", "is_weekend");

    @Override
    public String nodeId() {
        return "dap.calendar";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        ColumnSpec column = column(config, input.columns());
        String timezone = timezone(config);
        List<String> holidays = holidays(config);
        List<String> features = features(config, holidays);
        String holidayColumn = NodeSql.text(config, "holiday_column",
                column.name() + "_is_holiday");
        if (features.contains("is_holiday") && holidays.isEmpty()) {
            throw new IllegalArgumentException("calendar: 'is_holiday' needs a non-empty 'holidays' list");
        }

        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec existing : input.columns()) {
            projection.add("c." + NodeSql.quote(existing.name()));
        }
        String local = localExpression(column, timezone);
        for (String feature : features) {
            String name = "is_holiday".equals(feature) ? holidayColumn : column.name() + "_" + feature;
            projection.add(expression(feature, local, holidays) + " AS " + NodeSql.quote(name));
            columns.add(ColumnSpec.of(name, "is_weekend".equals(feature) || "is_holiday".equals(feature)
                    ? "Boolean" : "Int64"));
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS c";
        return new LazyPlan(context.db(), sql, columns);
    }

    /** UTC timestamps are shifted into the requested zone; dates have no time of day. */
    private static String localExpression(ColumnSpec column, String timezone) {
        String base = "c." + NodeSql.quote(column.name());
        if (timezone == null || "Date32".equals(column.arrowType())) {
            return base;
        }
        return "(" + base + " AT TIME ZONE 'UTC') AT TIME ZONE " + NodeSql.literal(timezone);
    }

    private static String expression(String feature, String local, List<String> holidays) {
        return switch (feature) {
            case "year" -> "year(" + local + ")";
            case "quarter" -> "quarter(" + local + ")";
            case "month" -> "month(" + local + ")";
            case "week" -> "week(" + local + ")";
            case "day" -> "day(" + local + ")";
            case "dow" -> "dayofweek(" + local + ")";
            case "doy" -> "dayofyear(" + local + ")";
            case "is_weekend" -> "dayofweek(" + local + ") IN (0, 6)";
            default -> {
                StringJoiner dates = new StringJoiner(", ", "(", ")");
                for (String holiday : holidays) {
                    dates.add("DATE " + NodeSql.literal(holiday));
                }
                yield "CAST(" + local + " AS DATE) IN " + dates;
            }
        };
    }

    private static ColumnSpec column(JsonNode config, List<ColumnSpec> columns) {
        String name = NodeSql.requiredText(config, "column");
        for (ColumnSpec column : columns) {
            if (column.name().equals(name)) {
                if (!"Date32".equals(column.arrowType()) && !"Timestamp".equals(column.arrowType())) {
                    throw new IllegalArgumentException("calendar: column '" + name + "' is "
                            + column.arrowType() + "; a date or timestamp column is required");
                }
                return column;
            }
        }
        throw new IllegalArgumentException("calendar: unknown column '" + name + "'");
    }

    private static String timezone(JsonNode config) {
        String timezone = NodeSql.text(config, "timezone", null);
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("calendar: unknown timezone '" + timezone + "'", invalid);
        }
        return timezone;
    }

    private static List<String> holidays(JsonNode config) {
        JsonNode node = config.get("holidays");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("calendar: 'holidays' must be an array of ISO dates");
        }
        List<String> holidays = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("calendar: 'holidays' must contain ISO dates");
            }
            try {
                holidays.add(LocalDate.parse(item.textValue().strip()).toString());
            } catch (DateTimeException invalid) {
                throw new IllegalArgumentException("calendar: invalid date '" + item.textValue()
                        + "' in 'holidays'; use ISO yyyy-MM-dd", invalid);
            }
        }
        return holidays;
    }

    private static List<String> features(JsonNode config, List<String> holidays) {
        JsonNode node = config.get("features");
        if (node == null || node.isNull()) {
            List<String> features = new ArrayList<>(DEFAULT_FEATURES);
            if (!holidays.isEmpty()) {
                features.add("is_holiday");
            }
            return features;
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("calendar: 'features' must be a non-empty array");
        }
        Set<String> features = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("calendar: 'features' must contain strings");
            }
            String feature = item.textValue().strip().toLowerCase(Locale.ROOT);
            if (!FEATURES.contains(feature)) {
                throw new IllegalArgumentException("calendar: unknown feature '" + item.textValue()
                        + "'; expected one of " + new java.util.TreeSet<>(FEATURES));
            }
            features.add(feature);
        }
        return List.copyOf(features);
    }
}
