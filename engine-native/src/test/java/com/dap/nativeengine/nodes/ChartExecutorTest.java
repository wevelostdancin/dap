package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartExecutorTest {
    @TempDir
    Path dir;

    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("category", "Utf8"),
            ColumnSpec.of("score", "Float64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{1L, "A", 1.0},
            new Object[]{2L, "B", 2.0},
            new Object[]{3L, "A", 3.0},
            new Object[]{4L, "C", 4.0},
            new Object[]{5L, "B", 5.0});

    private static final Pattern MIDDLE_TEXT = Pattern.compile(
            "<text x=\"([0-9.\\-]+)\" y=\"([0-9.\\-]+)\"[^>]*text-anchor=\"middle\"[^>]*>([^<]*)</text>");
    private static final Pattern END_TEXT = Pattern.compile(
            "<text x=\"([0-9.\\-]+)\" y=\"([0-9.\\-]+)\"[^>]*text-anchor=\"end\"[^>]*>([^<]*)</text>");

    private static LazyPlan input(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, COLUMNS, ROWS);
    }

    private static String config(Path file, String extra) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"" + extra + "}";
    }

    /** Run the chart node and return the written SVG text; the plan must be passed through. */
    private String svg(String extra) throws Exception {
        return svg(COLUMNS, ROWS, extra);
    }

    private String svg(List<ColumnSpec> columns, List<Object[]> rows, String extra) throws Exception {
        Path file = dir.resolve("chart-" + System.nanoTime() + ".svg");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, job, config(file, extra));
            LazyPlan plan = new ChartExecutor().execute(context, List.of(input));
            assertSame(input, plan, "chart must pass its input through");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int found = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            found++;
            index = text.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private record Text(double x, double y, String label) {
    }

    private static List<Text> texts(String document, Pattern pattern) {
        List<Text> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(document);
        while (matcher.find()) {
            found.add(new Text(Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2)), matcher.group(3)));
        }
        return found;
    }

    /** X position of a middle-anchored label, or -1 when the label is not rendered. */
    private static double xOf(String document, String label) {
        for (Text text : texts(document, MIDDLE_TEXT)) {
            if (text.label().equals(label)) {
                return text.x();
            }
        }
        return -1;
    }

    /** Y position of the first end-anchored (y axis) tick with the given label, or NaN. */
    private static double yOfTick(String document, String label) {
        for (Text text : texts(document, END_TEXT)) {
            if (text.label().equals(label)) {
                return text.y();
            }
        }
        return Double.NaN;
    }

    @Test
    void barChartIsByteDeterministicAndDrawsOneBarPerCategory() throws Exception {
        String extra = ",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],\"title\":\"Scores\"";
        String first = svg(extra);
        String second = svg(extra);

        assertEquals(first, second, "SVG output must be byte-identical across runs");
        assertTrue(first.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), first.substring(0, 60));
        assertTrue(first.contains("<title>Scores</title>"), first);
        assertTrue(first.contains(">Scores</text>"), "the title must also be visible on the canvas");
        assertTrue(first.contains(">A</text>") && first.contains(">B</text>") && first.contains(">C</text>"), first);
        assertEquals(3, count(first, "fill=\"#1f77b4\""), "one bar per category");
        assertTrue(first.endsWith("</svg>\n"), "SVG must end with a newline after the root element");
    }

    @Test
    void svgEscapesTitlesAndLabels() throws Exception {
        String document = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],"
                + "\"title\":\"a & <b>\"");

        assertTrue(document.contains("<title>a &amp; &lt;b&gt;</title>"), document);
        assertTrue(document.contains(">a &amp; &lt;b&gt;</text>"), "visible title is escaped too");
    }

    @Test
    void lineScatterAndHistogramRenderTheirMarks() throws Exception {
        String line = svg(",\"type\":\"line\",\"x\":\"id\",\"y\":[\"score\"]");
        assertTrue(line.contains("<polyline "), line);

        String scatter = svg(",\"type\":\"scatter\",\"x\":\"id\",\"y\":[\"score\"]");
        assertEquals(5, count(scatter, "<circle "), scatter);

        String histogram = svg(",\"type\":\"histogram\",\"x\":\"score\",\"bins\":4");
        assertEquals(4, count(histogram, "fill=\"#1f77b4\""), histogram);
    }

    @Test
    void seriesSplitsBarsAndLegend() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("category", "Utf8"),
                ColumnSpec.of("region", "Utf8"),
                ColumnSpec.of("score", "Float64"));
        List<Object[]> rows = List.of(
                new Object[]{"A", "X", 1.0},
                new Object[]{"A", "Y", 2.0},
                new Object[]{"B", "X", 3.0},
                new Object[]{"B", "Y", 4.0});
        String document = svg(columns, rows,
                ",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],\"series\":\"region\"");
        assertEquals(7, count(document, "<rect "), document);
        assertEquals(3, count(document, "fill=\"#1f77b4\""), document);
        assertEquals(3, count(document, "fill=\"#ff7f0e\""), document);
        assertTrue(document.contains(">X</text>") && document.contains(">Y</text>"), document);
    }

    @Test
    void axisTitlesUnitsSubtitleAndLegendAreVisible() throws Exception {
        String document = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],"
                + "\"title\":\"Revenue\",\"subtitle\":\"per region\","
                + "\"x_label\":\"Category\",\"y_label\":\"Amount\","
                + "\"x_unit\":\"code\",\"y_unit\":\"kg\"");

        assertTrue(document.contains(">Revenue</text>"), document);
        assertTrue(document.contains(">per region</text>"), document);
        assertTrue(document.contains(">Category (code)</text>"), document);
        assertTrue(document.contains(">Amount (kg)</text>"), document);
        assertTrue(document.contains("rotate(-90"), "the y axis title is rotated");
    }

    @Test
    void barsSortByAggregatedValueDescendingByDefault() throws Exception {
        String document = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"]");
        double b = xOf(document, "B");
        double a = xOf(document, "A");
        double c = xOf(document, "C");
        assertTrue(b >= 0 && a >= 0 && c >= 0, document);
        assertTrue(b < a, "B sums to 7 and must come first, A=" + a + " B=" + b);

        String byLabel = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],"
                + "\"sort\":\"label\"");
        assertTrue(xOf(byLabel, "A") < xOf(byLabel, "B"), byLabel);

        String ascending = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],"
                + "\"sort\":\"value\",\"sort_direction\":\"asc\"");
        assertTrue(xOf(ascending, "A") < xOf(ascending, "C")
                && xOf(ascending, "C") < xOf(ascending, "B"), ascending);

        String none = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],"
                + "\"sort\":\"none\"");
        assertTrue(xOf(none, "A") < xOf(none, "B") && xOf(none, "B") < xOf(none, "C"), none);
    }

    @Test
    void barHeadroomPutsTheTopTickAboveTheTallestBar() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("category", "Utf8"),
                ColumnSpec.of("score", "Float64"));
        List<Object[]> rows = List.<Object[]>of(new Object[]{"A", 10.0});
        String document = svg(columns, rows, ",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"]");

        double topTick = Double.NEGATIVE_INFINITY;
        for (Text text : texts(document, END_TEXT)) {
            topTick = Math.max(topTick, Double.parseDouble(text.label()));
        }
        assertTrue(topTick > 10.0, "top y tick must exceed the tallest bar value 10, got " + topTick);

        String scatter = svg(columns, List.<Object[]>of(new Object[]{"A", 10.0}),
                ",\"type\":\"scatter\",\"x\":\"score\",\"y\":[\"score\"]");
        double scatterTop = Double.NEGATIVE_INFINITY;
        for (Text text : texts(scatter, END_TEXT)) {
            scatterTop = Math.max(scatterTop, Double.parseDouble(text.label()));
        }
        assertTrue(scatterTop > 10.0, "line/scatter get 5% headroom too, got " + scatterTop);
    }

    @Test
    void logAxisSpacesTicksMultiplicativelyAndRejectsNonPositiveValues() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("x", "Int64"),
                ColumnSpec.of("value", "Float64"));
        List<Object[]> rows = List.of(
                new Object[]{1L, 1.0},
                new Object[]{2L, 10.0},
                new Object[]{3L, 100.0});
        String document = svg(columns, rows,
                ",\"type\":\"scatter\",\"x\":\"x\",\"y\":[\"value\"],\"log_y\":true");
        double one = yOfTick(document, "1");
        double ten = yOfTick(document, "10");
        double hundred = yOfTick(document, "100");
        assertTrue(one > 0 && ten > 0 && hundred > 0, document);
        double firstGap = Math.abs(ten - one);
        double secondGap = Math.abs(hundred - ten);
        assertEquals(firstGap, secondGap, 0.6, "log ticks are equally spaced in log space");

        List<Object[]> withZero = List.<Object[]>of(new Object[]{1L, 0.0}, new Object[]{2L, 10.0});
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> svg(columns, withZero, ",\"type\":\"scatter\",\"x\":\"x\","
                        + "\"y\":[\"value\"],\"log_y\":true"));
        assertTrue(failure.getMessage().contains("log_y"), failure.getMessage());
    }

    @Test
    void dataLabelsAndAnnotationsAreRendered() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("category", "Utf8"),
                ColumnSpec.of("score", "Float64"));
        List<Object[]> rows = List.of(
                new Object[]{"A", 3.5},
                new Object[]{"B", 7.25});
        String document = svg(columns, rows, ",\"type\":\"bar\",\"x\":\"category\","
                + "\"y\":[\"score\"],\"data_labels\":true");
        assertTrue(document.contains(">7.25</text>"), document);
        assertTrue(document.contains(">3.5</text>"), document);

        String annotated = svg(",\"type\":\"line\",\"x\":\"id\",\"y\":[\"score\"],"
                + "\"annotations\":[{\"text\":\"peak\",\"x\":5,\"y\":5}]");
        assertTrue(annotated.contains(">peak</text>"), annotated);
    }

    @Test
    void gridCanBeDisabled() throws Exception {
        String withGrid = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"]");
        assertTrue(count(withGrid, "stroke=\"#e5e5e5\"") > 0, withGrid);

        String withoutGrid = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],"
                + "\"grid\":false");
        assertEquals(0, count(withoutGrid, "#e5e5e5"), withoutGrid);
    }

    @Test
    void pieDrawsSlicesLegendAndPercentages() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("category", "Utf8"),
                ColumnSpec.of("score", "Float64"));
        List<Object[]> rows = List.<Object[]>of(
                new Object[]{"A", 1.0},
                new Object[]{"B", 3.0});
        String document = svg(columns, rows, ",\"type\":\"pie\",\"x\":\"category\","
                + "\"y\":[\"score\"],\"title\":\"Share\"");
        assertEquals(2, count(document, "<path "), "one arc per slice");
        assertTrue(document.contains(">B 75.0%</text>"), document);
        assertTrue(document.contains(">A 25.0%</text>"), document);
        assertTrue(document.indexOf("B 75.0%") < document.indexOf("A 25.0%"),
                "slices and legend sort by value, largest first");

        String labelled = svg(columns, rows, ",\"type\":\"pie\",\"x\":\"category\","
                + "\"y\":[\"score\"],\"data_labels\":true");
        assertEquals(4, count(labelled, "%"), "percentages on the slices and in the legend");

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> svg(columns, List.<Object[]>of(new Object[]{"A", -1.0}),
                        ",\"type\":\"pie\",\"x\":\"category\",\"y\":[\"score\"]"));
        assertTrue(negative.getMessage().contains("pie"), negative.getMessage());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> svg(columns, List.<Object[]>of(new Object[]{"A", null}),
                        ",\"type\":\"pie\",\"x\":\"category\",\"y\":[\"score\"]"));
        assertTrue(empty.getMessage().contains("pie"), empty.getMessage());
    }

    @Test
    void boxplotDrawsQuartilesWhiskersAndOutliers() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("category", "Utf8"),
                ColumnSpec.of("score", "Float64"));
        List<Object[]> rows = new ArrayList<>();
        for (double value : new double[]{1, 2, 3, 4, 5}) {
            rows.add(new Object[]{"A", value});
        }
        for (double value : new double[]{1, 2, 3, 4, 100}) {
            rows.add(new Object[]{"B", value});
        }
        String document = svg(columns, rows, ",\"type\":\"boxplot\",\"x\":\"category\","
                + "\"y\":[\"score\"]");
        assertEquals(2, count(document, "fill=\"#1f77b4\""), "one box per category");
        assertEquals(1, count(document, "<circle "), "100 is an outlier of category B");
        assertTrue(document.contains(">A</text>") && document.contains(">B</text>"), document);

        String empty = svg(columns, List.<Object[]>of(new Object[]{"A", null}),
                ",\"type\":\"boxplot\",\"x\":\"category\",\"y\":[\"score\"]");
        assertTrue(empty.endsWith("</svg>\n"), "an empty boxplot still writes a valid SVG");
        assertEquals(0, count(empty, "fill=\"#1f77b4\""), empty);
    }

    @Test
    void heatmapAggregatesMeansAndDrawsAColorLegend() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("region", "Utf8"),
                ColumnSpec.of("month", "Utf8"),
                ColumnSpec.of("value", "Float64"));
        List<Object[]> rows = List.<Object[]>of(
                new Object[]{"N", "Jan", 1.0},
                new Object[]{"N", "Jan", 4.0},
                new Object[]{"N", "Feb", 4.25},
                new Object[]{"S", "Jan", 6.0},
                new Object[]{"S", "Feb", 8.75});
        String document = svg(columns, rows, ",\"type\":\"heatmap\",\"x\":\"region\","
                + "\"series\":\"month\",\"y\":[\"value\"],\"data_labels\":true");
        assertTrue(document.contains(">2.5</text>"), "cell value is the mean of 1 and 4: " + document);
        assertTrue(document.contains(">8.75</text>"), "legend and cell show the maximum");
        assertTrue(document.contains(">Jan</text>") && document.contains(">Feb</text>"), document);
        assertTrue(document.contains(">N</text>") && document.contains(">S</text>"), document);

        String empty = svg(columns, List.<Object[]>of(new Object[]{"N", "Jan", null}),
                ",\"type\":\"heatmap\",\"x\":\"region\",\"series\":\"month\","
                        + "\"y\":[\"value\"]");
        assertTrue(empty.endsWith("</svg>\n"), empty);

        IllegalArgumentException noSeries = assertThrows(IllegalArgumentException.class,
                () -> svg(columns, rows, ",\"type\":\"heatmap\",\"x\":\"region\","
                        + "\"y\":[\"value\"]"));
        assertTrue(noSeries.getMessage().contains("series"), noSeries.getMessage());
    }

    @Test
    void newChartTypesRenderPng() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("category", "Utf8"),
                ColumnSpec.of("series", "Utf8"),
                ColumnSpec.of("score", "Float64"));
        List<Object[]> rows = List.<Object[]>of(
                new Object[]{"A", "X", 1.0},
                new Object[]{"B", "X", 2.0},
                new Object[]{"A", "Y", 3.0});
        for (String type : List.of("pie", "boxplot", "heatmap")) {
            Path file = dir.resolve("type-" + type + ".png");
            String extra = switch (type) {
                case "heatmap" -> ",\"format\":\"png\",\"type\":\"heatmap\","
                        + "\"x\":\"category\",\"series\":\"series\",\"y\":[\"score\"],"
                        + "\"width\":320,\"height\":240";
                default -> ",\"format\":\"png\",\"type\":\"" + type + "\","
                        + "\"x\":\"category\",\"y\":[\"score\"],"
                        + "\"width\":320,\"height\":240";
            };
            try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
                LazyPlan input = NodeTestSupport.table(db, columns, rows);
                NodeContext context = NodeTestSupport.context(db, job, config(file, extra));
                new ChartExecutor().execute(context, List.of(input));
            }
            assertTrue(Files.isRegularFile(file), file.toString());
            byte[] bytes = Files.readAllBytes(file);
            assertTrue(bytes.length > 1000, type + " PNG is not empty");
            assertEquals((byte) 0x89, bytes[0]);
            BufferedImage image = ImageIO.read(file.toFile());
            assertEquals(320, image.getWidth());
            assertEquals(240, image.getHeight());
        }
    }

    @Test
    void pngIsWrittenWithTheRequestedSize() throws Exception {
        Path file = dir.resolve("chart.png");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = input(db);
            NodeContext context = NodeTestSupport.context(db, job, config(file,
                    ",\"format\":\"png\",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],"
                            + "\"width\":320,\"height\":240"));
            new ChartExecutor().execute(context, List.of(input));
        }
        assertTrue(Files.isRegularFile(file), file.toString());
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(bytes.length > 1000, "PNG is not empty");
        assertEquals((byte) 0x89, bytes[0]);
        assertEquals((byte) 'P', bytes[1]);
        BufferedImage image = ImageIO.read(file.toFile());
        assertEquals(320, image.getWidth());
        assertEquals(240, image.getHeight());
    }

    @Test
    void rejectsInvalidConfiguration() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = input(db);

            IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("a.svg"), ",\"type\":\"radar\",\"x\":\"category\","
                                    + "\"y\":[\"score\"]")), List.of(input)));
            assertTrue(type.getMessage().contains("type"), type.getMessage());

            IllegalArgumentException x = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("b.svg"), ",\"type\":\"bar\",\"y\":[\"score\"]")),
                            List.of(input)));
            assertTrue(x.getMessage().contains("x"), x.getMessage());

            IllegalArgumentException column = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("c.svg"), ",\"type\":\"bar\",\"x\":\"nope\","
                                    + "\"y\":[\"score\"]")), List.of(input)));
            assertTrue(column.getMessage().contains("nope"), column.getMessage());

            IllegalArgumentException histogram = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("d.svg"), ",\"type\":\"histogram\",\"x\":\"score\","
                                    + "\"y\":[\"score\"]")), List.of(input)));
            assertTrue(histogram.getMessage().contains("y"), histogram.getMessage());

            IllegalArgumentException bar = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("e.svg"), ",\"type\":\"bar\",\"x\":\"category\","
                                    + "\"y\":[\"score\",\"id\"]")), List.of(input)));
            assertTrue(bar.getMessage().contains("y"), bar.getMessage());

            IllegalArgumentException format = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("f.svg"), ",\"format\":\"pdf\",\"type\":\"bar\","
                                    + "\"x\":\"category\",\"y\":[\"score\"]")), List.of(input)));
            assertTrue(format.getMessage().contains("format"), format.getMessage());

            IllegalArgumentException sort = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("g.svg"), ",\"type\":\"bar\",\"x\":\"category\","
                                    + "\"y\":[\"score\"],\"sort\":\"size\"")), List.of(input)));
            assertTrue(sort.getMessage().contains("sort"), sort.getMessage());

            IllegalArgumentException direction = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("h.svg"), ",\"type\":\"bar\",\"x\":\"category\","
                                    + "\"y\":[\"score\"],\"sort_direction\":\"up\"")), List.of(input)));
            assertTrue(direction.getMessage().contains("sort_direction"), direction.getMessage());

            IllegalArgumentException annotation = assertThrows(IllegalArgumentException.class,
                    () -> new ChartExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("i.svg"), ",\"type\":\"line\",\"x\":\"id\","
                                    + "\"y\":[\"score\"],\"annotations\":[{\"x\":1}]")), List.of(input)));
            assertTrue(annotation.getMessage().contains("text"), annotation.getMessage());
        }
    }
}
