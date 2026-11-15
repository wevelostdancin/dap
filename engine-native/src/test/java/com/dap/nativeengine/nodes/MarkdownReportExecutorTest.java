package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
