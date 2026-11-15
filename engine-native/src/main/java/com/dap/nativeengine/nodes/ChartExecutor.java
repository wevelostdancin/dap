package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * dap.chart: renders bar, line, scatter and histogram charts from the input table and passes
 * the input through unchanged. One geometry is built for both formats: SVG is hand-written
 * XML (no timestamps, rounded coordinates) and is byte-deterministic, PNG is drawn with
 * Java2D offscreen and is only smoke-checked. Rows whose x/y values are NULL or not numeric
 * are skipped; bar values are summed per category and series.
 */
public final class ChartExecutor implements NodeExecutor {
    static {
        // Offscreen Java2D must never need a display: the engine runs on servers and in CI.
        System.setProperty("java.awt.headless", "true");
    }

    private static final String NODE = "chart";
    private static final String SVG_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final String[] PALETTE = {
            "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2", "#7f7f7f"};
    private static final String BACKGROUND = "#ffffff";
    private static final String AXIS = "#333333";
    private static final String GRID = "#e5e5e5";
    private static final String TEXT = "#333333";
    private static final String TITLE_TEXT = "#111111";
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 400;
    private static final int MIN_SIZE = 100;
    private static final int MAX_SIZE = 4000;
    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 100_000;
    private static final int DEFAULT_BINS = 10;
    private static final int MAX_BINS = 1000;
    private static final double MARGIN_LEFT = 64;
    private static final double MARGIN_RIGHT = 24;
    private static final double MARGIN_TOP = 24;
    private static final double MARGIN_TOP_TITLE = 48;
    private static final double MARGIN_BOTTOM = 56;
    private static final double TICK_SIZE = 11;
    private static final double TITLE_SIZE = 16;
    private static final int Y_TICKS = 5;

    @Override
    public String nodeId() {
        return "dap.chart";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        String format = NodeSql.text(config, "format", "svg");
        if (!format.equals("svg") && !format.equals("png")) {
            throw new IllegalArgumentException(NODE + ": 'format' must be svg or png, got '" + format + "'");
        }
        Chart chart = chart(config, input.columns());
        TableData data = input.preview(chart.limit(), context.workflowId(), context.nodeId());
        Scene scene = scene(chart, input.columns(), data);
        byte[] content = format.equals("svg")
                ? svg(scene).getBytes(StandardCharsets.UTF_8)
                : png(scene);
        IoNodeSupport.writeAtomically(IoNodeSupport.targetFile(file, NODE), content, NODE);
        return input;
    }

    // ------------------------------------------------------------------ config

    private record Chart(String type, String x, List<String> y, String series, String title,
                         int width, int height, int limit, int bins) {
    }

