package com.dap.nativeengine.engine;

/**
 * Column statistics for /profile. Numeric fields are null for non-numeric columns.
 * {@code distinct} keeps the /profile contract (exact up to 100k distinct values,
 * sketch above that, refined when the estimate is small). {@code distinctExact} is
 * the count rendered by report sections; above the 1M-row limit it matches the
 * sketch for high-cardinality columns and the refined exact value for small ones.
 * {@code stddev} is only rendered by report sections, never returned by /profile.
 */
public record ProfileColumn(String name, String type, long missing, long distinct,
                            Double min, Double max, Double mean, Double stddev,
                            long distinctExact) {

    /** Same statistics without the stddev that only report sections render. */
    public ProfileColumn withoutStddev() {
        return stddev == null ? this
                : new ProfileColumn(name, type, missing, distinct, min, max, mean, null, distinctExact);
    }
}
