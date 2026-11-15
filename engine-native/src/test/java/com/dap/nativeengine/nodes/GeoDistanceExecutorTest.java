package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dap.geo_distance: haversine distance between two coordinate pairs. */
class GeoDistanceExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("lat", "Float64"), ColumnSpec.of("lng", "Float64"),
            ColumnSpec.of("lat2", "Float64"), ColumnSpec.of("lng2", "Float64"));

    private static LazyPlan distance(DuckDbSession db, List<Object[]> rows, String config)
            throws Exception {
        LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows);
        NodeContext context = NodeTestSupport.context(db, config);
        return new GeoDistanceExecutor().execute(context, List.of(input));
    }

    private static List<Object[]> parisLondon() {
        // Paris (48.8566, 2.3522) - London (51.5074, -0.1278)
        return List.<Object[]>of(new Object[]{48.8566, 2.3522, 51.5074, -0.1278});
    }

    @Test
    void parisToLondonIsAbout343Kilometres() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = distance(db, parisLondon(), """
                    {"lat":"lat","lng":"lng","lat2":"lat2","lng2":"lng2","unit":"km"}
                    """);

            assertEquals(List.of(ColumnSpec.of("lat", "Float64"), ColumnSpec.of("lng", "Float64"),
                    ColumnSpec.of("lat2", "Float64"), ColumnSpec.of("lng2", "Float64"),
                    ColumnSpec.of("distance", "Float64")), plan.columns());
            double km = Double.parseDouble(plan.preview(10).data().get(0).get(4));
            assertTrue(km > 343.5 * 0.99 && km < 343.5 * 1.01, "distance was " + km + " km");
        }
    }

    @Test
    void milesAreConvertedFromKilometres() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = distance(db, parisLondon(), """
                    {"lat":"lat","lng":"lng","lat2":"lat2","lng2":"lng2","unit":"mi"}
                    """);

            double miles = Double.parseDouble(plan.preview(10).data().get(0).get(4));
            assertTrue(miles > 343.5 * 0.99 * 0.621371 && miles < 343.5 * 1.01 * 0.621371,
                    "distance was " + miles + " mi");
        }
    }

    @Test
    void outputNameIsConfigurable() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = distance(db, parisLondon(), """
                    {"lat":"lat","lng":"lng","lat2":"lat2","lng2":"lng2","output":"km_apart"}
                    """);

            assertEquals("km_apart", plan.columns().get(4).name());
        }
    }

    @Test
    void unknownColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> distance(db, parisLondon(), """
                            {"lat":"nope","lng":"lng","lat2":"lat2","lng2":"lng2"}
                            """));
            assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
        }
    }

    @Test
    void nonNumericCoordinateIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("lat", "Utf8"), ColumnSpec.of("lng", "Float64"),
                            ColumnSpec.of("lat2", "Float64"), ColumnSpec.of("lng2", "Float64")),
                    List.<Object[]>of(new Object[]{"x", 1.0, 2.0, 3.0}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"lat":"lat","lng":"lng","lat2":"lat2","lng2":"lng2"}
                    """);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new GeoDistanceExecutor().execute(context, List.of(input)));
            assertTrue(failure.getMessage().contains("lat"), failure.getMessage());
        }
    }

    @Test
    void unknownUnitIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> distance(db, parisLondon(), """
                            {"lat":"lat","lng":"lng","lat2":"lat2","lng2":"lng2","unit":"miles"}
                            """));
            assertTrue(failure.getMessage().contains("unit"), failure.getMessage());
        }
    }

    @Test
    void missingInputIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, "{}");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new GeoDistanceExecutor().execute(context, List.of()));
            assertTrue(failure.getMessage().contains("input"), failure.getMessage());
        }
    }
}
