package com.dap.nativeengine.viz;

import java.util.List;
import java.util.Objects;

/**
 * One drawing primitive of a chart scene. Every coordinate is in scene pixels; the renderer
 * builds the same shapes for SVG and PNG, so both formats show the same geometry.
 */
public sealed interface Shape permits Shape.Rect, Shape.Line, Shape.Polyline, Shape.Circle,
        Shape.Label, Shape.Arc, Shape.Path {

    /** Filled rectangle: bars, heatmap cells, legend swatches, backgrounds. */
    record Rect(double x, double y, double width, double height, String fill) implements Shape {
    }

    /** Straight line: axes, gridlines, boxplot whiskers and medians. */
    record Line(double x1, double y1, double x2, double y2, String stroke, double strokeWidth)
            implements Shape {
    }

    /** Open polyline: line series. */
    record Polyline(List<double[]> points, String stroke, double strokeWidth) implements Shape {
    }

    /** Filled circle: scatter points, map bubbles, boxplot outliers. */
    record Circle(double cx, double cy, double radius, String fill) implements Shape {
    }

    /** Text label; rotation is in degrees clockwise around (x, y). */
    record Label(double x, double y, String text, String anchor, double size, String fill,
                 double rotation) implements Shape {
        public Label(double x, double y, String text, String anchor, double size, String fill) {
            this(x, y, text, anchor, size, fill, 0);
        }
    }

    /** Pie slice: angles are degrees clockwise from 12 o'clock. */
    record Arc(double cx, double cy, double radius, double startDegrees, double endDegrees,
               String fill) implements Shape {
    }

    /** Closed polygon: world map outlines. */
    record Path(List<double[]> points, String fill, String stroke, double strokeWidth)
            implements Shape {
    }

    /** Copy of {@code shape} moved by (dx, dy). */
    static Shape shift(Shape shape, double dx, double dy) {
        Objects.requireNonNull(shape, "shape");
        if (shape instanceof Rect rect) {
            return new Rect(rect.x() + dx, rect.y() + dy, rect.width(), rect.height(), rect.fill());
        }
        if (shape instanceof Line line) {
            return new Line(line.x1() + dx, line.y1() + dy, line.x2() + dx, line.y2() + dy,
                    line.stroke(), line.strokeWidth());
        }
        if (shape instanceof Polyline polyline) {
            return new Polyline(shift(polyline.points(), dx, dy), polyline.stroke(),
                    polyline.strokeWidth());
        }
        if (shape instanceof Circle circle) {
            return new Circle(circle.cx() + dx, circle.cy() + dy, circle.radius(), circle.fill());
        }
        if (shape instanceof Label label) {
            return new Label(label.x() + dx, label.y() + dy, label.text(), label.anchor(),
                    label.size(), label.fill(), label.rotation());
        }
        if (shape instanceof Arc arc) {
            return new Arc(arc.cx() + dx, arc.cy() + dy, arc.radius(), arc.startDegrees(),
                    arc.endDegrees(), arc.fill());
        }
        Path path = (Path) shape;
        return new Path(shift(path.points(), dx, dy), path.fill(), path.stroke(),
                path.strokeWidth());
    }

    private static List<double[]> shift(List<double[]> points, double dx, double dy) {
        return points.stream()
                .map(point -> new double[]{point[0] + dx, point[1] + dy})
                .toList();
    }
}
