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
import java.util.List;

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

    private static LazyPlan input(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, COLUMNS, ROWS);
    }

    private static String config(Path file, String extra) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"" + extra + "}";
    }

    /** Run the chart node and return the written SVG text; the plan must be passed through. */
    private String svg(String extra) throws Exception {
        Path file = dir.resolve("chart-" + System.nanoTime() + ".svg");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = input(db);
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

    @Test
    void barChartIsByteDeterministicAndDrawsOneBarPerCategory() throws Exception {
        String extra = ",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],\"title\":\"Scores\"";
        String first = svg(extra);
        String second = svg(extra);

        assertEquals(first, second, "SVG output must be byte-identical across runs");
        assertTrue(first.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), first.substring(0, 60));
        assertTrue(first.contains("<title>Scores</title>"), first);
        assertTrue(first.contains(">A</text>") && first.contains(">B</text>") && first.contains(">C</text>"), first);
        assertEquals(3, count(first, "fill=\"#1f77b4\""), "one bar per category");
        assertTrue(first.endsWith("</svg>\n"), "SVG must end with a newline after the root element");
    }

    @Test
    void svgEscapesTitlesAndLabels() throws Exception {
        String document = svg(",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],"
                + "\"title\":\"a & <b>\"");

        assertTrue(document.contains("<title>a &amp; &lt;b&gt;</title>"), document);
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
        Path file = dir.resolve("series.svg");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, job, config(file,
                    ",\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],\"series\":\"region\""));
            new ChartExecutor().execute(context, List.of(input));
        }
        String document = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(7, count(document, "<rect "), document);
        assertEquals(3, count(document, "fill=\"#1f77b4\""), document);
        assertEquals(3, count(document, "fill=\"#ff7f0e\""), document);
        assertTrue(document.contains(">X</text>") && document.contains(">Y</text>"), document);
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
                            config(dir.resolve("a.svg"), ",\"type\":\"pie\",\"x\":\"category\","
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
        }
    }
}
