package com.dap.nativeengine.report;

import com.dap.nativeengine.report.ReportDocument.Section;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal deterministic OOXML presentation writer built on {@link ZipOutputStream}. The
 * package contains only what PowerPoint needs to open a deck: content types, package and
 * part relationships, one slide master with a blank layout, a theme, one slide per section
 * (a {@code page_break} does not create a slide) and the media parts for embedded images.
 * Slides are built from plain text boxes, a DrawingML table or a picture, so no placeholder
 * inheritance from the master is required. Zip entry timestamps are fixed, so the same
 * document always produces the same bytes.
 */
public final class PptxWriter {
    private static final long EMU_PER_POINT = 12700;
    private static final long EMU_PER_PIXEL = 9525;
    private static final long DEFAULT_CX = 12192000L;
    private static final long DEFAULT_CY = 6858000L;
    private static final long SLIDE_MARGIN = 457200L;
    private static final long TITLE_Y = 274638L;
    private static final long TITLE_HEIGHT = 800100L;
    private static final long BODY_Y = 1200000L;
    private static final long TABLE_ROW_HEIGHT = 370840L;
    private static final int MAX_TABLE_ROWS = 12;
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 0);

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String GROUP_TREE = """
            <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
            <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>
            <a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
            """;

    /** A media part embedded in the package. */
    private record Media(String fileName, String mediaType, byte[] data, int pixelWidth,
                         int pixelHeight) {
    }

    /** An image placed on a slide with its configured width (0 = fit the slide). */
    private record Placed(Media media, int width) {
    }

    /** One slide: exactly one of paragraphs, table cells or images is set. */
    private record Slide(String title, List<String> paragraphs, List<String> columns,
                         List<List<String>> rows, List<Placed> images, int maxRows, String note) {
    }

    private record Deck(List<Slide> slides, List<Media> media) {
    }

    public byte[] write(ReportDocument document) throws IOException {
        Deck deck = deck(document);
        if (deck.slides().isEmpty()) {
            throw new IllegalArgumentException("pptx: the presentation needs at least one slide; "
                    + "sections of kind page_break do not create one");
        }
        long[] size = slideSize(document);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            put(zip, "[Content_Types].xml", contentTypes(deck));
            put(zip, "_rels/.rels", rootRels());
            put(zip, "ppt/presentation.xml", presentation(deck, size));
            put(zip, "ppt/_rels/presentation.xml.rels", presentationRels(deck));
            put(zip, "ppt/slideMasters/slideMaster1.xml", slideMaster());
            put(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", slideMasterRels());
            put(zip, "ppt/slideLayouts/slideLayout1.xml", slideLayout());
            put(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", slideLayoutRels());
            put(zip, "ppt/theme/theme1.xml", theme());
            for (int index = 0; index < deck.slides().size(); index++) {
                Slide slide = deck.slides().get(index);
                put(zip, "ppt/slides/slide" + (index + 1) + ".xml",
                        slideXml(slide, size, document.theme()));
                if (!slide.images().isEmpty()) {
                    put(zip, "ppt/slides/_rels/slide" + (index + 1) + ".xml.rels", slideRels(slide));
                }
            }
            for (Media media : deck.media()) {
                put(zip, "ppt/media/" + media.fileName(), media.data());
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------ model

    private static Deck deck(ReportDocument document) throws IOException {
        Map<String, Media> media = new LinkedHashMap<>();
        List<Slide> slides = new ArrayList<>();
        for (Section section : document.sections()) {
            switch (section) {
                case Section.Text text -> slides.add(new Slide(text.title(), lines(text.text()),
                        null, null, List.of(), 0, null));
                case Section.Table table -> {
                    int limit = table.maxRows() == null ? MAX_TABLE_ROWS : table.maxRows();
                    slides.add(new Slide(table.title(), null, table.columns(), table.rows(), List.of(),
                            limit, truncationNote(table.rows(), limit)));
                }
                case Section.Image image -> slides.add(new Slide(image.title(), null, null, null,
                        List.of(new Placed(media(image, media), image.width())), 0, null));
                case Section.PageBreak ignored -> {
                    // a presentation is paginated by slides: an explicit break adds nothing
                }
            }
        }
        return new Deck(slides, List.copyOf(media.values()));
    }

    private static Media media(Section.Image image, Map<String, Media> media) throws IOException {
        Media existing = media.get(image.path());
        if (existing != null) {
            return existing;
        }
        BufferedImage decoded;
        try (ByteArrayInputStream input = new ByteArrayInputStream(image.data())) {
            decoded = ImageIO.read(input);
        }
        if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
            throw new IllegalArgumentException("pptx: unsupported image format for '" + image.path()
                    + "': expected png, jpeg or gif");
        }
        Media created = new Media("image" + (media.size() + 1) + "." + image.extension(),
                image.mediaType(), image.data(), decoded.getWidth(), decoded.getHeight());
        media.put(image.path(), created);
        return created;
    }

    private static List<String> lines(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        return List.of(normalized.split("\n", -1));
    }

    /** An explicit in-body note: a truncated table must say how much of it was shown. */
    private static String truncationNote(List<List<String>> rows, int limit) {
        return rows.size() > limit ? "shown " + limit + " of " + rows.size() + " rows" : null;
    }

    /** Widescreen by default; an explicit page size and orientation map to point dimensions. */
    private static long[] slideSize(ReportDocument document) {
        if (document.pageSize() == null && document.orientation() == null) {
            return new long[]{DEFAULT_CX, DEFAULT_CY};
        }
        ReportDocument.PageSize size = document.pageSize() == null
                ? ReportDocument.PageSize.A4 : document.pageSize();
        ReportDocument.Orientation orientation = document.orientation() == null
                ? ReportDocument.Orientation.LANDSCAPE : document.orientation();
        long width = size == ReportDocument.PageSize.LETTER ? 612 : 595;
        long height = size == ReportDocument.PageSize.LETTER ? 792 : 842;
        if (orientation == ReportDocument.Orientation.PORTRAIT) {
            return new long[]{width * EMU_PER_POINT, height * EMU_PER_POINT};
        }
        return new long[]{height * EMU_PER_POINT, width * EMU_PER_POINT};
    }

    // ------------------------------------------------------------ parts

    private static String contentTypes(Deck deck) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        out.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n");
        out.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n");
        out.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>\n");
        out.append("<Default Extension=\"png\" ContentType=\"image/png\"/>\n");
        out.append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>\n");
        out.append("<Default Extension=\"gif\" ContentType=\"image/gif\"/>\n");
        out.append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>\n");
        out.append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>\n");
        out.append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>\n");
        out.append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>\n");
        for (int index = 1; index <= deck.slides().size(); index++) {
            out.append("<Override PartName=\"/ppt/slides/slide").append(index)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>\n");
        }
        out.append("</Types>\n");
        return out.toString();
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "/officeDocument\" Target=\"ppt/presentation.xml\"/>\n"
                + "</Relationships>\n";
    }

    private static String presentation(Deck deck, long[] size) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        out.append("<p:presentation xmlns:a=\"").append(A).append("\" xmlns:r=\"").append(R)
                .append("\" xmlns:p=\"").append(P).append("\">\n");
        out.append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>\n");
        out.append("<p:sldIdLst>\n");
        for (int index = 0; index < deck.slides().size(); index++) {
            out.append("<p:sldId id=\"").append(256 + index).append("\" r:id=\"rId")
                    .append(index + 2).append("\"/>\n");
        }
        out.append("</p:sldIdLst>\n");
        out.append("<p:sldSz cx=\"").append(size[0]).append("\" cy=\"").append(size[1])
                .append("\"/>\n");
        out.append("<p:notesSz cx=\"6858000\" cy=\"9144000\"/>\n");
        out.append("</p:presentation>\n");
        return out.toString();
    }

    private static String presentationRels(Deck deck) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        out.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n");
        out.append("<Relationship Id=\"rId1\" Type=\"").append(REL)
                .append("/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>\n");
        for (int index = 0; index < deck.slides().size(); index++) {
            out.append("<Relationship Id=\"rId").append(index + 2).append("\" Type=\"")
                    .append(REL).append("/slide\" Target=\"slides/slide")
                    .append(index + 1).append(".xml\"/>\n");
        }
        out.append("</Relationships>\n");
        return out.toString();
    }

    private static String slideMaster() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:sldMaster xmlns:a=\"" + A + "\" xmlns:r=\"" + R + "\" xmlns:p=\"" + P + "\">\n"
                + "<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill>"
                + "<a:effectLst/></p:bgPr></p:bg><p:spTree>\n"
                + GROUP_TREE
                + "</p:spTree></p:cSld>\n"
                + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" "
                + "accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" "
                + "accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>\n"
                + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>\n"
                + "</p:sldMaster>\n";
    }

    private static String slideMasterRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>\n"
                + "<Relationship Id=\"rId2\" Type=\"" + REL + "/theme\" Target=\"../theme/theme1.xml\"/>\n"
                + "</Relationships>\n";
    }

    private static String slideLayout() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<p:sldLayout xmlns:a=\"" + A + "\" xmlns:r=\"" + R + "\" xmlns:p=\"" + P + "\" "
                + "type=\"blank\" preserve=\"1\">\n"
                + "<p:cSld name=\"Blank\"><p:spTree>\n"
                + GROUP_TREE
                + "</p:spTree></p:cSld>\n"
                + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>\n"
                + "</p:sldLayout>\n";
    }

    private static String slideLayoutRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>\n"
                + "</Relationships>\n";
    }

    private static String theme() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<a:theme xmlns:a=\"" + A + "\" name=\"DAP\"><a:themeElements>\n"
                + "<a:clrScheme name=\"DAP\">"
                + "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>"
                + "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"
                + "<a:dk2><a:srgbClr val=\"1F4E79\"/></a:dk2>"
                + "<a:lt2><a:srgbClr val=\"EEF3F8\"/></a:lt2>"
                + "<a:accent1><a:srgbClr val=\"1F4E79\"/></a:accent1>"
                + "<a:accent2><a:srgbClr val=\"2E75B6\"/></a:accent2>"
                + "<a:accent3><a:srgbClr val=\"9DC3E6\"/></a:accent3>"
                + "<a:accent4><a:srgbClr val=\"C55A11\"/></a:accent4>"
                + "<a:accent5><a:srgbClr val=\"70AD47\"/></a:accent5>"
                + "<a:accent6><a:srgbClr val=\"7030A0\"/></a:accent6>"
                + "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink>"
                + "<a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>"
                + "</a:clrScheme>\n"
                + "<a:fontScheme name=\"DAP\">"
                + "<a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>"
                + "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>"
                + "</a:fontScheme>\n"
                + "<a:fmtScheme name=\"DAP\">"
                + "<a:fillStyleLst>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "</a:fillStyleLst>"
                + "<a:lnStyleLst>"
                + "<a:ln w=\"6350\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>"
                + "<a:ln w=\"12700\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>"
                + "<a:ln w=\"19050\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>"
                + "</a:lnStyleLst>"
                + "<a:effectStyleLst>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "<a:effectStyle><a:effectLst/></a:effectStyle>"
                + "</a:effectStyleLst>"
                + "<a:bgFillStyleLst>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
                + "</a:bgFillStyleLst>"
                + "</a:fmtScheme>\n"
                + "</a:themeElements></a:theme>\n";
    }

    private static String slideXml(Slide slide, long[] size, ReportDocument.Theme theme) {
        long contentCx = size[0] - 2 * SLIDE_MARGIN;
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        out.append("<p:sld xmlns:a=\"").append(A).append("\" xmlns:r=\"").append(R)
                .append("\" xmlns:p=\"").append(P).append("\">\n");
        out.append("<p:cSld><p:spTree>\n").append(GROUP_TREE);
        if (slide.title() != null && !slide.title().isBlank()) {
            out.append(textShape(2, "Title", SLIDE_MARGIN, TITLE_Y, contentCx, TITLE_HEIGHT,
                    List.of(slide.title()), 2400, theme.accent()));
        }
        if (slide.paragraphs() != null) {
            out.append(textShape(3, "Body", SLIDE_MARGIN, BODY_Y, contentCx,
                    size[1] - BODY_Y - SLIDE_MARGIN, slide.paragraphs(), 1400, 0));
        }
        if (slide.columns() != null) {
            long rowHeight = tableRowHeight(slide, size);
            out.append(tableFrame(3, slide, SLIDE_MARGIN, BODY_Y, contentCx, rowHeight));
            if (slide.note() != null) {
                long noteY = BODY_Y + tableRows(slide) * rowHeight + 80_000;
                out.append(textShape(4, "Truncation", SLIDE_MARGIN, noteY, contentCx, 240_000,
                        List.of(slide.note()), 1000, 0));
            }
        }
        if (!slide.images().isEmpty()) {
            out.append(picture(3, slide.images().get(0), SLIDE_MARGIN, BODY_Y, contentCx,
                    size[1] - BODY_Y - SLIDE_MARGIN));
        }
        out.append("</p:spTree></p:cSld>\n<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>\n");
        out.append("</p:sld>\n");
        return out.toString();
    }

    private static String textShape(int id, String name, long x, long y, long cx, long cy,
                                    List<String> lines, int size, int color) {
        StringBuilder out = new StringBuilder();
        out.append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"").append(name)
                .append("\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>");
        out.append("<p:spPr><a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y)
                .append("\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>");
        out.append("<p:txBody><a:bodyPr/><a:lstStyle/>");
        for (String line : lines) {
            out.append(paragraph(line, size, color != 0, color));
        }
        out.append("</p:txBody></p:sp>");
        return out.toString();
    }

    private static String paragraph(String line, int size, boolean bold, int color) {
        StringBuilder out = new StringBuilder("<a:p>");
        if (!line.isEmpty()) {
            out.append("<a:r><a:rPr lang=\"en-US\" sz=\"").append(size).append('"');
            if (bold) {
                out.append(" b=\"1\"");
            }
            if (color != 0) {
                out.append("><a:solidFill><a:srgbClr val=\"").append(hex(color))
                        .append("\"/></a:solidFill></a:rPr>");
            } else {
                out.append("/>");
            }
            out.append("<a:t>").append(xml(line)).append("</a:t></a:r>");
        }
        out.append("<a:endParaRPr lang=\"en-US\" sz=\"").append(size).append("\"/></a:p>");
        return out.toString();
    }

    /** Header, printed rows and the optional "more rows" summary row. */
    private static long tableRows(Slide slide) {
        return Math.min(slide.rows().size(), slide.maxRows()) + 1L
                + (slide.rows().size() > slide.maxRows() ? 1 : 0);
    }

    /** Fit the table and its optional note inside the slide without changing normal decks. */
    private static long tableRowHeight(Slide slide, long[] size) {
        long noteSpace = slide.note() == null ? 0 : 320_000;
        long available = size[1] - BODY_Y - SLIDE_MARGIN - noteSpace;
        return Math.min(TABLE_ROW_HEIGHT, available / Math.max(1, tableRows(slide)));
    }

    private static String tableFrame(int id, Slide slide, long x, long y, long cx, long rowHeight) {
        List<String> columns = slide.columns();
        int count = Math.max(1, columns.size());
        long columnWidth = cx / count;
        List<List<String>> rows = slide.rows();
        long rowsHeight = tableRows(slide) * rowHeight;
        StringBuilder out = new StringBuilder();
        out.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"").append(id)
                .append("\" name=\"Table\"/><p:cNvGraphicFramePr>"
                        + "<a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/>"
                        + "</p:nvGraphicFramePr>");
        out.append("<p:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y)
                .append("\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(rowsHeight)
                .append("\"/></p:xfrm>");
        out.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">");
        out.append("<a:tbl><a:tblPr firstRow=\"1\"/><a:tblGrid>");
        for (int index = 0; index < count; index++) {
            out.append("<a:gridCol w=\"").append(columnWidth).append("\"/>");
        }
        out.append("</a:tblGrid>");
        out.append(tableRow(columns, columnWidth, true, rowHeight));
        int printed = 0;
        for (List<String> dataRow : rows) {
            if (printed >= slide.maxRows()) {
                break;
            }
            out.append(tableRow(dataRow, columnWidth, false, rowHeight));
            printed++;
        }
        if (rows.size() > slide.maxRows()) {
            List<String> summary = new ArrayList<>();
            summary.add("\u2026 " + (rows.size() - slide.maxRows()) + " more rows");
            for (int index = 1; index < count; index++) {
                summary.add("");
            }
            out.append(tableRow(summary, columnWidth, false, rowHeight));
        }
        out.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>");
        return out.toString();
    }

    private static String tableRow(List<String> cells, long columnWidth, boolean header,
                                   long rowHeight) {
        StringBuilder out = new StringBuilder();
        out.append("<a:tr h=\"").append(rowHeight).append("\">");
        int count = cells.size();
        for (int index = 0; index < count; index++) {
            String value = cells.get(index);
            out.append("<a:tc><a:txBody><a:bodyPr/><a:lstStyle/>");
            out.append(paragraph(value == null ? "" : value, 1000, header, 0));
            out.append("</a:txBody><a:tcPr/></a:tc>");
        }
        out.append("</a:tr>");
        return out.toString();
    }

    private static String picture(int id, Placed placed, long x, long y, long cx, long cy) {
        Media media = placed.media();
        long width = placed.width() > 0 ? placed.width() * EMU_PER_PIXEL : cx;
        width = Math.min(width, cx);
        long height = width * media.pixelHeight() / media.pixelWidth();
        if (height > cy) {
            height = cy;
            width = height * media.pixelWidth() / media.pixelHeight();
        }
        return "<p:pic><p:nvPicPr><p:cNvPr id=\"" + id + "\" name=\"Image\"/>"
                + "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/>"
                + "</p:nvPicPr><p:blipFill><a:blip r:embed=\"rId1\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                + "<p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/>"
                + "<a:ext cx=\"" + width + "\" cy=\"" + height + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>";
    }

    private static String slideRels(Slide slide) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        out.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n");
        for (int index = 0; index < slide.images().size(); index++) {
            out.append("<Relationship Id=\"rId").append(index + 1).append("\" Type=\"")
                    .append(REL).append("/image\" Target=\"../media/")
                    .append(slide.images().get(index).media().fileName()).append("\"/>\n");
        }
        out.append("</Relationships>\n");
        return out.toString();
    }

    // ------------------------------------------------------------ helpers

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        put(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(FIXED_TIME);
        entry.setSize(content.length);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }
}
