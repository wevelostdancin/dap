package com.dap.nativeengine.engine;

/**
 * Column statistics for /profile. Numeric fields are null for non-numeric columns.
 * {@code distinctExact} is the exact count used by report sections where bounded;
 * {@code distinct} keeps the /profile contract (exact up to 100k distinct values,
 * sketch above that). Above the 1M-row refinement limit both use the sketch.
 * {@code stddev} is only rendered by report sections, never returned by /profile.
 */
public record ProfileColumn(String name, String type, long missing, long distinct,
                            Double min, Double max, Double mean, Double stddev,
                            long distinctExact) {
}
