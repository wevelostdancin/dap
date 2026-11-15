package com.dap.nativeengine.viz;

import com.dap.nativeengine.api.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bundled public-domain Natural Earth 1:110m country outlines. The classpath resource is a
 * compact ring list (see viz/NOTICE.txt) instead of the original GeoJSON, which keeps the jar
 * small; rings are in [longitude, latitude] degrees and are projected equirectangularly by the
 * renderer. Loading is lazy and cached for the life of the process.
 */
public final class WorldMap {
    private static final String RESOURCE = "viz/world-110m.json";
    private static volatile WorldMap instance;

    private final List<List<double[]>> rings;

    private WorldMap(List<List<double[]>> rings) {
        this.rings = List.copyOf(rings);
    }

    /** The bundled outline, parsed once. */
    public static WorldMap load() {
        WorldMap current = instance;
        if (current == null) {
            synchronized (WorldMap.class) {
                current = instance;
                if (current == null) {
                    current = parse(read());
                    instance = current;
                }
            }
        }
        return current;
    }

    /** Country outline rings as [longitude, latitude] degree pairs. */
    public List<List<double[]>> rings() {
        return rings;
    }

    /** Equirectangular projection into [0, 1] x [0, 1]; y grows towards the south pole. */
    public static double[] normalize(double longitude, double latitude) {
        return new double[]{(longitude + 180) / 360, (90 - latitude) / 180};
    }

    private static String read() {
        try (InputStream stream = WorldMap.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + RESOURCE + ": " + failure.getMessage(),
                    failure);
        }
    }

    private static WorldMap parse(String json) {
        JsonNode root;
        try {
            root = Json.parse(json);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot parse " + RESOURCE + ": " + failure.getMessage(),
                    failure);
        }
        List<List<double[]>> rings = new ArrayList<>();
        for (JsonNode ring : root.path("rings")) {
            List<double[]> points = new ArrayList<>();
            for (JsonNode point : ring) {
                if (point.size() == 2 && point.get(0).isNumber() && point.get(1).isNumber()) {
                    points.add(new double[]{point.get(0).doubleValue(), point.get(1).doubleValue()});
                }
            }
            if (points.size() >= 3) {
                rings.add(List.copyOf(points));
            }
        }
        if (rings.isEmpty()) {
            throw new IllegalStateException(RESOURCE + " contains no outline rings");
        }
        return new WorldMap(rings);
    }
}
