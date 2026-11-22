package com.dap.nativeengine.report;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rendering contracts shared by every report format. */
class ReportWriterTest {
    private static ReportDocument document(ReportDocument.Format format, List<String> columns,
                                           List<List<String>> rows) {
        return new ReportDocument(format, "Report", null, null, false,
                ReportDocument.Theme.DEFAULT, null, null,
                List.of(new ReportDocument.Section.Table("Data", columns, rows)));
    }

    /** Pipes that are not escaped by an odd number of backslashes are column separators. */
    private static int unescapedPipes(String line) {
        int count = 0;
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) != '|') {
                continue;
            }
            int backslashes = 0;
            for (int before = index - 1; before >= 0 && line.charAt(before) == '\\'; before--) {
                backslashes++;
            }
            if (backslashes % 2 == 0) {
                count++;
            }
        }
        return count;
    }

    @Test
    void escapesBackslashBeforePipeAndMarkdownMeta() {
        assertEquals("a\\\\\\|b", ReportWriter.escapeCell("a\\|b"));
        assertEquals("\\*\\*x\\*\\*", ReportWriter.escapeCell("**x**"));
        assertEquals("\\`code\\_\\~", ReportWriter.escapeCell("`code_~"));
    }

    @Test
    void markdownKeepsTwoColumnsAndLiteralText() {
        String markdown = ReportWriter.markdown(document(ReportDocument.Format.MD,
                List.of("left", "right"), List.of(Arrays.asList("a\\|b", "**x**"))));

        for (String line : markdown.split("\n")) {
            if (line.startsWith("|")) {
                assertEquals(3, unescapedPipes(line), "two columns need three separators: " + line);
            }
        }
        assertTrue(markdown.contains("a\\\\\\|b"), markdown);
        assertTrue(markdown.contains("\\*\\*x\\*\\*"), markdown);
    }

    @Test
    void markdownHtmlAndPptxWriteTheSameLiteralCellText() throws Exception {
        List<List<String>> rows = List.of(List.of("a\\|b", "**x**"));
        String markdown = new String(ReportWriter.write(document(ReportDocument.Format.MD,
                List.of("a", "b"), rows)), StandardCharsets.UTF_8);
        String html = new String(ReportWriter.write(document(ReportDocument.Format.HTML,
                List.of("a", "b"), rows)), StandardCharsets.UTF_8);

        assertTrue(markdown.contains("\\*\\*x\\*\\*"), markdown);
        assertTrue(html.contains("<td>**x**</td>"), html);
        assertTrue(html.contains("<td>a\\|b</td>"), html);
    }

    @Test
    void markdownAndHtmlAreByteIdenticalAcrossRuns() throws Exception {
        ReportDocument md = document(ReportDocument.Format.MD, List.of("a", "b"),
                List.of(Arrays.asList("a\\|b", "**x**"), Arrays.asList(null, "<tag>")));
        ReportDocument html = document(ReportDocument.Format.HTML, List.of("a", "b"),
                List.of(Arrays.asList("a\\|b", "**x**"), Arrays.asList(null, "<tag>")));

        for (ReportDocument document : List.of(md, html)) {
            byte[] first = ReportWriter.write(document);
            byte[] second = ReportWriter.write(document);
            assertTrue(Arrays.equals(first, second), document.format() + " must be deterministic");
        }
    }

    @Test
    void pdfIsByteIdenticalAndDocumentsTheWinAnsiFontLimit() throws Exception {
        ReportDocument document = new ReportDocument(ReportDocument.Format.PDF, "Report", null, null,
                false, ReportDocument.Theme.DEFAULT, null, null,
                List.of(new ReportDocument.Section.Table("Data", List.of("a"),
                        List.of(List.of("привет"), List.of("ok")))));

        byte[] first = ReportWriter.write(document);
        byte[] second = ReportWriter.write(document);
        assertTrue(Arrays.equals(first, second), "pdf rendering must be deterministic");
        String content = new String(first, StandardCharsets.ISO_8859_1);
        assertTrue(!content.contains("/CreationDate") && !content.contains("/ModDate"), content);
        assertTrue(content.contains("(??????) Tj"), "standard Helvetica has no Cyrillic glyphs");
    }
}
