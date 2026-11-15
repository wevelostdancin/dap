package com.dap.nativeengine.viz;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.TableData;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Turns a {@link ChartSpec} plus a table preview into a {@link Scene} and paints that scene as
 * deterministic SVG or as a Java2D PNG. The same scene drives both formats, so geometry,
 * titles, labels and annotations always agree; only the raster back end differs.
 */
public final class ChartRenderer {
    static {
        // Offscreen Java2D must never need a display: the engine runs on servers and in CI.
        System.setProperty("java.awt.headless", "true");
    }

    private static final String SVG_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String[] PALETTE = {
            "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2", "#7f7f7f"};
    private static final String BACKGROUND = "#ffffff";
    private static final String AXIS = "#333333";
    private static final String GRID = "#e5e5e5";
    private static final String TEXT = "#333333";
    private static final String TITLE_TEXT = "#111111";
    private static final String ANNOTATION = "#d62728";

    private static final double TICK_SIZE = 11;
    private static final double TITLE_SIZE = 16;
    private static final double SUBTITLE_SIZE = 12;
    private static final double AXIS_TITLE_SIZE = 12;
    private static final double PANEL_TITLE_SIZE = 12;
    private static final double DATA_LABEL_SIZE = 10;
    private static final double LEGEND_SIZE = 11;
    private static final double MARGIN_LEFT = 56;
    private static final double MARGIN_RIGHT = 24;
    private static final double MARGIN_TOP = 14;
    private static final double MARGIN_BOTTOM = 44;
    private static final int Y_TICKS = 5;

    private ChartRenderer() {
    }

    // ------------------------------------------------------------------ scene

