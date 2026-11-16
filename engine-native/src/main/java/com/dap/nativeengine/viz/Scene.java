package com.dap.nativeengine.viz;

import java.util.List;

/** A rendered chart: canvas size, accessible title and drawing primitives in paint order. */
public record Scene(int width, int height, String title, List<Shape> shapes) {
    public Scene {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("scene size must be positive, got " + width + "x" + height);
        }
        shapes = List.copyOf(shapes);
    }

    /** Copy of this scene with every shape moved by (dx, dy), for grids and facets. */
    public Scene shifted(double dx, double dy) {
        return new Scene(width, height, title,
                shapes.stream().map(shape -> Shape.shift(shape, dx, dy)).toList());
    }
}
