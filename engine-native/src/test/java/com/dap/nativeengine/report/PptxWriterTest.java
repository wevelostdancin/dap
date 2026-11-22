package com.dap.nativeengine.report;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PptxWriterTest {
    private static ReportDocument document(ReportDocument.PageSize size,
                                           ReportDocument.Orientation orientation,
                                           ReportDocument.Section... sections) {
        return new ReportDocument(ReportDocument.Format.PPTX, "Deck", null, null, false,
                ReportDocument.Theme.DEFAULT, size, orientation, List.of(sections));
    }

    private static Map<String, byte[]> entries(byte[] pptx) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pptx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String name) {
        byte[] bytes = entries.get(name);
        assertNotNull(bytes, name + " is missing from " + entries.keySet());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00FF00);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void writesOoxmlPackageWithOneSlidePerSection() throws Exception {
        byte[] pptx = new PptxWriter().write(document(null, null,
                new ReportDocument.Section.Text("Intro", "hello"),
                new ReportDocument.Section.Table("Rows", List.of("a", "b"),
                        List.of(List.of("1", "2")))));

        Map<String, byte[]> entries = entries(pptx);

        for (String required : List.of("[Content_Types].xml", "_rels/.rels",
                "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels",
                "ppt/slides/slide1.xml", "ppt/slides/slide2.xml",
                "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml",
                "ppt/theme/theme1.xml")) {
            assertTrue(entries.containsKey(required), required + " missing from " + entries.keySet());
        }
        assertFalse(entries.containsKey("ppt/slides/slide3.xml"), "one slide per section");
        assertTrue(text(entries, "ppt/slides/slide1.xml").contains("<a:t>hello</a:t>"),
                text(entries, "ppt/slides/slide1.xml"));
        assertTrue(text(entries, "ppt/slides/slide2.xml").contains("<a:tbl>"),
                text(entries, "ppt/slides/slide2.xml"));
        assertTrue(text(entries, "ppt/presentation.xml").contains("<p:sldId id=\"256\""),
                text(entries, "ppt/presentation.xml"));
    }

    @Test
    void everyXmlPartIsWellFormed() throws Exception {
        byte[] pptx = new PptxWriter().write(document(ReportDocument.PageSize.A4,
                ReportDocument.Orientation.LANDSCAPE,
                new ReportDocument.Section.Text("Intro", "hello & <world>"),
                new ReportDocument.Section.Image("Chart", "/tmp/chart.png", "chart", 100, png(8, 6))));

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        int parsed = 0;
        for (Map.Entry<String, byte[]> entry : entries(pptx).entrySet()) {
            if (entry.getKey().endsWith(".xml") || entry.getKey().endsWith(".rels")) {
                factory.newDocumentBuilder().parse(new ByteArrayInputStream(entry.getValue()));
                parsed++;
            }
        }
        assertTrue(parsed >= 8, "parsed " + parsed + " xml parts");
    }

    @Test
    void embedsImagesInMediaFolder() throws Exception {
        byte[] pptx = new PptxWriter().write(document(null, null,
                new ReportDocument.Section.Image("Chart", "/tmp/chart.png", "chart", 120, png(8, 6))));

        Map<String, byte[]> entries = entries(pptx);

        assertTrue(entries.containsKey("ppt/media/image1.png"), entries.keySet().toString());
        String slide = text(entries, "ppt/slides/slide1.xml");
        assertTrue(slide.contains("<p:pic>"), slide);
        assertTrue(slide.contains("r:embed=\"rId1\""), slide);
        assertTrue(text(entries, "ppt/slides/_rels/slide1.xml.rels")
                .contains("Target=\"../media/image1.png\""), text(entries, "ppt/slides/_rels/slide1.xml.rels"));
        assertTrue(text(entries, "[Content_Types].xml").contains("Extension=\"png\""),
                text(entries, "[Content_Types].xml"));
    }

    @Test
    void pageBreaksDoNotCreateSlides() throws Exception {
        byte[] pptx = new PptxWriter().write(document(null, null,
                new ReportDocument.Section.Text("One", "first"),
                new ReportDocument.Section.PageBreak(null),
                new ReportDocument.Section.Text("Two", "second")));

        Map<String, byte[]> entries = entries(pptx);

        assertTrue(entries.containsKey("ppt/slides/slide2.xml"), entries.keySet().toString());
        assertFalse(entries.containsKey("ppt/slides/slide3.xml"), entries.keySet().toString());
    }

    @Test
    void rejectsDocumentWithoutSlides() {
        ReportDocument document = document(null, null, new ReportDocument.Section.PageBreak(null));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new PptxWriter().write(document));
        assertTrue(failure.getMessage().contains("slide"), failure.getMessage());
    }

    @Test
    void rejectsUnsupportedImageBytes() {
        ReportDocument document = document(null, null, new ReportDocument.Section.Image("Bad",
                "/tmp/notes.txt", "notes", 0, "not an image".getBytes(StandardCharsets.UTF_8)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new PptxWriter().write(document));
        assertTrue(failure.getMessage().contains("image"), failure.getMessage());
    }

    @Test
    void slideSizeFollowsPageSettings() throws Exception {
        String wide = text(entries(new PptxWriter().write(document(null, null,
                new ReportDocument.Section.Text(null, "x")))), "ppt/presentation.xml");
        assertTrue(wide.contains("<p:sldSz cx=\"12192000\" cy=\"6858000\"/>"), wide);

        String letter = text(entries(new PptxWriter().write(document(
                ReportDocument.PageSize.LETTER, ReportDocument.Orientation.LANDSCAPE,
                new ReportDocument.Section.Text(null, "x")))), "ppt/presentation.xml");
        assertTrue(letter.contains("<p:sldSz cx=\"10058400\" cy=\"7772400\"/>"), letter);
    }

    @Test
    void truncationIsVisibleInTheSlideBodyAndConfigurable() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            rows.add(List.of("row " + index));
        }
        ReportDocument.Section.Table section = new ReportDocument.Section.Table("Rows",
                List.of("a"), rows);
        String defaultSlide = text(entries(new PptxWriter().write(document(null, null, section))),
                "ppt/slides/slide1.xml");
        assertTrue(defaultSlide.contains("<a:t>shown 12 of 30 rows</a:t>"), defaultSlide);
        assertTrue(defaultSlide.contains("<a:t>\u2026 18 more rows</a:t>"), defaultSlide);
        assertEquals(12, count(defaultSlide, "<a:t>row "));

        ReportDocument.Section.Table limited = new ReportDocument.Section.Table("Rows",
                List.of("a"), rows, 5);
        String limitedSlide = text(entries(new PptxWriter().write(document(null, null, limited))),
                "ppt/slides/slide1.xml");
        assertTrue(limitedSlide.contains("<a:t>shown 5 of 30 rows</a:t>"), limitedSlide);
        assertEquals(5, count(limitedSlide, "<a:t>row "));
    }

    @Test
    void aTableThatFitsHasNoTruncationNote() throws Exception {
        List<List<String>> rows = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            rows.add(List.of("row " + index));
        }
        String slide = text(entries(new PptxWriter().write(document(null, null,
                new ReportDocument.Section.Table("Rows", List.of("a"), rows)))),
                "ppt/slides/slide1.xml");
        assertEquals(12, count(slide, "<a:t>row "));
        assertTrue(!slide.contains("shown "), slide);
    }

    private static int count(String value, String needle) {
        int found = 0;
        int index = value.indexOf(needle);
        while (index >= 0) {
            found++;
            index = value.indexOf(needle, index + needle.length());
        }
        return found;
    }

    @Test
    void isDeterministic() throws Exception {
        ReportDocument document = document(null, null,
                new ReportDocument.Section.Table("Rows", List.of("a"),
                        List.of(List.of("1"), List.of("2"))));

        byte[] first = new PptxWriter().write(document);
        byte[] second = new PptxWriter().write(document);

        assertEquals(new String(first, StandardCharsets.ISO_8859_1),
                new String(second, StandardCharsets.ISO_8859_1),
                "two renders of the same document must be identical");
    }
}
