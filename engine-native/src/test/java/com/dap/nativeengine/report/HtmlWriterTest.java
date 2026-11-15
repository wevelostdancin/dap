package com.dap.nativeengine.report;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlWriterTest {
    private static final ReportDocument.Section TEXT = new ReportDocument.Section.Text(null, "hello");

    private static String render(ReportDocument document) {
        return new String(new HtmlWriter().write(document), StandardCharsets.UTF_8);
    }

    private static ReportDocument document(ReportDocument.Section... sections) {
        return new ReportDocument(ReportDocument.Format.HTML, "Report", null, null, false,
                ReportDocument.Theme.DEFAULT, null, null, List.of(sections));
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void rendersSelfContainedDocumentWithTitleAndStyles() {
        String html = render(document(TEXT));

        assertTrue(html.startsWith("<!DOCTYPE html>"), html.substring(0, 40));
        assertTrue(html.contains("<meta charset=\"utf-8\">"), html);
        assertTrue(html.contains("<style>"), html);
        assertTrue(html.contains("<h1>Report</h1>"), html);
        assertTrue(html.contains("<p class=\"text\">hello</p>"), html);
        assertFalse(html.contains("<link"), "no external stylesheet");
        assertFalse(html.contains("<script"), "no external script");
        assertFalse(html.contains("http://"), "no external links");
        assertFalse(html.contains("https://"), "no external links");
    }

    @Test
    void escapesSubtitleAuthorThemeAndPageSetup() {
        ReportDocument document = new ReportDocument(ReportDocument.Format.HTML,
                "T & <b>", "Sub \"quoted\"", "Ann", false, ReportDocument.Theme.DARK,
                ReportDocument.PageSize.LETTER, ReportDocument.Orientation.LANDSCAPE, List.of(TEXT));

        String html = render(document);

        assertTrue(html.contains("<h1>T &amp; &lt;b&gt;</h1>"), html);
        assertTrue(html.contains("<p class=\"subtitle\">Sub &quot;quoted&quot;</p>"), html);
        assertTrue(html.contains("<p class=\"author\">Ann</p>"), html);
        assertTrue(html.contains("<body class=\"theme-dark\">"), html);
        assertTrue(html.contains("@page { size: letter landscape;"), html);
    }

    @Test
    void rendersTocWithAnchorsOnlyWhenEnabled() {
        ReportDocument.Section intro = new ReportDocument.Section.Text("Intro", "one");
        ReportDocument.Section rows = new ReportDocument.Section.Table("Rows",
                List.of("a"), List.of(List.of("1")));
        ReportDocument.Section untitled = new ReportDocument.Section.Text(null, "two");
        ReportDocument withToc = new ReportDocument(ReportDocument.Format.HTML, null, null, null,
                true, ReportDocument.Theme.DEFAULT, null, null, List.of(intro, rows, untitled));

        String html = render(withToc);

        assertTrue(html.contains("<nav class=\"toc\">"), html);
        assertTrue(html.contains("<a href=\"#section-1\">Intro</a>"), html);
        assertTrue(html.contains("<a href=\"#section-2\">Rows</a>"), html);
        assertFalse(html.contains("#section-3"), "untitled sections are not in the table of contents");
        assertTrue(html.contains("<section id=\"section-1\">"), html);

        String withoutToc = render(document(intro));
        assertFalse(withoutToc.contains("<nav class=\"toc\">"), withoutToc);
    }

    @Test
    void rendersTablesWithEscapedCells() {
        ReportDocument.Section table = new ReportDocument.Section.Table("Rows",
                List.of("a", "b"),
                List.of(Arrays.asList("x<y", "1&2"), Arrays.asList("q\"z", null)));

        String html = render(document(table));

        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<thead><tr><th>a</th><th>b</th></tr></thead>"), html);
        assertTrue(html.contains("<td>x&lt;y</td>"), html);
        assertTrue(html.contains("<td>1&amp;2</td>"), html);
        assertTrue(html.contains("<td>q&quot;z</td>"), html);
        assertTrue(html.contains("<td></td>"), "NULL renders as an empty cell: " + html);
    }

    @Test
    void embedsImagesAsDataUris() throws Exception {
        byte[] bytes = png(4, 3);
        ReportDocument.Section image = new ReportDocument.Section.Image("Chart", "/data/chart.png",
                "sales <chart>", 120, bytes);

        String html = render(document(image));

        assertTrue(html.contains("src=\"data:image/png;base64,"), html);
        assertTrue(html.contains("alt=\"sales &lt;chart&gt;\""), html);
        assertTrue(html.contains("width=\"120\""), html);
        assertFalse(html.contains("/data/chart.png"), "the image must be embedded, not linked");
    }

    @Test
    void rejectsUnsupportedImageBytes() {
        ReportDocument.Section image = new ReportDocument.Section.Image("Bad", "/data/notes.txt",
                "notes", 0, "not an image".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> render(document(image)));
        assertTrue(failure.getMessage().contains("image"), failure.getMessage());
    }

    @Test
    void rendersPageBreaksAndIsDeterministic() {
        ReportDocument.Section breakSection = new ReportDocument.Section.PageBreak(null);
        ReportDocument document = document(TEXT, breakSection, TEXT);

        String first = render(document);
        String second = render(document);

        assertTrue(first.contains("<div class=\"page-break\"></div>"), first);
        assertEquals(first, second, "two renders of the same document must be identical");
    }
}