    private static Chart chart(JsonNode config, List<ColumnSpec> columns) {
        String type = NodeSql.requiredText(config, "type");
        if (!List.of("bar", "line", "scatter", "histogram").contains(type)) {
            throw new IllegalArgumentException(NODE + ": unknown 'type' '" + type
                    + "', expected bar, line, scatter or histogram");
        }
        String x = NodeSql.requiredText(config, "x");
        ColumnSpec xColumn = NodeSql.column(columns, x);
        List<String> y = columns(config, "y");
        String series = NodeSql.text(config, "series", null);
        String title = NodeSql.text(config, "title", null);
        int width = integer(config, "width", DEFAULT_WIDTH, MIN_SIZE, MAX_SIZE);
        int height = integer(config, "height", DEFAULT_HEIGHT, MIN_SIZE, MAX_SIZE);
        int limit = integer(config, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        int bins = integer(config, "bins", DEFAULT_BINS, 1, MAX_BINS);

        if (type.equals("histogram")) {
            if (!y.isEmpty()) {
                throw new IllegalArgumentException(NODE + ": histogram does not take 'y'; it bins the 'x' column");
            }
            if (!xColumn.numeric()) {
                throw new IllegalArgumentException(NODE + ": histogram 'x' column '" + x + "' must be numeric");
            }
            if (series != null) {
                throw new IllegalArgumentException(NODE + ": histogram does not take 'series'");
            }
        } else {
            if (y.isEmpty()) {
                throw new IllegalArgumentException(NODE + ": 'y' must be a non-empty array of numeric column names");
            }
            for (String name : y) {
                ColumnSpec column = NodeSql.column(columns, name);
                if (!column.numeric()) {
                    throw new IllegalArgumentException(NODE + ": 'y' column '" + name + "' must be numeric");
                }
            }
            if (type.equals("bar") && y.size() != 1) {
                throw new IllegalArgumentException(NODE + ": bar takes exactly one 'y' column, got " + y.size());
            }
            if (series != null) {
                NodeSql.column(columns, series);
                if (y.size() != 1) {
                    throw new IllegalArgumentException(NODE + ": with 'series' 'y' must contain exactly one column");
                }
            }
            if (!type.equals("bar") && !xColumn.numeric()) {
                throw new IllegalArgumentException(NODE + ": " + type + " 'x' column '" + x + "' must be numeric");
            }
        }
        return new Chart(type, x, y, series, title, width, height, limit, bins);
    }

    private static List<String> columns(JsonNode config, String field) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be an array of column names");
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(NODE + ": '" + field
                        + "' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException(NODE + ": duplicate column '" + name + "' in '" + field + "'");
            }
            names.add(name);
        }
        return names;
    }

    private static int integer(JsonNode config, String field, int fallback, int minimum, int maximum) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be an integer");
        }
        long value = node.longValue();
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be between " + minimum
                    + " and " + maximum + ", got " + value);
        }
        return (int) value;
    }

    // ------------------------------------------------------------------ scene

    private sealed interface Shape permits Rect, Line, Polyline, Circle, Label {
    }

    private record Rect(double x, double y, double width, double height, String fill) implements Shape {
    }

    private record Line(double x1, double y1, double x2, double y2, String stroke,
                        double strokeWidth) implements Shape {
    }

    private record Polyline(List<double[]> points, String stroke, double strokeWidth) implements Shape {
    }

    private record Circle(double cx, double cy, double radius, String fill) implements Shape {
    }

    private record Label(double x, double y, String text, String anchor, double size, String fill)
            implements Shape {
    }

    private record Scene(int width, int height, String title, List<Shape> shapes) {
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

    private static Scene scene(Chart chart, List<ColumnSpec> columns, TableData data) {
        Plot plot = new Plot(MARGIN_LEFT,
                chart.title() == null || chart.title().isBlank() ? MARGIN_TOP : MARGIN_TOP_TITLE,
                chart.width() - MARGIN_LEFT - MARGIN_RIGHT,
                chart.height() - (chart.title() == null || chart.title().isBlank()
                        ? MARGIN_TOP : MARGIN_TOP_TITLE) - MARGIN_BOTTOM);
        return switch (chart.type()) {
            case "bar" -> barScene(chart, columns, data, plot);
            case "histogram" -> histogramScene(chart, columns, data, plot);
            default -> pointScene(chart, columns, data, plot);
        };
    }

    private static int indexOf(List<ColumnSpec> columns, String name) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException(NODE + ": unknown column '" + name + "'");
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
    private static List<String> sortedCategories(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        boolean numeric = !sorted.isEmpty() && sorted.stream().allMatch(value -> number(value) != null);
        if (numeric) {
            sorted.sort(Comparator.comparingDouble(value -> number(value)));
        } else {
            sorted.sort(Comparator.naturalOrder());
        }
        return sorted;
    }

    private static String groupValue(List<String> row, int index) {
        String value = index < row.size() ? row.get(index) : null;
        return value == null ? "(null)" : value;
    }

    // ------------------------------------------------------------------ bar

    private static Scene barScene(Chart chart, List<ColumnSpec> columns, TableData data, Plot plot) {
        int xIndex = indexOf(columns, chart.x());
        int yIndex = indexOf(columns, chart.y().get(0));
        int seriesIndex = chart.series() == null ? -1 : indexOf(columns, chart.series());
        Map<String, Map<String, Double>> sums = new LinkedHashMap<>();
        for (List<String> row : data.data()) {
            String category = groupValue(row, xIndex);
            Double value = number(yIndex < row.size() ? row.get(yIndex) : null);
            if (value == null) {
                continue;
            }
            String group = seriesIndex < 0 ? "" : groupValue(row, seriesIndex);
            sums.computeIfAbsent(category, key -> new LinkedHashMap<>())
                    .merge(group, value, Double::sum);
        }
        List<String> categories = sortedCategories(sums.keySet());
        Set<String> groupNames = new LinkedHashSet<>();
        sums.values().forEach(groups -> groupNames.addAll(groups.keySet()));
        List<String> groups = sortedCategories(groupNames);
        double maximum = 0;
        double minimum = 0;
        for (Map<String, Double> groupsOfCategory : sums.values()) {
            for (double value : groupsOfCategory.values()) {
                maximum = Math.max(maximum, value);
                minimum = Math.min(minimum, value);
            }
        }
        double[] range = bounds(minimum, maximum);
        double baseline = plot.py((range[1] - 0) / (range[1] - range[0]));
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Rect(0, 0, chart.width(), chart.height(), BACKGROUND));
        yAxis(shapes, plot, range[0], range[1]);
        double slot = categories.isEmpty() ? plot.width() : plot.width() / categories.size();
        int step = categoryStep(categories.size());
        for (int index = 0; index < categories.size(); index++) {
            String category = categories.get(index);
            double centre = plot.left() + slot * (index + 0.5);
            if (index % step == 0) {
                shapes.add(new Label(centre, plot.bottom() + 16, category, "middle", TICK_SIZE, TEXT));
            }
            Map<String, Double> groupsOfCategory = sums.get(category);
            for (int group = 0; group < groups.size(); group++) {
                Double value = groupsOfCategory.get(groups.get(group));
                if (value == null) {
                    continue;
                }
                double barWidth = slot * 0.8 / groups.size();
                double x = plot.left() + slot * index + slot * 0.1 + barWidth * group;
                double y = plot.py((range[1] - value) / (range[1] - range[0]));
                shapes.add(new Rect(x, Math.min(y, baseline), barWidth, Math.abs(baseline - y),
                        PALETTE[group % PALETTE.length]));
            }
        }
        axes(shapes, plot);
        legend(shapes, plot, groups);
        return new Scene(chart.width(), chart.height(), title(chart), shapes);
    }

    private static int categoryStep(int count) {
        return count <= 12 ? 1 : (int) Math.ceil(count / 12.0);
    }

    // ------------------------------------------------------------------ line / scatter

    private static Scene pointScene(Chart chart, List<ColumnSpec> columns, TableData data, Plot plot) {
        int xIndex = indexOf(columns, chart.x());
        int seriesIndex = chart.series() == null ? -1 : indexOf(columns, chart.series());
        List<String> names = new ArrayList<>();
        Map<String, List<double[]>> series = new LinkedHashMap<>();
        if (seriesIndex < 0) {
            for (String y : chart.y()) {
                int yIndex = indexOf(columns, y);
                List<double[]> points = new ArrayList<>();
                for (List<String> row : data.data()) {
                    Double x = number(xIndex < row.size() ? row.get(xIndex) : null);
                    Double value = number(yIndex < row.size() ? row.get(yIndex) : null);
                    if (x != null && value != null) {
                        points.add(new double[]{x, value});
                    }
                }
                names.add(y);
                series.put(y, points);
            }
        } else {
            int yIndex = indexOf(columns, chart.y().get(0));
            for (List<String> row : data.data()) {
                Double x = number(xIndex < row.size() ? row.get(xIndex) : null);
                Double value = number(yIndex < row.size() ? row.get(yIndex) : null);
                if (x == null || value == null) {
                    continue;
                }
                series.computeIfAbsent(groupValue(row, seriesIndex), key -> new ArrayList<>())
                        .add(new double[]{x, value});
            }
            names.addAll(sortedCategories(series.keySet()));
            Map<String, List<double[]>> sorted = new LinkedHashMap<>();
            for (String name : names) {
                sorted.put(name, series.get(name));
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
        boolean empty = xMin > xMax || yMin > yMax;
        if (empty) {
            xMin = 0;
            xMax = 1;
            yMin = 0;
            yMax = 1;
        }
        double[] xRange = padded(xMin, xMax);
        double[] yRange = padded(yMin, yMax);
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Rect(0, 0, chart.width(), chart.height(), BACKGROUND));
        yAxis(shapes, plot, yRange[0], yRange[1]);
        xAxis(shapes, plot, xRange[0], xRange[1]);
        int colour = 0;
        for (List<double[]> points : series.values()) {
            String stroke = PALETTE[colour % PALETTE.length];
            if (chart.type().equals("line")) {
                if (points.size() > 1) {
                    shapes.add(new Polyline(points.stream().map(point -> new double[]{
                            plot.px((point[0] - xRange[0]) / (xRange[1] - xRange[0])),
                            plot.py((yRange[1] - point[1]) / (yRange[1] - yRange[0]))}).toList(),
                            stroke, 2));
                }
            } else {
                for (double[] point : points) {
                    shapes.add(new Circle(
                            plot.px((point[0] - xRange[0]) / (xRange[1] - xRange[0])),
                            plot.py((yRange[1] - point[1]) / (yRange[1] - yRange[0])), 3, stroke));
                }
            }
            colour++;
        }
        axes(shapes, plot);
        if (names.size() > 1) {
            legend(shapes, plot, names);
        }
        return new Scene(chart.width(), chart.height(), title(chart), shapes);
    }

    // ------------------------------------------------------------------ histogram

    private static Scene histogramScene(Chart chart, List<ColumnSpec> columns, TableData data, Plot plot) {
        int xIndex = indexOf(columns, chart.x());
        List<Double> values = new ArrayList<>();
        for (List<String> row : data.data()) {
            Double value = number(xIndex < row.size() ? row.get(xIndex) : null);
            if (value != null) {
                values.add(value);
            }
        }
        double minimum = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double maximum = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        int bins = chart.bins();
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
        double[] range = bounds(0, top == 0 ? 1 : top);
        List<Shape> shapes = new ArrayList<>();
        shapes.add(new Rect(0, 0, chart.width(), chart.height(), BACKGROUND));
        yAxis(shapes, plot, range[0], range[1]);
        double slot = plot.width() / bins;
        int step = categoryStep(bins);
        for (int index = 0; index < bins; index++) {
            double height = plot.height() * counts[index] / (range[1] - range[0]);
            shapes.add(new Rect(plot.left() + slot * index + slot * 0.1,
                    plot.bottom() - height, slot * 0.8, height, PALETTE[0]));
            if (index % step == 0) {
                String label = bins == 1 ? num(minimum) : num(minimum + binWidth * index);
                shapes.add(new Label(plot.left() + slot * (index + 0.5), plot.bottom() + 16,
                        label, "middle", TICK_SIZE, TEXT));
            }
        }
        axes(shapes, plot);
        return new Scene(chart.width(), chart.height(), title(chart), shapes);
    }

    // ------------------------------------------------------------------ axes

    private static void yAxis(List<Shape> shapes, Plot plot, double minimum, double maximum) {
        List<Double> ticks = ticks(minimum, maximum);
        boolean first = true;
        for (double value : ticks) {
            double y = plot.py((maximum - value) / (maximum - minimum));
            if (!first) {
                shapes.add(new Line(plot.left(), y, plot.right(), y, GRID, 1));
            }
            first = false;
            shapes.add(new Label(plot.left() - 8, y + 4, num(value), "end", TICK_SIZE, TEXT));
        }
    }

    private static void xAxis(List<Shape> shapes, Plot plot, double minimum, double maximum) {
        for (double value : ticks(minimum, maximum)) {
            double x = plot.px((value - minimum) / (maximum - minimum));
            shapes.add(new Label(x, plot.bottom() + 16, num(value), "middle", TICK_SIZE, TEXT));
        }
    }

    /** Round tick values (1, 2, 5 x 10^n) instead of fractions like 0.8, 1.6, 2.4. */
    private static List<Double> ticks(double minimum, double maximum) {
        double span = maximum - minimum;
        double step = niceStep(span / Y_TICKS);
        List<Double> ticks = new ArrayList<>();
        double start = Math.ceil(minimum / step - 1e-9) * step;
        for (double value = start; value <= maximum + step * 1e-9; value += step) {
            ticks.add(value);
        }
        if (ticks.size() < 2) {
            ticks.clear();
            for (int index = 0; index <= Y_TICKS; index++) {
                ticks.add(minimum + span * index / Y_TICKS);
            }
        }
        return ticks;
    }

    private static double niceStep(double raw) {
        double exponent = Math.floor(Math.log10(raw));
        double base = Math.pow(10, exponent);
        double fraction = raw / base;
        double nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
        return nice * base;
    }

    private static void axes(List<Shape> shapes, Plot plot) {
        shapes.add(new Line(plot.left(), plot.top(), plot.left(), plot.bottom(), AXIS, 1));
        shapes.add(new Line(plot.left(), plot.bottom(), plot.right(), plot.bottom(), AXIS, 1));
    }

    private static void legend(List<Shape> shapes, Plot plot, List<String> names) {
        if (names.size() < 2) {
            return;
        }
        double x = plot.left();
        double y = plot.top() - 12;
        for (int index = 0; index < names.size(); index++) {
            shapes.add(new Rect(x, y - 9, 10, 10, PALETTE[index % PALETTE.length]));
            shapes.add(new Label(x + 14, y, names.get(index), "start", TICK_SIZE, TEXT));
            x += 14 + names.get(index).length() * TICK_SIZE * 0.6 + 16;
        }
    }

    // ------------------------------------------------------------------ svg

    private static String svg(Scene scene) {
        StringBuilder xml = new StringBuilder(2048);
        xml.append(SVG_DECLARATION).append('\n');
        xml.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(scene.width())
                .append("\" height=\"").append(scene.height()).append("\" viewBox=\"0 0 ")
                .append(scene.width()).append(' ').append(scene.height()).append("\">\n");
        xml.append("<title>").append(escapeXml(scene.title())).append("</title>\n");
        for (Shape shape : scene.shapes()) {
            if (shape instanceof Rect rect) {
                xml.append("<rect x=\"").append(num(rect.x())).append("\" y=\"").append(num(rect.y()))
                        .append("\" width=\"").append(num(rect.width())).append("\" height=\"")
                        .append(num(rect.height())).append("\" fill=\"").append(rect.fill()).append("\"/>\n");
            } else if (shape instanceof Line line) {
                xml.append("<line x1=\"").append(num(line.x1())).append("\" y1=\"").append(num(line.y1()))
                        .append("\" x2=\"").append(num(line.x2())).append("\" y2=\"").append(num(line.y2()))
                        .append("\" stroke=\"").append(line.stroke()).append("\" stroke-width=\"")
                        .append(num(line.strokeWidth())).append("\"/>\n");
            } else if (shape instanceof Polyline polyline) {
                StringBuilder points = new StringBuilder();
                for (double[] point : polyline.points()) {
                    if (points.length() > 0) {
                        points.append(' ');
                    }
                    points.append(num(point[0])).append(',').append(num(point[1]));
                }
                xml.append("<polyline points=\"").append(points).append("\" fill=\"none\" stroke=\"")
                        .append(polyline.stroke()).append("\" stroke-width=\"")
                        .append(num(polyline.strokeWidth())).append("\"/>\n");
            } else if (shape instanceof Circle circle) {
                xml.append("<circle cx=\"").append(num(circle.cx())).append("\" cy=\"")
                        .append(num(circle.cy())).append("\" r=\"").append(num(circle.radius()))
                        .append("\" fill=\"").append(circle.fill()).append("\"/>\n");
            } else if (shape instanceof Label label) {
                xml.append("<text x=\"").append(num(label.x())).append("\" y=\"").append(num(label.y()))
                        .append("\" text-anchor=\"").append(label.anchor())
                        .append("\" font-family=\"sans-serif\" font-size=\"").append(num(label.size()))
                        .append("\" fill=\"").append(label.fill()).append("\">")
                        .append(escapeXml(label.text())).append("</text>\n");
            }
        }
        xml.append("</svg>\n");
        return xml.toString();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String num(double value) {
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

    // ------------------------------------------------------------------ png

    private static byte[] png(Scene scene) throws IOException {
        BufferedImage image = new BufferedImage(scene.width(), scene.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, scene.width(), scene.height());
            for (Shape shape : scene.shapes()) {
                if (shape instanceof Rect rect) {
                    graphics.setColor(Color.decode(rect.fill()));
                    graphics.fillRect(round(rect.x()), round(rect.y()),
                            Math.max(1, round(rect.width())), Math.max(1, round(rect.height())));
                } else if (shape instanceof Line line) {
                    graphics.setColor(Color.decode(line.stroke()));
                    graphics.setStroke(new java.awt.BasicStroke((float) line.strokeWidth()));
                    graphics.drawLine(round(line.x1()), round(line.y1()), round(line.x2()), round(line.y2()));
                } else if (shape instanceof Polyline polyline) {
                    graphics.setColor(Color.decode(polyline.stroke()));
                    graphics.setStroke(new java.awt.BasicStroke((float) polyline.strokeWidth()));
                    int[] xs = new int[polyline.points().size()];
                    int[] ys = new int[polyline.points().size()];
                    for (int index = 0; index < xs.length; index++) {
                        xs[index] = round(polyline.points().get(index)[0]);
                        ys[index] = round(polyline.points().get(index)[1]);
                    }
                    graphics.drawPolyline(xs, ys, xs.length);
                } else if (shape instanceof Circle circle) {
                    graphics.setColor(Color.decode(circle.fill()));
                    int radius = round(circle.radius());
                    graphics.fillOval(round(circle.cx()) - radius, round(circle.cy()) - radius,
                            radius * 2, radius * 2);
                } else if (shape instanceof Label label) {
                    Font font = new Font(Font.SANS_SERIF, Font.PLAIN, (int) label.size());
                    graphics.setFont(font);
                    graphics.setColor(Color.decode(label.fill()));
                    FontMetrics metrics = graphics.getFontMetrics();
                    int width = metrics.stringWidth(label.text());
                    int x = switch (label.anchor()) {
                        case "middle" -> round(label.x()) - width / 2;
                        case "end" -> round(label.x()) - width;
                        default -> round(label.x());
                    };
                    graphics.drawString(label.text(), x, round(label.y()));
                }
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    // ------------------------------------------------------------------ helpers

    private static String title(Chart chart) {
        return chart.title() == null || chart.title().isBlank() ? "chart" : chart.title();
    }

    private static double[] bounds(double minimum, double maximum) {
        if (maximum - minimum < 1e-12) {
            maximum = minimum + 1;
        }
        return new double[]{minimum, maximum};
    }

    private static double[] padded(double minimum, double maximum) {
        if (maximum - minimum < 1e-12) {
            return new double[]{minimum - 1, maximum + 1};
        }
        double pad = (maximum - minimum) * 0.05;
        return new double[]{minimum - pad, maximum + pad};
    }
}
