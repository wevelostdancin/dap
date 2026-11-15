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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartGridExecutorTest {
    @TempDir
    Path dir;

    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("category", "Utf8"),
            ColumnSpec.of("score", "Float64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{"A", 1.0},
            new Object[]{"B", 2.0});

    private static final Pattern MIDDLE_TEXT = Pattern.compile(
            "<text x=\"([0-9.\\-]+)\" y=\"([0-9.\\-]+)\"[^>]*text-anchor=\"middle\"[^>]*>([^<]*)</text>");

    private static String config(Path file, String extra) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"" + extra + "}";
    }

    /** Run chart_grid over two connected inputs and return the written file text. */
    private String render(String format, String extra, String suffix) throws Exception {
        Path file = dir.resolve("grid-" + System.nanoTime() + suffix);
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = NodeTestSupport.table(db, COLUMNS, ROWS);
            LazyPlan second = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{"A", 3.0},
                    new Object[]{"B", 4.0}));
            NodeContext context = NodeTestSupport.context(db, job, config(file,
                    ",\"format\":\"" + format + "\"" + extra));
            LazyPlan plan = new ChartGridExecutor().execute(context, List.of(first, second));
            assertSame(first, plan, "chart_grid must pass input port 0 through");
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

    private static double xOf(String document, String label) {
        Matcher matcher = MIDDLE_TEXT.matcher(document);
        while (matcher.find()) {
            if (matcher.group(3).equals(label)) {
                return Double.parseDouble(matcher.group(1));
            }
        }
        return -1;
    }

    private static String charts() {
        return ",\"title\":\"Dashboard\",\"columns\":2,"
                + "\"charts\":["
                + "{\"input\":0,\"type\":\"bar\",\"x\":\"category\",\"y\":[\"score\"],\"title\":\"Left\"},"
                + "{\"input\":1,\"type\":\"line\",\"x\":\"score\",\"y\":[\"score\"],\"title\":\"Right\"}]";
    }

    @Test
    void svgGridDrawsOnePanelPerChart() throws Exception {
        String document = render("svg", charts(), ".svg");
        assertTrue(document.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
                document.substring(0, 60));
        assertEquals(1, count(document, "<svg "), "one canvas for the whole grid");
        assertTrue(document.contains(">Dashboard</text>"), "shared title: " + document);
        assertTrue(document.contains(">Left</text>"), "first panel title: " + document);
        assertTrue(document.contains(">Right</text>"), "second panel title: " + document);
        double left = xOf(document, "Left");
        double right = xOf(document, "Right");
        assertTrue(left >= 0 && right >= 0, document);
        assertTrue(left < right, "two columns put the panels side by side: " + left + " " + right);
        assertTrue(count(document, "fill=\"#1f77b4\"") >= 2, document);
    }

    @Test
    void htmlGridEmbedsEveryPanelAsSvg() throws Exception {
        String document = render("html", charts(), ".html");
        assertTrue(document.startsWith("<!DOCTYPE html>"), document.substring(0, 40));
        assertEquals(2, count(document, "<svg "), "one inline SVG per chart: " + document);
        assertTrue(document.contains("<h1>Dashboard</h1>"), document);
        assertTrue(document.contains(">Left</text>") && document.contains(">Right</text>"), document);
        assertTrue(!document.contains("<script") && !document.contains("src=\"http"),
                "the HTML report must not reference external resources");
    }

    @Test
    void pngGridIsWrittenWithTheRequestedSize() throws Exception {
        Path file = dir.resolve("grid.png");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = NodeTestSupport.table(db, COLUMNS, ROWS);
            LazyPlan second = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, job, config(file,
                    ",\"format\":\"png\",\"width\":640,\"height\":480" + charts()));
            new ChartGridExecutor().execute(context, List.of(first, second));
        }
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(bytes.length > 1000, "PNG is not empty");
        assertEquals((byte) 0x89, bytes[0]);
        BufferedImage image = ImageIO.read(file.toFile());
        assertEquals(640, image.getWidth());
        assertEquals(480, image.getHeight());
    }

    @Test
    void rejectsInvalidConfiguration() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = NodeTestSupport.table(db, COLUMNS, ROWS);
            LazyPlan second = NodeTestSupport.table(db, COLUMNS, ROWS);
            List<LazyPlan> inputs = List.of(first, second);

            IllegalArgumentException format = assertThrows(IllegalArgumentException.class,
                    () -> new ChartGridExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("a.svg"), ",\"format\":\"pdf\"" + charts())),
                            inputs));
            assertTrue(format.getMessage().contains("format"), format.getMessage());

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new ChartGridExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("b.svg"), ",\"title\":\"x\"")), inputs));
            assertTrue(missing.getMessage().contains("charts"), missing.getMessage());

            IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                    () -> new ChartGridExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("c.svg"), ",\"charts\":[]")), inputs));
            assertTrue(empty.getMessage().contains("charts"), empty.getMessage());

            IllegalArgumentException port = assertThrows(IllegalArgumentException.class,
                    () -> new ChartGridExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("d.svg"), ",\"charts\":[{\"input\":5,\"type\":\"bar\","
                                    + "\"x\":\"category\",\"y\":[\"score\"]}]")), inputs));
            assertTrue(port.getMessage().contains("input"), port.getMessage());

            IllegalArgumentException type = assertThrows(IllegalArgumentException.class,
                    () -> new ChartGridExecutor().execute(NodeTestSupport.context(db, job,
                            config(dir.resolve("e.svg"), ",\"charts\":[{\"input\":0,\"type\":\"radar\","
                                    + "\"x\":\"category\",\"y\":[\"score\"]}]")), inputs));
            assertTrue(type.getMessage().contains("type"), type.getMessage());

            IllegalArgumentException file = assertThrows(IllegalArgumentException.class,
                    () -> new ChartGridExecutor().execute(NodeTestSupport.context(db, job,
                            "{\"format\":\"svg\"" + charts() + "}"), inputs));
            assertTrue(file.getMessage().contains("file"), file.getMessage());
        }
    }
}
