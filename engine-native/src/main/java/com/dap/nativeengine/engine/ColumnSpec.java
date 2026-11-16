package com.dap.nativeengine.engine;

import java.util.Objects;

/** One output column: logical name, Arrow storage type, KNIME display type. */
public record ColumnSpec(String name, String arrowType, String knimeType) {
    public ColumnSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arrowType, "arrowType");
        Objects.requireNonNull(knimeType, "knimeType");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("empty column name");
        }
    }

    public static ColumnSpec of(String name, String arrowType) {
        String canonical = TypeMap.canonical(arrowType);
        return new ColumnSpec(name, canonical, TypeMap.knimeType(canonical));
    }

    public String duckDbType() {
        return TypeMap.duckDbType(arrowType);
    }

    public boolean numeric() {
        return TypeMap.isNumeric(arrowType);
    }
}
