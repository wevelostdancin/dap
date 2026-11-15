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

/**
 * dap.geo_distance: haversine distance between two coordinate pairs, in kilometres or miles.
 * Pure SQL over the input columns; the original columns stay in place.
 */
public final class GeoDistanceExecutor implements NodeExecutor {
    /** Mean Earth radius in kilometres (IUGG). */
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final double KM_TO_MILES = 0.621371192;

    @Override
    public String nodeId() {
        return "dap.geo_distance";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String lat = coordinate(config, "lat", input.columns());
        String lng = coordinate(config, "lng", input.columns());
        String lat2 = coordinate(config, "lat2", input.columns());
        String lng2 = coordinate(config, "lng2", input.columns());
        String unit = NodeSql.text(config, "unit", "km").toLowerCase(Locale.ROOT);
        if (!"km".equals(unit) && !"mi".equals(unit)) {
            throw new IllegalArgumentException("geo_distance: 'unit' must be km or mi, got '" + unit + "'");
        }
        String output = NodeSql.text(config, "output", "distance");
        if (output.isBlank()) {
            throw new IllegalArgumentException("geo_distance: 'output' must not be empty");
        }
        double radius = "mi".equals(unit) ? EARTH_RADIUS_KM * KM_TO_MILES : EARTH_RADIUS_KM;

        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        columns.add(ColumnSpec.of(output, "Float64"));
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : input.columns()) {
            projection.add(NodeSql.quote(column.name()));
        }
        projection.add(haversine(lat, lng, lat2, lng2, radius) + " AS " + NodeSql.quote(output));
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS g";
        return new LazyPlan(context.db(), sql, columns);
    }

    private static String haversine(String lat, String lng, String lat2, String lng2, double radius) {
        String dLat = "radians(" + quote(lat2) + " - " + quote(lat) + ")";
        String dLng = "radians(" + quote(lng2) + " - " + quote(lng) + ")";
        return "2 * " + radius + " * asin(sqrt("
                + "sin(" + dLat + " / 2) * sin(" + dLat + " / 2) + "
                + "cos(radians(" + quote(lat) + ")) * cos(radians(" + quote(lat2) + ")) * "
                + "sin(" + dLng + " / 2) * sin(" + dLng + " / 2)))";
    }

    private static String coordinate(JsonNode config, String field, List<ColumnSpec> columns) {
        String name = NodeSql.requiredText(config, field);
        for (ColumnSpec column : columns) {
            if (column.name().equals(name)) {
                if (!column.numeric()) {
                    throw new IllegalArgumentException("geo_distance: coordinate column '" + name
                            + "' is " + column.arrowType() + ", not numeric");
                }
                return name;
            }
        }
        throw new IllegalArgumentException("geo_distance: unknown coordinate column '" + name + "'");
    }

    private static String quote(String identifier) {
        return "g." + NodeSql.quote(identifier);
    }
}
