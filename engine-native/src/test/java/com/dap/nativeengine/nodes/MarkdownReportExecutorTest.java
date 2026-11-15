package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownReportExecutorTest {
    @TempDir
    Path dir;

    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("name", "Utf8"),
            ColumnSpec.of("score", "Float64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{1L, "ann", 2.0},
            new Object[]{2L, "a|b", 4.0},
            new Object[]{3L, "line1\nline2", 6.0},
            new Object[]{4L, null, null});

    private static LazyPlan input(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, COLUMNS, ROWS);
    }

    private static String config(Path file, String extra) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"" + extra + "}";
    }

    private static String document(Path file, String extra) throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = input(db);
            NodeContext context = NodeTestSupport.context(db, job, config(file, extra));
            LazyPlan plan = new MarkdownReportExecutor().execute(context, List.of(input));
            assertSame(input, plan, "markdown_report must pass its input through");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void rendersTitleAndEscapedTableSection() throws Exception {
        String markdown = document(dir.resolve("report.md"),
                ",\"title\":\"Report\",\"sections\":[{\"kind\":\"table\",\"title\":\"Rows\","
                        + "\"columns\":[\"id\",\"name\"],\"limit\":10}]");

        assertEquals("""
                # Report

                ## Rows

                | id | name |
                | --- | --- |
                | 1 | ann |
                | 2 | a\\|b |
                | 3 | line1<br>line2 |
                | 4 |  |
                """, markdown);
    }

    @Test
    void tableSectionHonoursColumnsAndLimit() throws Exception {
        String markdown = document(dir.resolve("limited.md"),
                ",\"sections\":[{\"kind\":\"table\",\"columns\":[\"name\"],\"limit\":2}]");

        assertEquals("""
                | name |
                | --- |
                | ann |
                | a\\|b |
                """, markdown);
    }

    @Test
    void profileSectionRendersDescribeStatistics() throws Exception {
        String markdown = document(dir.resolve("profile.md"),
                ",\"sections\":[{\"kind\":\"profile\",\"columns\":[\"score\"]}]");

        assertEquals("""
                | column | type | count | missing | distinct | min | max | mean | stddev |
                | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                | score | Number (Float) | 3 | 1 | 3 | 2.0 | 6.0 | 4.0 | 2.0 |
                """, markdown);
    }

    @Test
    void metricsSectionReportsRowColumnAndColumnMetrics() throws Exception {
        String markdown = document(dir.resolve("metrics.md"),
                ",\"sections\":[{\"kind\":\"metrics\",\"columns\":[\"score\"]}]");

        assertEquals("""
                | metric | value |
                | --- | --- |
                | rows | 4 |
                | columns | 3 |
                | score.count | 3 |
                | score.missing | 1 |
                | score.mean | 4.0 |
                | score.min | 2.0 |
                | score.max | 6.0 |
                """, markdown);
    }

    @Test
    void textSectionAndDeterministicBytes() throws Exception {
        Path file = dir.resolve("text.md");
        String extra = ",\"sections\":[{\"kind\":\"text\",\"title\":\"Note\",\"text\":\"plain text\"}]";
        String first = document(file, extra);
        String second = document(file, extra);

        assertEquals("## Note\n\nplain text\n", first);
        assertEquals(first, second, "two runs over the same input must produce the same bytes");
    }

    @Test
    void writesNestedParentDirectoriesAndSectionsInConfigOrder() throws Exception {
        Path file = dir.resolve("nested/deeper/report.md");
        String markdown = document(file,
                ",\"sections\":[{\"kind\":\"text\",\"text\":\"first\"},"
                        + "{\"kind\":\"text\",\"text\":\"second\"}]");

        assertTrue(Files.isRegularFile(file), file.toString());
        assertEquals("first\n\nsecond\n", markdown);
    }

    @Test
    void rejectsInvalidSections() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = input(db);

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new MarkdownReportExecutor().execute(
                            NodeTestSupport.context(db, job, config(dir.resolve("a.md"), "")),
                            List.of(input)));
            assertTrue(missing.getMessage().contains("sections"), missing.getMessage());

            IllegalArgumentException kind = assertThrows(IllegalArgumentException.class,
                    () -> new MarkdownReportExecutor().execute(
                            NodeTestSupport.context(db, job, config(dir.resolve("b.md"),
                                    ",\"sections\":[{\"kind\":\"chart\"}]")),
                            List.of(input)));
            assertTrue(kind.getMessage().contains("kind"), kind.getMessage());

            IllegalArgumentException column = assertThrows(IllegalArgumentException.class,
                    () -> new MarkdownReportExecutor().execute(
                            NodeTestSupport.context(db, job, config(dir.resolve("c.md"),
                                    ",\"sections\":[{\"kind\":\"table\",\"columns\":[\"nope\"]}]")),
                            List.of(input)));
            assertTrue(column.getMessage().contains("nope"), column.getMessage());

            IllegalArgumentException text = assertThrows(IllegalArgumentException.class,
                    () -> new MarkdownReportExecutor().execute(
                            NodeTestSupport.context(db, job, config(dir.resolve("d.md"),
                                    ",\"sections\":[{\"kind\":\"text\",\"title\":\"no body\"}]")),
                            List.of(input)));
            assertTrue(text.getMessage().contains("text"), text.getMessage());
        }
    }

    // -------------------------------------------------- multiple inputs and new sections

    private static final List<ColumnSpec> SECOND_COLUMNS = List.of(
            ColumnSpec.of("city", "Utf8"),
            ColumnSpec.of("amount", "Float64"));

    private static final List<Object[]> SECOND_ROWS = List.of(
            new Object[]{"moscow", 10.0},
            new Object[]{"kazan", 30.0});

    private static LazyPlan secondInput(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, SECOND_COLUMNS, SECOND_ROWS);
    }

    private static LazyPlan execute(DuckDbSession db, Connection job, Path file, String extra,
                                    List<LazyPlan> inputs) throws Exception {
        NodeContext context = NodeTestSupport.context(db, job, config(file, extra));
        return new MarkdownReportExecutor().execute(context, inputs);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void multipleInputsFeedSectionsByIndex() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = input(db);
            LazyPlan second = secondInput(db);
            Path file = dir.resolve("multi.md");
            LazyPlan plan = execute(db, job, file,
                    ",\"sections\":[{\"kind\":\"table\",\"title\":\"First\",\"columns\":[\"id\"],\"limit\":2},"
                            + "{\"kind\":\"table\",\"title\":\"Second\",\"input\":1}]",
                    List.of(first, second));

            assertSame(first, plan, "markdown_report must pass input port 0 through");
            assertEquals("""
                    ## First

                    | id |
                    | --- |
                    | 1 |
                    | 2 |

                    ## Second

                    | city | amount |
                    | --- | --- |
                    | moscow | 10.0 |
                    | kazan | 30.0 |
                    """, Files.readString(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void metricsAndProfileSectionsReadTheSelectedInput() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = input(db);
            LazyPlan second = secondInput(db);
            Path file = dir.resolve("selected.md");
            execute(db, job, file,
                    ",\"sections\":[{\"kind\":\"metrics\",\"title\":\"Totals\",\"input\":1,"
                            + "\"columns\":[\"amount\"]},"
                            + "{\"kind\":\"profile\",\"title\":\"Profile\",\"input\":1,"
                            + "\"columns\":[\"city\"]}]",
                    List.of(first, second));

            assertEquals("""
                    ## Totals

                    | metric | value |
                    | --- | --- |
                    | rows | 2 |
                    | columns | 2 |
                    | amount.count | 2 |
                    | amount.missing | 0 |
                    | amount.mean | 20.0 |
                    | amount.min | 10.0 |
                    | amount.max | 30.0 |

                    ## Profile

                    | column | type | count | missing | distinct | min | max | mean | stddev |
                    | --- | --- | --- | --- | --- | --- | --- | --- | --- |
                    | city | String | 2 | 0 | 2 |  |  |  |  |
                    """, Files.readString(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsUnconnectedOrOutOfRangeSectionInput() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = input(db);

            IllegalArgumentException unconnected = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, job, dir.resolve("unconnected.md"),
                            ",\"sections\":[{\"kind\":\"table\",\"input\":1}]",
                            List.of(first)));
            assertTrue(unconnected.getMessage().contains("input"), unconnected.getMessage());

            IllegalArgumentException outOfRange = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, job, dir.resolve("range.md"),
                            ",\"sections\":[{\"kind\":\"table\",\"input\":9}]",
                            List.of(first)));
            assertTrue(outOfRange.getMessage().contains("between 0 and 7"), outOfRange.getMessage());
        }
    }

    @Test
    void imageSectionRendersMarkdownImage() throws Exception {
        Path image = dir.resolve("chart.png");
        Files.write(image, png(4, 3));
        String markdown = document(dir.resolve("image.md"),
                ",\"sections\":[{\"kind\":\"image\",\"title\":\"Chart\",\"path\":\""
                        + NodeTestSupport.path(image) + "\",\"alt\":\"sales chart\",\"width\":320}]");

        assertEquals("## Chart\n\n![sales chart](" + image.toAbsolutePath() + ")\n", markdown);
    }

    @Test
    void rejectsMissingImageAndInvalidImageWidth() throws Exception {
        Path missing = dir.resolve("missing.png");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = input(db);

            IllegalArgumentException absent = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, job, dir.resolve("missing.md"),
                            ",\"sections\":[{\"kind\":\"image\",\"path\":\""
                                    + NodeTestSupport.path(missing) + "\"}]",
                            List.of(first)));
            assertTrue(absent.getMessage().contains("image"), absent.getMessage());
            assertTrue(absent.getMessage().contains("missing.png"), absent.getMessage());

            Path image = dir.resolve("tiny.png");
            Files.write(image, png(2, 2));
            IllegalArgumentException width = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, job, dir.resolve("width.md"),
                            ",\"sections\":[{\"kind\":\"image\",\"path\":\""
                                    + NodeTestSupport.path(image) + "\",\"width\":0}]",
                            List.of(first)));
            assertTrue(width.getMessage().contains("width"), width.getMessage());
        }
    }

    @Test
    void pageBreakSectionRendersHorizontalRule() throws Exception {
        String markdown = document(dir.resolve("break.md"),
                ",\"sections\":[{\"kind\":\"text\",\"text\":\"one\"},"
                        + "{\"kind\":\"page_break\"},"
                        + "{\"kind\":\"text\",\"text\":\"two\"}]");

        assertEquals("one\n\n---\n\ntwo\n", markdown);
    }

    @Test
    void htmlFormatWritesSelfContainedReport() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = input(db);
            Path file = dir.resolve("report.html");
            LazyPlan plan = execute(db, job, file,
                    ",\"format\":\"html\",\"title\":\"Report\",\"toc\":true,"
                            + "\"sections\":[{\"kind\":\"table\",\"title\":\"Rows\","
                            + "\"columns\":[\"id\"],\"limit\":2}]",
                    List.of(first));

            assertSame(first, plan, "html reports still pass input port 0 through");
            String html = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(html.startsWith("<!DOCTYPE html>"), html);
            assertTrue(html.contains("<h1>Report</h1>"), html);
            assertTrue(html.contains("<a href=\"#section-1\">Rows</a>"), html);
            assertTrue(html.contains("<td>1</td>"), html);
            assertFalse(html.contains("http://"), "self-contained report has no external links");
        }
    }

    @Test
    void rejectsUnknownFormatAndTheme() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan first = input(db);

            IllegalArgumentException format = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, job, dir.resolve("format.md"),
                            ",\"format\":\"docx\",\"sections\":[{\"kind\":\"text\",\"text\":\"x\"}]",
                            List.of(first)));
            assertTrue(format.getMessage().contains("format"), format.getMessage());

            IllegalArgumentException theme = assertThrows(IllegalArgumentException.class,
                    () -> execute(db, job, dir.resolve("theme.html"),
                            ",\"format\":\"html\",\"theme\":\"neon\","
                                    + "\"sections\":[{\"kind\":\"text\",\"text\":\"x\"}]",
                            List.of(first)));
            assertTrue(theme.getMessage().contains("theme"), theme.getMessage());
        }
    }
}
