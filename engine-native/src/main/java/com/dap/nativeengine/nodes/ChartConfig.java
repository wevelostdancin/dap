package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.viz.ChartSpec;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and validates the settings of dap.chart and dap.chart_grid into a {@link ChartSpec}.
 * Chart type decides which columns are required: x and y for cartesian charts, lat/lng for the
 * map, a series for the heatmap.
 */
final class ChartConfig {
    static final int DEFAULT_WIDTH = 640;
    static final int DEFAULT_HEIGHT = 400;
    static final int MIN_SIZE = 100;
    static final int MAX_SIZE = 4000;
    static final int DEFAULT_LIMIT = 1000;
    static final int MAX_LIMIT = 100_000;
    static final int DEFAULT_BINS = 10;
    static final int MAX_BINS = 1000;
    static final int DEFAULT_FACET_COLUMNS = 2;
    static final int MAX_FACET_COLUMNS = 12;

    private static final Set<String> TYPES = new LinkedHashSet<>(List.of(
            "bar", "line", "scatter", "histogram", "pie", "boxplot", "heatmap", "map"));

    private ChartConfig() {
    }

    static ChartSpec parse(JsonNode config, List<ColumnSpec> columns, String node) {
        return parse(config, columns, node, 0, 0);
    }

    /** Parse chart settings; a positive width/height overrides the canvas size (grid cells). */
    static ChartSpec parse(JsonNode config, List<ColumnSpec> columns, String node, int width,
                           int height) {
        String type = requiredText(config, "type", node);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException(node + ": unknown 'type' '" + type
                    + "', expected " + String.join(", ", TYPES));
        }
        String x = text(config, "x", node);
        List<String> y = columnNames(config, "y", node);
        String series = text(config, "series", node);
        String lat = text(config, "lat", node);
        String lng = text(config, "lng", node);
        String size = text(config, "size", node);
        String color = text(config, "color", node);
        String label = text(config, "label", node);

        validateColumns(node, type, columns, x, y, series, lat, lng, size, color, label);
        String facet = text(config, "facet", node);
        if (facet != null) {
            NodeSql.column(columns, facet);
        }

        boolean logX = bool(config, "log_x", false, node);
        boolean logY = bool(config, "log_y", false, node);
        if (logX && !List.of("line", "scatter").contains(type)) {
            throw new IllegalArgumentException(node + ": 'log_x' is only supported for line and "
                    + "scatter charts");
        }
        if (logY && List.of("pie", "heatmap", "map", "histogram").contains(type)) {
            throw new IllegalArgumentException(node + ": " + type + " does not support 'log_y'");
        }

        ChartSpec.SortMode sort = sort(config, type, node);
        boolean descending = descending(config, sort, node);

