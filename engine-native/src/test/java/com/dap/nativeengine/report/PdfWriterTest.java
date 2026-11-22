package com.dap.nativeengine.report;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfWriterTest {
    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static ReportDocument document(ReportDocument.PageSize size,
                                           ReportDocument.Orientation orientation,
                                           ReportDocument.Section... sections) {
        return new ReportDocument(ReportDocument.Format.PDF, "Report", null, null, false,
                ReportDocument.Theme.DEFAULT, size, orientation, List.of(sections));
    }

    private static int pageCount(String pdf) {
        Matcher matcher = Pattern.compile("/Count (\\d+)").matcher(pdf);
        assertTrue(matcher.find(), "no /Count in the PDF");
        return Integer.parseInt(matcher.group(1));
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, (x * 7 + y * 11) & 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static List<List<String>> rows(int count) {
        List<List<String>> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(List.of("row " + index, "value " + index));
        }
        return rows;
    }

    @Test
    void writesPdfHeaderAndOnePage() throws Exception {
        byte[] pdf = new PdfWriter().write(document(ReportDocument.PageSize.A4,
                ReportDocument.Orientation.PORTRAIT,
                new ReportDocument.Section.Text(null, "hello world")));
        String content = latin1(pdf);

        assertTrue(content.startsWith("%PDF-1.4"), content.substring(0, Math.min(20, content.length())));
        assertTrue(pdf.length > 200, "pdf size " + pdf.length);
        assertEquals(1, pageCount(content));
        assertTrue(content.contains("/Type /Page"), content);
        assertTrue(content.contains("(hello world) Tj"), content);
        assertTrue(content.trim().endsWith("%%EOF"), content.substring(content.length() - 40));
    }

    @Test
    void drawsTitleAndSectionHeading() throws Exception {
        ReportDocument document = new ReportDocument(ReportDocument.Format.PDF, "Quarterly & <Report>",
                "Sales", "DAP", false, ReportDocument.Theme.DEFAULT, null, null,
                List.of(new ReportDocument.Section.Text("Details", "body")));

        String content = latin1(new PdfWriter().write(document));

        assertTrue(content.contains("(Quarterly & <Report>) Tj"), content);
        assertTrue(content.contains("(Sales) Tj"), content);
        assertTrue(content.contains("(DAP) Tj"), content);
        assertTrue(content.contains("(Details) Tj"), content);
        assertTrue(content.contains("/MediaBox [0 0 595 842]"), "default page is A4 portrait");
    }

    @Test
    void honoursPageSizeAndOrientation() throws Exception {
        String a4 = latin1(new PdfWriter().write(document(ReportDocument.PageSize.A4,
                ReportDocument.Orientation.PORTRAIT, new ReportDocument.Section.Text(null, "x"))));
        String letter = latin1(new PdfWriter().write(document(ReportDocument.PageSize.LETTER,
                ReportDocument.Orientation.LANDSCAPE, new ReportDocument.Section.Text(null, "x"))));

        assertTrue(a4.contains("/MediaBox [0 0 595 842]"), a4);
        assertTrue(letter.contains("/MediaBox [0 0 792 612]"), letter);
    }

    @Test
    void breaksLongTablesIntoMultiplePages() throws Exception {
        ReportDocument.Section table = new ReportDocument.Section.Table("Rows",
                List.of("name", "value"), rows(200));

        String content = latin1(new PdfWriter().write(document(ReportDocument.PageSize.A4,
                ReportDocument.Orientation.PORTRAIT, table)));

        assertTrue(pageCount(content) >= 2, "page count " + pageCount(content));
        assertTrue(content.contains("(name) Tj"), "table header is drawn");
        assertTrue(content.contains("(row 0) Tj"), content);
    }

    @Test
    void splitsOneTallRowAcrossPagesWithoutLeavingTheMediaBox() throws Exception {
        StringBuilder cell = new StringBuilder();
        for (int index = 0; index < 100; index++) {
            if (index > 0) {
                cell.append(' ');
            }
            cell.append(String.format(java.util.Locale.ROOT, "line%03d", index))
                    .append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        }
        ReportDocument.Section table = new ReportDocument.Section.Table("Tall",
                List.of("body"), List.of(List.of(cell.toString())));

        String content = latin1(new PdfWriter().write(document(ReportDocument.PageSize.A4,
                ReportDocument.Orientation.PORTRAIT, table)));

        assertTrue(pageCount(content) >= 3, "a 100-line cell needs several pages: "
                + pageCount(content));
        assertTrue(content.contains("line000"), content);
        assertTrue(content.contains("line099"), "the tail of the row must be kept");
        assertTrue(content.indexOf("line099") > content.indexOf("line000"));
        Matcher positions = Pattern.compile("1 0 0 1 [0-9.]+ ([0-9.\\-]+) Tm").matcher(content);
        int drawn = 0;
        while (positions.find()) {
            double y = Double.parseDouble(positions.group(1));
            assertTrue(y >= 0 && y <= 842, "text outside A4 MediaBox: y=" + y);
            drawn++;
        }
        assertTrue(drawn >= 100, "drawn lines " + drawn);
    }

    @Test
    void explicitPageBreakStartsANewPage() throws Exception {
        String content = latin1(new PdfWriter().write(document(ReportDocument.PageSize.A4,
                ReportDocument.Orientation.PORTRAIT,
                new ReportDocument.Section.Text(null, "one"),
                new ReportDocument.Section.PageBreak(null),
                new ReportDocument.Section.Text(null, "two"))));

        assertEquals(2, pageCount(content));
        assertTrue(content.contains("(one) Tj"), content);
        assertTrue(content.contains("(two) Tj"), content);
    }

    @Test
    void embedsPngAndJpegImages() throws Exception {
        byte[] png = image("png", 10, 6);
        byte[] jpeg = image("jpg", 12, 8);
        String content = latin1(new PdfWriter().write(document(ReportDocument.PageSize.A4,
                ReportDocument.Orientation.PORTRAIT,
                new ReportDocument.Section.Image("Png", "/tmp/a.png", "a", 120, png),
                new ReportDocument.Section.Image("Jpeg", "/tmp/b.jpg", "b", 0, jpeg))));

        assertTrue(content.contains("/Subtype /Image"), content);
        assertTrue(content.contains("/Width 10"), content);
        assertTrue(content.contains("/Height 6"), content);
        assertTrue(content.contains("/Width 12"), content);
        assertTrue(content.contains("/Filter /FlateDecode"), content);
        assertTrue(content.contains("/Im1 Do"), content);
        assertTrue(content.contains("/Im2 Do"), content);
    }

    @Test
    void rejectsUnsupportedImageBytes() {
        ReportDocument.Section image = new ReportDocument.Section.Image("Bad", "/tmp/notes.txt",
                "notes", 0, "not an image".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new PdfWriter().write(document(ReportDocument.PageSize.A4,
                        ReportDocument.Orientation.PORTRAIT, image)));
        assertTrue(failure.getMessage().contains("image"), failure.getMessage());
    }

    @Test
    void isDeterministicAndHasNoTimestamps() throws Exception {
        ReportDocument document = new ReportDocument(ReportDocument.Format.PDF, "Report", null, null,
                false, ReportDocument.Theme.DEFAULT, null, null,
                List.of(new ReportDocument.Section.Table("Rows", List.of("a", "b"),
                        Arrays.asList(Arrays.asList("1", "2"), Arrays.asList("3", null)))));

        String first = latin1(new PdfWriter().write(document));
        String second = latin1(new PdfWriter().write(document));

        assertEquals(first, second, "two renders of the same document must be identical");
        assertTrue(!first.contains("/CreationDate") && !first.contains("/ModDate"),
                "pdf must not contain timestamps");
    }
}
