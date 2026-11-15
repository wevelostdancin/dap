package com.dap.nativeengine.viz;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldMapTest {
    @Test
    void bundledOutlineStaysSmallAndCoversTheWorld() throws Exception {
        try (InputStream stream = WorldMapTest.class.getClassLoader()
                .getResourceAsStream("viz/world-110m.json")) {
            assertNotNull(stream, "viz/world-110m.json must be on the classpath");
            byte[] resource = stream.readAllBytes();
            assertTrue(resource.length > 10_000, "the outline must contain real geometry");
            assertTrue(resource.length <= 300 * 1024,
                    "the bundled outline must stay under 300 KB, was " + resource.length + " bytes");
            assertTrue(new String(resource, StandardCharsets.UTF_8).contains("Natural Earth"),
                    "the resource keeps its public-domain attribution");
        }

        WorldMap map = WorldMap.load();
        assertTrue(map.rings().size() >= 100, "expected many country outlines, got "
                + map.rings().size());
        int points = map.rings().stream().mapToInt(List::size).sum();
        assertTrue(points > 5_000, "expected thousands of outline points, got " + points);
        for (List<double[]> ring : map.rings()) {
            assertTrue(ring.size() >= 3, "every ring is a closed polygon");
            for (double[] point : ring) {
                assertTrue(point[0] >= -180 && point[0] <= 180, "longitude out of range: " + point[0]);
                assertTrue(point[1] >= -90 && point[1] <= 90, "latitude out of range: " + point[1]);
            }
        }
    }

    @Test
    void equirectangularProjectionMapsTheCorners() {
        assertArrayEquals(new double[]{0, 0}, WorldMap.normalize(-180, 90));
        assertArrayEquals(new double[]{1, 1}, WorldMap.normalize(180, -90));
        assertArrayEquals(new double[]{0.5, 0.5}, WorldMap.normalize(0, 0));
        assertArrayEquals(new double[]{0.75, 0.25}, WorldMap.normalize(90, 45));
    }

    private static void assertArrayEquals(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], actual[index], 1e-9);
        }
    }
}