        return new ChartSpec(type, x, y, series,
                text(config, "title", node), text(config, "subtitle", node),
                text(config, "x_label", node), text(config, "y_label", node),
                text(config, "x_unit", node), text(config, "y_unit", node),
                width > 0 ? width : integer(config, "width", DEFAULT_WIDTH, MIN_SIZE, MAX_SIZE, node),
                height > 0 ? height
                        : integer(config, "height", DEFAULT_HEIGHT, MIN_SIZE, MAX_SIZE, node),
                integer(config, "bins", DEFAULT_BINS, 1, MAX_BINS, node),
                integer(config, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT, node),
                sort, descending, logX, logY,
                bool(config, "grid", true, node),
                bool(config, "data_labels", false, node),
                annotations(config, node),
                facet,
                integer(config, "facet_columns", DEFAULT_FACET_COLUMNS, 1, MAX_FACET_COLUMNS, node),
                lat, lng, size, color, label);
    }

    private static void validateColumns(String node, String type, List<ColumnSpec> columns,
                                        String x, List<String> y, String series, String lat,
                                        String lng, String size, String color, String label) {
        if (type.equals("map")) {
            if (lat == null || lat.isBlank()) {
                throw new IllegalArgumentException(node + ": map requires a 'lat' column");
            }
            if (lng == null || lng.isBlank()) {
                throw new IllegalArgumentException(node + ": map requires a 'lng' column");
            }
            requireNumeric(node, columns, lat, "lat");
            requireNumeric(node, columns, lng, "lng");
            if (!y.isEmpty()) {
                throw new IllegalArgumentException(node + ": map does not take 'y'; it plots "
                        + "'lat'/'lng' points");
            }
            if (size != null) {
                requireNumeric(node, columns, size, "size");
            }
            if (color != null) {
                NodeSql.column(columns, color);
            }
            if (label != null) {
                NodeSql.column(columns, label);
            }
            if (series != null) {
                throw new IllegalArgumentException(node + ": map does not take 'series'");
            }
            return;
        }
        if (x == null || x.isBlank()) {
            throw new IllegalArgumentException(node + ": config field 'x' is required");
        }
        ColumnSpec xColumn = NodeSql.column(columns, x);
        if (type.equals("histogram")) {
            if (!y.isEmpty()) {
                throw new IllegalArgumentException(node + ": histogram does not take 'y'; it bins "
                        + "the 'x' column");
            }
            if (!xColumn.numeric()) {
                throw new IllegalArgumentException(node + ": histogram 'x' column '" + x
                        + "' must be numeric");
            }
            if (series != null) {
                throw new IllegalArgumentException(node + ": histogram does not take 'series'");
            }
            if (lat != null || lng != null) {
                throw new IllegalArgumentException(node + ": 'lat'/'lng' are only used by map");
            }
            return;
        }
        if (y.isEmpty()) {
            throw new IllegalArgumentException(node + ": 'y' must be a non-empty array of numeric "
                    + "column names");
        }
        if (List.of("bar", "pie", "boxplot", "heatmap").contains(type) && y.size() != 1) {
            throw new IllegalArgumentException(node + ": " + type + " takes exactly one 'y' "
                    + "column, got " + y.size());
        }
        for (String name : y) {
            requireNumeric(node, columns, name, "y");
        }
        if (series != null) {
            NodeSql.column(columns, series);
            if (y.size() != 1) {
                throw new IllegalArgumentException(node + ": with 'series' 'y' must contain exactly "
                        + "one column");
            }
            if (!List.of("bar", "line", "scatter", "heatmap").contains(type)) {
                throw new IllegalArgumentException(node + ": " + type + " does not take 'series'");
            }
        }
        if (type.equals("heatmap") && series == null) {
            throw new IllegalArgumentException(node + ": heatmap requires a 'series' column for the "
                    + "row axis");
        }
        if ((type.equals("line") || type.equals("scatter")) && !xColumn.numeric()) {
            throw new IllegalArgumentException(node + ": " + type + " 'x' column '" + x
                    + "' must be numeric");
        }
        if (lat != null || lng != null) {
            throw new IllegalArgumentException(node + ": 'lat'/'lng' are only used by map");
        }
    }

    private static void requireNumeric(String node, List<ColumnSpec> columns, String name,
                                       String field) {
        ColumnSpec column = NodeSql.column(columns, name);
        if (!column.numeric()) {
            throw new IllegalArgumentException(node + ": '" + field + "' column '" + name
                    + "' must be numeric");
        }
    }

    private static ChartSpec.SortMode sort(JsonNode config, String type, String node) {
        String value = text(config, "sort", node);
        if (value == null) {
            return List.of("bar", "pie", "boxplot").contains(type)
                    ? ChartSpec.SortMode.VALUE : ChartSpec.SortMode.LABEL;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "value" -> ChartSpec.SortMode.VALUE;
            case "label" -> ChartSpec.SortMode.LABEL;
            case "none" -> ChartSpec.SortMode.NONE;
            default -> throw new IllegalArgumentException(node + ": unknown 'sort' '" + value
                    + "', expected value, label or none");
        };
    }

    private static boolean descending(JsonNode config, ChartSpec.SortMode sort, String node) {
        String value = text(config, "sort_direction", node);
        if (value == null) {
            return sort == ChartSpec.SortMode.VALUE;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "asc" -> false;
            case "desc" -> true;
            default -> throw new IllegalArgumentException(node + ": unknown 'sort_direction' '"
                    + value + "', expected asc or desc");
        };
    }

    private static List<ChartSpec.Annotation> annotations(JsonNode config, String node) {
        JsonNode nodeArray = config.get("annotations");
        if (nodeArray == null || nodeArray.isNull()) {
            return List.of();
        }
        if (!nodeArray.isArray()) {
            throw new IllegalArgumentException(node + ": 'annotations' must be an array of objects");
        }
        List<ChartSpec.Annotation> annotations = new ArrayList<>();
        for (JsonNode item : nodeArray) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(node + ": each annotation must be an object");
            }
            String text = requiredText(item, "text", node);
            String category = null;
            Double x = null;
            Double y = null;
            JsonNode xNode = item.get("x");
            if (xNode != null && !xNode.isNull()) {
                if (xNode.isNumber()) {
                    x = xNode.doubleValue();
                } else if (xNode.isTextual() && !xNode.textValue().isBlank()) {
                    category = xNode.textValue();
                } else {
                    throw new IllegalArgumentException(node + ": annotation 'x' must be a number "
                            + "or a category name");
                }
            }
            JsonNode yNode = item.get("y");
            if (yNode != null && !yNode.isNull()) {
                if (!yNode.isNumber()) {
                    throw new IllegalArgumentException(node + ": annotation 'y' must be a number");
                }
                y = yNode.doubleValue();
            }
            String color = null;
            JsonNode colorNode = item.get("color");
            if (colorNode != null && !colorNode.isNull()) {
                if (!colorNode.isTextual() || !colorNode.textValue().matches("#[0-9a-fA-F]{6}")) {
                    throw new IllegalArgumentException(node + ": annotation 'color' must be a "
                            + "#rrggbb string");
                }
                color = colorNode.textValue();
            }
            annotations.add(new ChartSpec.Annotation(text, category, x, y, color));
        }
        return annotations;
    }

    // ------------------------------------------------------------------ primitives

    private static String requiredText(JsonNode node, String field, String prefix) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.textValue().isBlank())) {
            throw new IllegalArgumentException(prefix + ": config field '" + field + "' is required");
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(prefix + ": config field '" + field
                    + "' must be a string");
        }
        return value.textValue();
    }

    static String text(JsonNode node, String field, String prefix) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(prefix + ": config field '" + field
                    + "' must be a string");
        }
        return value.textValue().isBlank() ? null : value.textValue();
    }

    private static boolean bool(JsonNode node, String field, boolean fallback, String prefix) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(prefix + ": config field '" + field
                    + "' must be a boolean");
        }
        return value.booleanValue();
    }

    static int integer(JsonNode node, String field, int fallback, int minimum, int maximum,
                       String prefix) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(prefix + ": '" + field + "' must be an integer");
        }
        long number = value.longValue();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(prefix + ": '" + field + "' must be between "
                    + minimum + " and " + maximum + ", got " + number);
        }
        return (int) number;
    }

    private static List<String> columnNames(JsonNode config, String field, String prefix) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(prefix + ": '" + field
                    + "' must be an array of column names");
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(prefix + ": '" + field
                        + "' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException(prefix + ": duplicate column '" + name
                        + "' in '" + field + "'");
            }
            names.add(name);
        }
        return names;
    }
}