    /** Build the scene for one chart; facets and grids call this per panel. */
    public static Scene render(ChartSpec spec, List<ColumnSpec> columns, TableData data) {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Shape.Rect(0, 0, spec.width(), spec.height(), BACKGROUND));
        int top = header(shapes, spec.title(), spec.subtitle(), spec.width());
        Frame frame = new Frame(0, top, spec.width(), spec.height() - top);
        if (spec.faceted()) {
            shapes.addAll(facets(spec, columns, data.data(), frame));
        } else {
            shapes.addAll(panel(spec, columns, data.data(), frame, null));
        }
        return new Scene(spec.width(), spec.height(), titleOf(spec), shapes);
    }

    /** Compose already rendered panels into one grid canvas with a shared title. */
    public static Scene grid(List<Scene> panels, int columns, int width, int height, int header,
                             String title) {
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Shape.Rect(0, 0, width, height, BACKGROUND));
        if (title != null && !title.isBlank()) {
            shapes.add(new Shape.Label(width / 2.0, header - 12, title, "middle", TITLE_SIZE,
                    TITLE_TEXT));
        }
        int rows = Math.max(1, (panels.size() + columns - 1) / columns);
        double cellWidth = (double) width / columns;
        double cellHeight = (double) (height - header) / rows;
        for (int index = 0; index < panels.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            Scene shifted = panels.get(index).shifted(column * cellWidth, header + row * cellHeight);
            shapes.addAll(shifted.shapes());
        }
        String accessible = title == null || title.isBlank() ? "charts" : title;
        return new Scene(width, height, accessible, shapes);
    }

    private static int header(List<Shape> shapes, String title, String subtitle, double width) {
        int top = 0;
        if (title != null && !title.isBlank()) {
            shapes.add(new Shape.Label(width / 2.0, 28, title, "middle", TITLE_SIZE, TITLE_TEXT));
            top = 38;
        }
        if (subtitle != null && !subtitle.isBlank()) {
            shapes.add(new Shape.Label(width / 2.0, top + 14, subtitle, "middle", SUBTITLE_SIZE,
                    TEXT));
            top += 22;
        }
        return top;
    }

    private static List<Shape> facets(ChartSpec spec, List<ColumnSpec> columns,
                                      List<List<String>> rows, Frame frame) {
        int facetIndex = indexOf(columns, spec.facet());
        Map<String, List<List<String>>> groups = new LinkedHashMap<>();
        for (List<String> row : rows) {
            groups.computeIfAbsent(cell(row, facetIndex), key -> new ArrayList<>()).add(row);
        }
        List<String> values = categoryOrder(groups.keySet());
        int columnsCount = Math.max(1, Math.min(spec.facetColumns(), Math.max(1, values.size())));
        int rowCount = Math.max(1, (values.size() + columnsCount - 1) / columnsCount);
        double cellWidth = frame.width() / columnsCount;
        double cellHeight = frame.height() / rowCount;
        List<Shape> shapes = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            int column = index % columnsCount;
            int row = index / columnsCount;
            Frame cell = new Frame(frame.left() + column * cellWidth, frame.top() + row * cellHeight,
                    cellWidth, cellHeight);
            shapes.addAll(panel(spec, columns, groups.get(values.get(index)), cell,
                    values.get(index)));
        }
        return shapes;
    }

    private static List<Shape> panel(ChartSpec spec, List<ColumnSpec> columns,
                                     List<List<String>> rows, Frame frame, String panelTitle) {
        List<Shape> shapes = new ArrayList<>();
        Frame body = frame;
        if (panelTitle != null) {
            shapes.add(new Shape.Label(frame.left() + frame.width() / 2, frame.top() + 16,
                    panelTitle, "middle", PANEL_TITLE_SIZE, TITLE_TEXT));
            body = new Frame(frame.left(), frame.top() + 24, frame.width(), frame.height() - 24);
        }
        switch (spec.type()) {
            case "bar" -> bar(spec, columns, rows, body, shapes);
            case "line", "scatter" -> points(spec, columns, rows, body, shapes);
            case "histogram" -> histogram(spec, columns, rows, body, shapes);
            case "pie" -> pie(spec, columns, rows, body, shapes);
            case "boxplot" -> boxplot(spec, columns, rows, body, shapes);
            case "heatmap" -> heatmap(spec, columns, rows, body, shapes);
            default -> throw new IllegalArgumentException("chart: unsupported type '" + spec.type()
                    + "'");
        }
        return shapes;
    }

    // ------------------------------------------------------------------ layout

    private record Frame(double left, double top, double width, double height) {
    }

    private record Plot(double left, double top, double width, double height) {
        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }

        double px(double fraction) {
            return left + width * fraction;
        }

        double py(double fraction) {
            return top + height * fraction;
        }
    }

    private static Plot plot(Frame frame, boolean yTitle, boolean xTitle, boolean topLegend,
                             double rightExtra) {
        double left = MARGIN_LEFT + (yTitle ? 20 : 0);
        double right = MARGIN_RIGHT + rightExtra;
        double top = MARGIN_TOP + (topLegend ? 20 : 0);
        double bottom = MARGIN_BOTTOM + (xTitle ? 16 : 0);
        double width = Math.max(16, frame.width() - left - right);
        double height = Math.max(16, frame.height() - top - bottom);
        return new Plot(frame.left() + left, frame.top() + top, width, height);
    }

    /** One numeric axis: raw data bounds, an optional log10 transform and the tick step. */
    private record Axis(double min, double max, boolean log, double step) {
        double t(double value) {
            return log ? Math.log10(value) : value;
        }

        double fraction(double value) {
            double span = t(max) - t(min);
            if (Math.abs(span) < 1e-12) {
                return 0.5;
            }
            return (t(value) - t(min)) / span;
        }
    }

    private static Axis linearAxis(double dataMin, double dataMax, boolean includeZero) {
        double min = includeZero ? Math.min(0, dataMin) : dataMin;
        double max = includeZero ? Math.max(0, dataMax) : dataMax;
        if (max - min < 1e-12) {
            max = min + 1;
        }
        double span = max - min;
        double lower = min;
        double upper = max;
        if (includeZero && dataMin >= 0) {
            // Bars start at 0, so only the top needs 5% headroom above the tallest bar.
            upper = max + span * 0.05;
        } else {
            lower = min - span * 0.05;
            upper = max + span * 0.05;
        }
        return snapped(lower, upper, dataMin >= 0);
    }

    private static Axis paddedAxis(double dataMin, double dataMax) {
        if (dataMax - dataMin < 1e-12) {
            dataMin -= 1;
            dataMax += 1;
        }
        double pad = (dataMax - dataMin) * 0.05;
        return snapped(dataMin - pad, dataMax + pad, dataMin >= 0);
    }

    /**
     * Widen a padded domain to whole tick steps: ticks then land inside the plot and the top
     * tick is always above the largest value (the headroom is never lost to tick rounding).
     */
    private static Axis snapped(double lower, double upper, boolean keepNonNegative) {
        double step = niceStep((upper - lower) / Y_TICKS);
        double min = Math.floor(lower / step + 1e-9) * step;
        if (keepNonNegative && min < 0) {
            min = 0;
        }
        return new Axis(min, Math.ceil(upper / step - 1e-9) * step, false, step);
    }

    private static Axis logAxis(double dataMin, double dataMax, String axisName, String column) {
        if (dataMin <= 0) {
            throw new IllegalArgumentException("chart: " + axisName
                    + " requires positive values, column '" + column + "' contains "
                    + format(dataMin));
        }
        double low = Math.log10(dataMin);
        double high = Math.log10(dataMax);
        if (high - low < 1e-9) {
            low -= 0.5;
            high += 0.5;
        }
        double pad = (high - low) * 0.05;
        return new Axis(Math.pow(10, low - pad), Math.pow(10, high + pad), true, 0);
    }

    private static Axis valueAxis(ChartSpec spec, double dataMin, double dataMax,
                                  boolean includeZero, String column) {
        return spec.logY() ? logAxis(dataMin, dataMax, "log_y", column)
                : linearAxis(dataMin, dataMax, includeZero);
    }

    private static List<Double> ticks(Axis axis) {
        if (axis.log()) {
            return logTicks(axis);
        }
        double span = axis.max() - axis.min();
        double step = axis.step();
        double first = Math.ceil(axis.min() / step - 1e-9) * step;
        double last = Math.floor(axis.max() / step + 1e-9) * step;
        List<Double> values = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            double value = first + step * index;
            if (value > last + step * 1e-9) {
                break;
            }
            double rounded = Math.round(value / step) * step;
            values.add(Math.abs(rounded) < step * 1e-9 ? 0 : rounded);
        }
        if (values.size() < 2) {
            values.clear();
            for (int index = 0; index <= Y_TICKS; index++) {
                values.add(axis.min() + span * index / Y_TICKS);
            }
        }
        return values;
    }

    private static List<Double> logTicks(Axis axis) {
        List<Double> values = new ArrayList<>();
        double low = Math.log10(axis.min());
        double high = Math.log10(axis.max());
        for (int exponent = (int) Math.floor(low); exponent <= (int) Math.ceil(high); exponent++) {
            for (int mantissa : new int[]{1, 2, 5}) {
                double value = mantissa * Math.pow(10, exponent);
                if (value >= axis.min() * (1 - 1e-9) && value <= axis.max() * (1 + 1e-9)) {
                    values.add(value);
                }
            }
        }
        if (values.size() < 2) {
            values.clear();
            values.add(axis.min());
            values.add(axis.max());
        }
        return values;
    }

    private static double niceStep(double raw) {
        double exponent = Math.floor(Math.log10(raw));
        double base = Math.pow(10, exponent);
        double fraction = raw / base;
        double nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
        return nice * base;
    }

    // ------------------------------------------------------------------ axes

    private static void yTicks(List<Shape> shapes, Plot plot, Axis axis, boolean grid) {
        for (double value : ticks(axis)) {
            double y = plot.py(1 - axis.fraction(value));
            if (grid) {
                shapes.add(new Shape.Line(plot.left(), y, plot.right(), y, GRID, 1));
            }
            shapes.add(new Shape.Label(plot.left() - 8, y + 4, format(value), "end", TICK_SIZE,
                    TEXT));
        }
    }

    private static void xTicks(List<Shape> shapes, Plot plot, Axis axis) {
        for (double value : ticks(axis)) {
            shapes.add(new Shape.Label(plot.px(axis.fraction(value)), plot.bottom() + 16,
                    format(value), "middle", TICK_SIZE, TEXT));
        }
    }

    private static void axes(List<Shape> shapes, Plot plot) {
        shapes.add(new Shape.Line(plot.left(), plot.top(), plot.left(), plot.bottom(), AXIS, 1));
        shapes.add(new Shape.Line(plot.left(), plot.bottom(), plot.right(), plot.bottom(), AXIS, 1));
    }

    private static void axisTitles(List<Shape> shapes, ChartSpec spec, Plot plot) {
        String x = axisTitle(spec.xLabel(), spec.xUnit());
        if (x != null) {
            shapes.add(new Shape.Label(plot.left() + plot.width() / 2, plot.bottom() + 34, x,
                    "middle", AXIS_TITLE_SIZE, TEXT));
        }
        String y = axisTitle(spec.yLabel(), spec.yUnit());
        if (y != null) {
            shapes.add(new Shape.Label(plot.left() - 46, plot.top() + plot.height() / 2, y,
                    "middle", AXIS_TITLE_SIZE, TEXT, -90));
        }
    }

    private static String axisTitle(String label, String unit) {
        boolean hasLabel = label != null && !label.isBlank();
        boolean hasUnit = unit != null && !unit.isBlank();
        if (hasLabel && hasUnit) {
            return label + " (" + unit + ")";
        }
        return hasLabel ? label : hasUnit ? unit : null;
    }

    private static boolean hasXTitle(ChartSpec spec) {
        return axisTitle(spec.xLabel(), spec.xUnit()) != null;
    }

    private static boolean hasYTitle(ChartSpec spec) {
        return axisTitle(spec.yLabel(), spec.yUnit()) != null;
    }

    private static void legend(List<Shape> shapes, Plot plot, List<String> names) {
        if (names.size() < 2) {
            return;
        }
        double x = plot.left();
        double y = plot.top() - 10;
        for (int index = 0; index < names.size(); index++) {
            shapes.add(new Shape.Rect(x, y - 9, 10, 10, PALETTE[index % PALETTE.length]));
            shapes.add(new Shape.Label(x + 14, y, names.get(index), "start", LEGEND_SIZE, TEXT));
            x += 14 + names.get(index).length() * LEGEND_SIZE * 0.6 + 16;
        }
    }

    private static void annotations(List<Shape> shapes, ChartSpec spec, Plot plot, Axis xAxis,
                                    Axis yAxis, List<String> categories) {
        int stacked = 0;
        for (ChartSpec.Annotation annotation : spec.annotations()) {
            String color = annotation.color() == null ? ANNOTATION : annotation.color();
            double x;
            double y;
            String anchor;
            if (annotation.x() != null && annotation.y() != null && xAxis != null) {
                x = plot.px(xAxis.fraction(annotation.x()));
                y = plot.py(1 - yAxis.fraction(annotation.y()));
                anchor = "middle";
            } else if (annotation.category() != null && categories != null
                    && categories.contains(annotation.category())) {
                int index = categories.indexOf(annotation.category());
                double slot = plot.width() / categories.size();
                x = plot.left() + slot * (index + 0.5);
                y = annotation.y() != null && yAxis != null
                        ? plot.py(1 - yAxis.fraction(annotation.y()))
                        : plot.top() + 16;
                anchor = "middle";
            } else {
                x = plot.left() + 12;
                y = plot.top() + 18 + stacked * 16;
                stacked++;
                anchor = "start";
            }
            shapes.add(new Shape.Label(x, y, annotation.text(), anchor, AXIS_TITLE_SIZE, color));
        }
    }

    // ------------------------------------------------------------------ bar

    private static void bar(ChartSpec spec, List<ColumnSpec> columns, List<List<String>> rows,
                            Frame frame, List<Shape> shapes) {
        int xIndex = indexOf(columns, spec.x());
        int yIndex = indexOf(columns, spec.y().get(0));
        int seriesIndex = spec.series() == null ? -1 : indexOf(columns, spec.series());
        Map<String, Map<String, Double>> sums = new LinkedHashMap<>();
        for (List<String> row : rows) {
            String category = cell(row, xIndex);
            Double value = number(cell(row, yIndex));
            if (value == null) {
                continue;
            }
            String group = seriesIndex < 0 ? "" : cell(row, seriesIndex);
            sums.computeIfAbsent(category, key -> new LinkedHashMap<>())
                    .merge(group, value, Double::sum);
        }
        List<String> groups = new ArrayList<>(new LinkedHashSet<>(sums.values().stream()
                .flatMap(values -> values.keySet().stream()).toList()));
        groups = categoryOrder(groups);
        List<String> categories = order(spec, new ArrayList<>(sums.keySet()), category -> {
            double total = 0;
            for (double value : sums.get(category).values()) {
                total += value;
            }
            return total;
        });
        double dataMin = Double.POSITIVE_INFINITY;
        double dataMax = Double.NEGATIVE_INFINITY;
        for (Map<String, Double> values : sums.values()) {
            for (double value : values.values()) {
                dataMin = Math.min(dataMin, value);
                dataMax = Math.max(dataMax, value);
            }
        }
        if (dataMin > dataMax) {
            dataMin = 0;
            dataMax = 0;
        }
        Axis yAxis = valueAxis(spec, dataMin, dataMax, true, spec.y().get(0));
        Plot plot = plot(frame, hasYTitle(spec), hasXTitle(spec), groups.size() > 1, 0);
        yTicks(shapes, plot, yAxis, spec.grid());
        axes(shapes, plot);
        double baseline = spec.logY() ? plot.bottom() : plot.py(1 - yAxis.fraction(0));
        double slot = categories.isEmpty() ? plot.width() : plot.width() / categories.size();
        double barWidth = slot * 0.8 / Math.max(1, groups.size());
        int step = categoryStep(categories.size());
        for (int index = 0; index < categories.size(); index++) {
            String category = categories.get(index);
            double centre = plot.left() + slot * (index + 0.5);
            if (index % step == 0) {
                shapes.add(new Shape.Label(centre, plot.bottom() + 16, category, "middle",
                        TICK_SIZE, TEXT));
            }
            Map<String, Double> groupValues = sums.get(category);
            for (int group = 0; group < groups.size(); group++) {
                Double value = groupValues.get(groups.get(group));
                if (value == null) {
                    continue;
                }
                double x = plot.left() + slot * index + slot * 0.1 + barWidth * group;
                double y = plot.py(1 - yAxis.fraction(value));
                shapes.add(new Shape.Rect(x, Math.min(y, baseline), barWidth,
                        Math.abs(baseline - y), PALETTE[group % PALETTE.length]));
                if (spec.dataLabels()) {
                    double labelY = value >= 0 ? Math.min(y, baseline) - 4 : Math.max(y, baseline) + 12;
                    shapes.add(new Shape.Label(x + barWidth / 2, labelY, format(value), "middle",
                            DATA_LABEL_SIZE, TEXT));
                }
            }
        }
        axisTitles(shapes, spec, plot);
        legend(shapes, plot, groups);
        annotations(shapes, spec, plot, null, yAxis, categories);
    }

    private static int categoryStep(int count) {
        return count <= 12 ? 1 : (int) Math.ceil(count / 12.0);
    }

    // ------------------------------------------------------------------ line / scatter

    private static void points(ChartSpec spec, List<ColumnSpec> columns, List<List<String>> rows,
                               Frame frame, List<Shape> shapes) {
        int xIndex = indexOf(columns, spec.x());
        int seriesIndex = spec.series() == null ? -1 : indexOf(columns, spec.series());
        List<String> names = new ArrayList<>();
        Map<String, List<double[]>> series = new LinkedHashMap<>();
        if (seriesIndex < 0) {
            for (String y : spec.y()) {
                int yIndex = indexOf(columns, y);
                List<double[]> points = new ArrayList<>();
                for (List<String> row : rows) {
                    Double x = number(cell(row, xIndex));
                    Double value = number(cell(row, yIndex));
                    if (x != null && value != null) {
                        points.add(new double[]{x, value});
                    }
                }
                names.add(y);
                series.put(y, points);
            }
        } else {
            int yIndex = indexOf(columns, spec.y().get(0));
            for (List<String> row : rows) {
                Double x = number(cell(row, xIndex));
                Double value = number(cell(row, yIndex));
                if (x == null || value == null) {
                    continue;
                }
                series.computeIfAbsent(cell(row, seriesIndex), key -> new ArrayList<>())
                        .add(new double[]{x, value});
            }
            Map<String, List<double[]>> sorted = new LinkedHashMap<>();
            for (String name : categoryOrder(series.keySet())) {
                sorted.put(name, series.get(name));
                names.add(name);
            }
            series = sorted;
        }
        double xMin = Double.POSITIVE_INFINITY;
        double xMax = Double.NEGATIVE_INFINITY;
        double yMin = Double.POSITIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        for (List<double[]> points : series.values()) {
            points.sort(Comparator.comparingDouble(point -> point[0]));
            for (double[] point : points) {
                xMin = Math.min(xMin, point[0]);
                xMax = Math.max(xMax, point[0]);
                yMin = Math.min(yMin, point[1]);
                yMax = Math.max(yMax, point[1]);
            }
        }
        if (xMin > xMax || yMin > yMax) {
            xMin = 0;
            xMax = 1;
            yMin = 0;
            yMax = 1;
        }
        Axis xAxis = spec.logX() ? logAxis(xMin, xMax, "log_x", spec.x()) : paddedAxis(xMin, xMax);
        Axis yAxis = spec.logY() ? logAxis(yMin, yMax, "log_y", spec.y().get(0))
                : paddedAxis(yMin, yMax);
        Plot plot = plot(frame, hasYTitle(spec), hasXTitle(spec), names.size() > 1, 0);
        yTicks(shapes, plot, yAxis, spec.grid());
        xTicks(shapes, plot, xAxis);
        axes(shapes, plot);
        int colour = 0;
        for (List<double[]> points : series.values()) {
            String stroke = PALETTE[colour % PALETTE.length];
            List<double[]> projected = new ArrayList<>();
            for (double[] point : points) {
                double px = plot.px(xAxis.fraction(point[0]));
                double py = plot.py(1 - yAxis.fraction(point[1]));
                projected.add(new double[]{px, py});
                if (spec.dataLabels()) {
                    shapes.add(new Shape.Label(px + 4, py - 4, format(point[1]), "start",
                            DATA_LABEL_SIZE, TEXT));
                }
            }
            if (spec.type().equals("line")) {
                if (projected.size() > 1) {
                    shapes.add(new Shape.Polyline(projected, stroke, 2));
                }
            } else {
                for (double[] point : projected) {
                    shapes.add(new Shape.Circle(point[0], point[1], 3, stroke));
                }
            }
            colour++;
        }
        axisTitles(shapes, spec, plot);
        legend(shapes, plot, names);
        annotations(shapes, spec, plot, xAxis, yAxis, null);
    }

    // ------------------------------------------------------------------ histogram

    private static void histogram(ChartSpec spec, List<ColumnSpec> columns,
                                  List<List<String>> rows, Frame frame, List<Shape> shapes) {
        int xIndex = indexOf(columns, spec.x());
        List<Double> values = new ArrayList<>();
        for (List<String> row : rows) {
            Double value = number(cell(row, xIndex));
            if (value != null) {
                values.add(value);
            }
        }
        double minimum = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maximum = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        int bins = spec.bins();
        double width = maximum - minimum;
        if (width <= 0) {
            bins = 1;
            width = 0;
        }
        int[] counts = new int[bins];
        double binWidth = bins == 1 ? 0 : width / bins;
        for (double value : values) {
            int bin = bins == 1 ? 0 : (int) Math.floor((value - minimum) / binWidth);
            counts[Math.min(bins - 1, Math.max(0, bin))]++;
        }
        int top = 0;
        for (int count : counts) {
            top = Math.max(top, count);
        }
        Axis yAxis = linearAxis(0, top == 0 ? 1 : top, true);
        Plot plot = plot(frame, hasYTitle(spec), hasXTitle(spec), false, 0);
        yTicks(shapes, plot, yAxis, spec.grid());
        axes(shapes, plot);
        double slot = plot.width() / bins;
        int step = categoryStep(bins);
        for (int index = 0; index < bins; index++) {
            double height = plot.height() * counts[index] / (yAxis.max() - yAxis.min());
            double x = plot.left() + slot * index + slot * 0.1;
            double y = plot.bottom() - height;
            shapes.add(new Shape.Rect(x, y, slot * 0.8, height, PALETTE[0]));
            if (index % step == 0) {
                String label = bins == 1 ? format(minimum) : format(minimum + binWidth * index);
                shapes.add(new Shape.Label(plot.left() + slot * (index + 0.5), plot.bottom() + 16,
                        label, "middle", TICK_SIZE, TEXT));
            }
            if (spec.dataLabels() && counts[index] > 0) {
                shapes.add(new Shape.Label(x + slot * 0.4, y - 4, Integer.toString(counts[index]),
                        "middle", DATA_LABEL_SIZE, TEXT));
            }
        }
        axisTitles(shapes, spec, plot);
        annotations(shapes, spec, plot, null, yAxis, null);
    }

    // ------------------------------------------------------------------ pie

    private static void pie(ChartSpec spec, List<ColumnSpec> columns, List<List<String>> rows,
                            Frame frame, List<Shape> shapes) {
        int xIndex = indexOf(columns, spec.x());
        int yIndex = indexOf(columns, spec.y().get(0));
        Map<String, Double> sums = new LinkedHashMap<>();
        for (List<String> row : rows) {
            Double value = number(cell(row, yIndex));
            if (value == null) {
                continue;
            }
            if (value < 0) {
                throw new IllegalArgumentException("chart: pie requires non-negative values, column '"
                        + spec.y().get(0) + "' contains " + format(value));
            }
            sums.merge(cell(row, xIndex), value, Double::sum);
        }
        List<String> categories = order(spec, new ArrayList<>(sums.keySet()),
                category -> sums.get(category));
        double total = 0;
        for (double value : sums.values()) {
            total += value;
        }
        if (total <= 0) {
            throw new IllegalArgumentException("chart: pie requires a positive total, got "
                    + format(total));
        }
        Plot plot = plot(frame, false, false, false, 0);
        double pieWidth = plot.width() * 0.58;
        double radius = Math.max(8, Math.min(pieWidth, plot.height()) / 2 - 6);
        double cx = plot.left() + pieWidth / 2;
        double cy = plot.top() + plot.height() / 2;
        double angle = 0;
        for (int index = 0; index < categories.size(); index++) {
            String category = categories.get(index);
            double share = sums.get(category) / total;
            double end = angle + share * 360;
            String colour = PALETTE[index % PALETTE.length];
            if (end - angle >= 359.999) {
                shapes.add(new Shape.Circle(cx, cy, radius, colour));
            } else {
                shapes.add(new Shape.Arc(cx, cy, radius, angle, end, colour));
            }
            if (spec.dataLabels() && share >= 0.03) {
                double middle = Math.toRadians((angle + end) / 2);
                shapes.add(new Shape.Label(cx + radius * 0.62 * Math.sin(middle),
                        cy - radius * 0.62 * Math.cos(middle) + 4, percent(share), "middle",
                        DATA_LABEL_SIZE, "#ffffff"));
            }
            angle = end;
        }
        double legendX = plot.left() + pieWidth + 16;
        double legendTop = plot.top() + 20;
        for (int index = 0; index < categories.size(); index++) {
            String category = categories.get(index);
            shapes.add(new Shape.Rect(legendX, legendTop - 9 + index * 20, 10, 10,
                    PALETTE[index % PALETTE.length]));
            shapes.add(new Shape.Label(legendX + 14, legendTop + index * 20,
                    category + " " + percent(sums.get(category) / total), "start", LEGEND_SIZE,
                    TEXT));
        }
        annotations(shapes, spec, plot, null, null, categories);
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    // ------------------------------------------------------------------ boxplot

    private record Box(double q1, double median, double q3, double low, double high,
                       List<Double> outliers) {
    }

    private static void boxplot(ChartSpec spec, List<ColumnSpec> columns, List<List<String>> rows,
                                Frame frame, List<Shape> shapes) {
        int xIndex = indexOf(columns, spec.x());
        int yIndex = indexOf(columns, spec.y().get(0));
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        for (List<String> row : rows) {
            Double value = number(cell(row, yIndex));
            if (value == null) {
                continue;
            }
            groups.computeIfAbsent(cell(row, xIndex), key -> new ArrayList<>()).add(value);
        }
        List<String> categories = order(spec, new ArrayList<>(groups.keySet()),
                category -> median(groups.get(category)));
        Map<String, Box> boxes = new LinkedHashMap<>();
        double dataMin = Double.POSITIVE_INFINITY;
        double dataMax = Double.NEGATIVE_INFINITY;
        for (String category : categories) {
            boxes.put(category, box(groups.get(category)));
            for (double value : groups.get(category)) {
                dataMin = Math.min(dataMin, value);
                dataMax = Math.max(dataMax, value);
            }
        }
        if (dataMin > dataMax) {
            dataMin = 0;
            dataMax = 1;
        }
        Axis yAxis = valueAxis(spec, dataMin, dataMax, false, spec.y().get(0));
        Plot plot = plot(frame, hasYTitle(spec), hasXTitle(spec), false, 0);
        yTicks(shapes, plot, yAxis, spec.grid());
        axes(shapes, plot);
        double slot = categories.isEmpty() ? plot.width() : plot.width() / categories.size();
        double boxWidth = Math.min(60, slot * 0.5);
        int step = categoryStep(categories.size());
        for (int index = 0; index < categories.size(); index++) {
            String category = categories.get(index);
            Box box = boxes.get(category);
            double centre = plot.left() + slot * (index + 0.5);
            if (index % step == 0) {
                shapes.add(new Shape.Label(centre, plot.bottom() + 16, category, "middle",
                        TICK_SIZE, TEXT));
            }
            double yQ1 = plot.py(1 - yAxis.fraction(box.q1()));
            double yQ3 = plot.py(1 - yAxis.fraction(box.q3()));
            double yMedian = plot.py(1 - yAxis.fraction(box.median()));
            double yLow = plot.py(1 - yAxis.fraction(box.low()));
            double yHigh = plot.py(1 - yAxis.fraction(box.high()));
            shapes.add(new Shape.Rect(centre - boxWidth / 2, yQ3, boxWidth,
                    Math.max(1, yQ1 - yQ3), PALETTE[0]));
            shapes.add(new Shape.Line(centre - boxWidth / 2, yMedian, centre + boxWidth / 2,
                    yMedian, AXIS, 2));
            shapes.add(new Shape.Line(centre, yQ1, centre, yLow, AXIS, 1));
            shapes.add(new Shape.Line(centre, yQ3, centre, yHigh, AXIS, 1));
            double cap = boxWidth * 0.25;
            shapes.add(new Shape.Line(centre - cap, yLow, centre + cap, yLow, AXIS, 1));
            shapes.add(new Shape.Line(centre - cap, yHigh, centre + cap, yHigh, AXIS, 1));
            for (double outlier : box.outliers()) {
                shapes.add(new Shape.Circle(centre, plot.py(1 - yAxis.fraction(outlier)), 2.5,
                        TEXT));
            }
            if (spec.dataLabels()) {
                shapes.add(new Shape.Label(centre, yQ3 - 4, format(box.median()), "middle",
                        DATA_LABEL_SIZE, TEXT));
            }
        }
        axisTitles(shapes, spec, plot);
        annotations(shapes, spec, plot, null, yAxis, categories);
    }

    private static Box box(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double q1 = quantile(sorted, 0.25);
        double median = quantile(sorted, 0.5);
        double q3 = quantile(sorted, 0.75);
        double iqr = q3 - q1;
        double lowFence = q1 - 1.5 * iqr;
        double highFence = q3 + 1.5 * iqr;
        double low = q1;
        double high = q3;
        List<Double> outliers = new ArrayList<>();
        for (double value : sorted) {
            if (value < lowFence || value > highFence) {
                outliers.add(value);
            } else {
                low = Math.min(low, value);
                high = Math.max(high, value);
            }
        }
        return new Box(q1, median, q3, low, high, outliers);
    }

    private static double quantile(List<Double> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double index = fraction * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = Math.min(sorted.size() - 1, lower + 1);
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (index - lower);
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        return quantile(sorted, 0.5);
    }

    // ------------------------------------------------------------------ heatmap

    private static void heatmap(ChartSpec spec, List<ColumnSpec> columns, List<List<String>> rows,
                                Frame frame, List<Shape> shapes) {
        int xIndex = indexOf(columns, spec.x());
        int seriesIndex = indexOf(columns, spec.series());
        int yIndex = indexOf(columns, spec.y().get(0));
        Map<String, Map<String, double[]>> cells = new LinkedHashMap<>();
        for (List<String> row : rows) {
            Double value = number(cell(row, yIndex));
            if (value == null) {
                continue;
            }
            double[] cell = cells.computeIfAbsent(cell(row, xIndex), key -> new LinkedHashMap<>())
                    .computeIfAbsent(cell(row, seriesIndex), key -> new double[2]);
            cell[0] += value;
            cell[1] += 1;
        }
        List<String> xs = order(spec, new ArrayList<>(cells.keySet()), category -> {
            double total = 0;
            for (double[] cell : cells.get(category).values()) {
                total += cell[0] / cell[1];
            }
            return total;
        });
        Set<String> seriesNames = new LinkedHashSet<>();
        cells.values().forEach(map -> seriesNames.addAll(map.keySet()));
        List<String> ys = categoryOrder(seriesNames);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (Map<String, double[]> map : cells.values()) {
            for (double[] cell : map.values()) {
                double mean = cell[0] / cell[1];
                minimum = Math.min(minimum, mean);
                maximum = Math.max(maximum, mean);
            }
        }
        if (minimum > maximum) {
            minimum = 0;
            maximum = 1;
        }
        Plot plot = plot(frame, hasYTitle(spec), hasXTitle(spec), false, 96);
        double cellWidth = plot.width() / Math.max(1, xs.size());
        double cellHeight = plot.height() / Math.max(1, ys.size());
        for (int row = 0; row < ys.size(); row++) {
            shapes.add(new Shape.Label(plot.left() - 8, plot.top() + cellHeight * (row + 0.5) + 4,
                    ys.get(row), "end", TICK_SIZE, TEXT));
            for (int column = 0; column < xs.size(); column++) {
                double[] cell = cells.get(xs.get(column)).get(ys.get(row));
                if (cell == null) {
                    continue;
                }
                double mean = cell[0] / cell[1];
                double fraction = maximum - minimum < 1e-12 ? 0.5
                        : (mean - minimum) / (maximum - minimum);
                double x = plot.left() + cellWidth * column + 1;
                double y = plot.top() + cellHeight * row + 1;
                shapes.add(new Shape.Rect(x, y, Math.max(1, cellWidth - 2),
                        Math.max(1, cellHeight - 2), heatColour(fraction)));
                if (spec.dataLabels()) {
                    shapes.add(new Shape.Label(x + Math.max(1, cellWidth - 2) / 2,
                            y + cellHeight / 2 + 4, format(mean), "middle", DATA_LABEL_SIZE,
                            fraction > 0.55 ? "#ffffff" : TEXT));
                }
            }
        }
        int step = categoryStep(xs.size());
        for (int column = 0; column < xs.size(); column++) {
            if (column % step == 0) {
                shapes.add(new Shape.Label(plot.left() + cellWidth * (column + 0.5),
                        plot.bottom() + 16, xs.get(column), "middle", TICK_SIZE, TEXT));
            }
        }
        axes(shapes, plot);
        axisTitles(shapes, spec, plot);
        double legendX = plot.right() + 28;
        double legendHeight = plot.height() * 0.6;
        int steps = 5;
        for (int index = 0; index < steps; index++) {
            double fraction = 1 - (double) index / (steps - 1);
            shapes.add(new Shape.Rect(legendX, plot.top() + legendHeight * index / steps, 14,
                    legendHeight / steps, heatColour(fraction)));
        }
        shapes.add(new Shape.Label(legendX + 20, plot.top() + 8, format(maximum), "start",
                TICK_SIZE, TEXT));
        shapes.add(new Shape.Label(legendX + 20, plot.top() + legendHeight, format(minimum), "start",
                TICK_SIZE, TEXT));
        annotations(shapes, spec, plot, null, null, xs);
    }

    /** Sequential blue scale: light for the minimum, dark for the maximum. */
    private static String heatColour(double fraction) {
        double value = Math.max(0, Math.min(1, fraction));
        int[] low = {232, 241, 251};
        int[] high = {8, 81, 156};
        int red = (int) Math.round(low[0] + (high[0] - low[0]) * value);
        int green = (int) Math.round(low[1] + (high[1] - low[1]) * value);
        int blue = (int) Math.round(low[2] + (high[2] - low[2]) * value);
        return String.format(Locale.ROOT, "#%02x%02x%02x", red, green, blue);
    }

    // ------------------------------------------------------------------ svg

    /** Deterministic SVG text: rounded coordinates, no timestamps, no randomness. */
    public static String svg(Scene scene) {
        StringBuilder xml = new StringBuilder(2048);
        xml.append(SVG_DECLARATION).append('\n');
        xml.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(scene.width())
                .append("\" height=\"").append(scene.height()).append("\" viewBox=\"0 0 ")
                .append(scene.width()).append(' ').append(scene.height()).append("\">\n");
        xml.append("<title>").append(escapeXml(scene.title())).append("</title>\n");
        for (Shape shape : scene.shapes()) {
            if (shape instanceof Shape.Rect rect) {
                xml.append("<rect x=\"").append(format(rect.x())).append("\" y=\"")
                        .append(format(rect.y())).append("\" width=\"").append(format(rect.width()))
                        .append("\" height=\"").append(format(rect.height())).append("\" fill=\"")
                        .append(rect.fill()).append("\"/>\n");
            } else if (shape instanceof Shape.Line line) {
                xml.append("<line x1=\"").append(format(line.x1())).append("\" y1=\"")
                        .append(format(line.y1())).append("\" x2=\"").append(format(line.x2()))
                        .append("\" y2=\"").append(format(line.y2())).append("\" stroke=\"")
                        .append(line.stroke()).append("\" stroke-width=\"")
                        .append(format(line.strokeWidth())).append("\"/>\n");
            } else if (shape instanceof Shape.Polyline polyline) {
                xml.append("<polyline points=\"").append(points(polyline.points()))
                        .append("\" fill=\"none\" stroke=\"").append(polyline.stroke())
                        .append("\" stroke-width=\"").append(format(polyline.strokeWidth()))
                        .append("\"/>\n");
            } else if (shape instanceof Shape.Circle circle) {
                xml.append("<circle cx=\"").append(format(circle.cx())).append("\" cy=\"")
                        .append(format(circle.cy())).append("\" r=\"").append(format(circle.radius()))
                        .append("\" fill=\"").append(circle.fill()).append("\"/>\n");
            } else if (shape instanceof Shape.Arc arc) {
                xml.append("<path d=\"").append(arcPath(arc)).append("\" fill=\"")
                        .append(arc.fill()).append("\"/>\n");
            } else if (shape instanceof Shape.Path path) {
                xml.append("<path d=\"").append(points(path.points())).append("\" fill=\"")
                        .append(path.fill()).append("\" stroke=\"").append(path.stroke())
                        .append("\" stroke-width=\"").append(format(path.strokeWidth()))
                        .append("\"/>\n");
            } else if (shape instanceof Shape.Label label) {
                xml.append("<text x=\"").append(format(label.x())).append("\" y=\"")
                        .append(format(label.y())).append('"');
                if (label.rotation() != 0) {
                    xml.append(" transform=\"rotate(").append(format(label.rotation())).append(' ')
                            .append(format(label.x())).append(' ').append(format(label.y()))
                            .append(")\"");
                }
                xml.append(" text-anchor=\"").append(label.anchor())
                        .append("\" font-family=\"sans-serif\" font-size=\"")
                        .append(format(label.size())).append("\" fill=\"").append(label.fill())
                        .append("\">").append(escapeXml(label.text())).append("</text>\n");
            }
        }
        xml.append("</svg>\n");
        return xml.toString();
    }

    private static String arcPath(Shape.Arc arc) {
        double startRadians = Math.toRadians(arc.startDegrees());
        double endRadians = Math.toRadians(arc.endDegrees());
        double x1 = arc.cx() + arc.radius() * Math.sin(startRadians);
        double y1 = arc.cy() - arc.radius() * Math.cos(startRadians);
        double x2 = arc.cx() + arc.radius() * Math.sin(endRadians);
        double y2 = arc.cy() - arc.radius() * Math.cos(endRadians);
        int large = arc.endDegrees() - arc.startDegrees() > 180 ? 1 : 0;
        return "M " + format(arc.cx()) + " " + format(arc.cy())
                + " L " + format(x1) + " " + format(y1)
                + " A " + format(arc.radius()) + " " + format(arc.radius())
                + " 0 " + large + " 1 " + format(x2) + " " + format(y2) + " Z";
    }

    private static String points(List<double[]> points) {
        StringBuilder builder = new StringBuilder();
        for (double[] point : points) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(format(point[0])).append(',').append(format(point[1]));
        }
        return builder.toString();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // ------------------------------------------------------------------ png

    /** Rasterise the scene with Java2D; PNG bytes are smoke-tested, not golden-compared. */
    public static byte[] png(Scene scene) throws IOException {
        BufferedImage image = new BufferedImage(scene.width(), scene.height(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, scene.width(), scene.height());
            for (Shape shape : scene.shapes()) {
                paint(graphics, shape);
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void paint(Graphics2D graphics, Shape shape) {
        if (shape instanceof Shape.Rect rect) {
            graphics.setColor(Color.decode(rect.fill()));
            graphics.fillRect(round(rect.x()), round(rect.y()), Math.max(1, round(rect.width())),
                    Math.max(1, round(rect.height())));
        } else if (shape instanceof Shape.Line line) {
            graphics.setColor(Color.decode(line.stroke()));
            graphics.setStroke(new BasicStroke((float) line.strokeWidth()));
            graphics.drawLine(round(line.x1()), round(line.y1()), round(line.x2()),
                    round(line.y2()));
        } else if (shape instanceof Shape.Polyline polyline) {
            graphics.setColor(Color.decode(polyline.stroke()));
            graphics.setStroke(new BasicStroke((float) polyline.strokeWidth()));
            graphics.drawPolyline(xs(polyline.points()), ys(polyline.points()),
                    polyline.points().size());
        } else if (shape instanceof Shape.Circle circle) {
            graphics.setColor(Color.decode(circle.fill()));
            int radius = round(circle.radius());
            graphics.fillOval(round(circle.cx()) - radius, round(circle.cy()) - radius,
                    radius * 2, radius * 2);
        } else if (shape instanceof Shape.Arc arc) {
            graphics.setColor(Color.decode(arc.fill()));
            int radius = round(arc.radius());
            int start = (int) Math.round(90 - arc.endDegrees());
            int extent = (int) Math.round(arc.startDegrees() - arc.endDegrees());
            graphics.fillArc(round(arc.cx()) - radius, round(arc.cy()) - radius, radius * 2,
                    radius * 2, start, extent);
        } else if (shape instanceof Shape.Path path) {
            int[] xs = xs(path.points());
            int[] ys = ys(path.points());
            if (!"none".equals(path.fill())) {
                graphics.setColor(Color.decode(path.fill()));
                graphics.fillPolygon(xs, ys, xs.length);
            }
            graphics.setColor(Color.decode(path.stroke()));
            graphics.setStroke(new BasicStroke((float) path.strokeWidth()));
            graphics.drawPolygon(xs, ys, xs.length);
        } else if (shape instanceof Shape.Label label) {
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) label.size()));
            graphics.setColor(Color.decode(label.fill()));
            FontMetrics metrics = graphics.getFontMetrics();
            int width = metrics.stringWidth(label.text());
            int x = switch (label.anchor()) {
                case "middle" -> round(label.x()) - width / 2;
                case "end" -> round(label.x()) - width;
                default -> round(label.x());
            };
            if (label.rotation() != 0) {
                Graphics2D rotated = (Graphics2D) graphics.create();
                try {
                    rotated.rotate(Math.toRadians(label.rotation()), label.x(), label.y());
                    rotated.setFont(graphics.getFont());
                    rotated.setColor(Color.decode(label.fill()));
                    rotated.drawString(label.text(), x, round(label.y()));
                } finally {
                    rotated.dispose();
                }
            } else {
                graphics.drawString(label.text(), x, round(label.y()));
            }
        }
    }

    private static int[] xs(List<double[]> points) {
        int[] values = new int[points.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = round(points.get(index)[0]);
        }
        return values;
    }

    private static int[] ys(List<double[]> points) {
        int[] values = new int[points.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = round(points.get(index)[1]);
        }
        return values;
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    // ------------------------------------------------------------------ helpers

    private static String titleOf(ChartSpec spec) {
        return spec.hasTitle() ? spec.title() : "chart";
    }

    private static int indexOf(List<ColumnSpec> columns, String name) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException("chart: unknown column '" + name + "'");
    }

    private static String cell(List<String> row, int index) {
        String value = index < row.size() ? row.get(index) : null;
        return value == null ? "(null)" : value;
    }

    private static Double number(String value) {
        if (value == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Categories sort numerically when every value parses as a number, lexicographically otherwise. */
    private static List<String> categoryOrder(Collection<String> values) {
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(ChartRenderer::compareLabels);
        return sorted;
    }

    private static int compareLabels(String left, String right) {
        Double leftNumber = number(left);
        Double rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) {
            return Double.compare(leftNumber, rightNumber);
        }
        return left.compareTo(right);
    }

    private static List<String> order(ChartSpec spec, List<String> categories,
                                      ToDoubleFunction<String> value) {
        if (categories.size() < 2 || spec.sort() == ChartSpec.SortMode.NONE) {
            return categories;
        }
        Comparator<String> comparator;
        if (spec.sort() == ChartSpec.SortMode.LABEL) {
            comparator = ChartRenderer::compareLabels;
            if (spec.descending()) {
                comparator = comparator.reversed();
            }
        } else {
            comparator = (left, right) -> {
                int byValue = Double.compare(value.applyAsDouble(left), value.applyAsDouble(right));
                if (byValue != 0) {
                    return spec.descending() ? -byValue : byValue;
                }
                return compareLabels(left, right);
            };
        }
        categories.sort(comparator);
        return categories;
    }

    /** Two decimals, trailing zeros trimmed: deterministic across platforms. */
    private static String format(double value) {
        if (Math.abs(value) < 0.005) {
            return "0";
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
