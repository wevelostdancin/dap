package com.dap.nativeengine.engine;

/** Column statistics for /profile. Numeric fields are null for non-numeric columns. */
public record ProfileColumn(String name, String type, long missing, long distinct,
                            Double min, Double max, Double mean) {
}
