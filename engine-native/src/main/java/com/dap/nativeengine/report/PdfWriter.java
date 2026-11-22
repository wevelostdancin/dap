package com.dap.nativeengine.report;

import com.dap.nativeengine.report.ReportDocument.Section;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.zip.Deflater;

/**
 * Minimal deterministic PDF writer built on the JDK only. Layout is measured with Java2D
 * ({@link TextLayout}) and emitted as PDF text/line/image operators over the standard
 * Helvetica faces; pages are A4 or Letter, portrait or landscape. PNG and JPEG images are
 * decoded to RGB and embedded as Flate-compressed image XObjects. The file has no
 * {@code /CreationDate}, {@code /ModDate} or document id, so the same document always
 * produces the same bytes. Text outside WinAnsi (for example Cyrillic) is replaced with
 * {@code ?} because the writer embeds no font program.
 */
public final class PdfWriter {
    private static final int MARGIN = 50;
    private static final double PADDING = 4;
    private static final int BLACK = 0x000000;
    private static final int MUTED = 0x555555;
    private static final Font TITLE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 22);
    private static final Font HEADING_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 15);
    private static final Font SUBTITLE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font AUTHOR_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final Font BODY_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private static final Font CELL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 9);
    private static final Font HEADER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 9);

    public byte[] write(ReportDocument document) throws IOException {
        ReportDocument.PageSize pageSize = document.pageSize() == null
                ? ReportDocument.PageSize.A4 : document.pageSize();
        ReportDocument.Orientation orientation = document.orientation() == null
                ? ReportDocument.Orientation.PORTRAIT : document.orientation();
        boolean portrait = orientation == ReportDocument.Orientation.PORTRAIT;
        int pageWidth;
        int pageHeight;
        if (pageSize == ReportDocument.PageSize.LETTER) {
            pageWidth = portrait ? 612 : 792;
            pageHeight = portrait ? 792 : 612;
        } else {
            pageWidth = portrait ? 595 : 842;
            pageHeight = portrait ? 842 : 595;
        }
        try (TextLayout layout = new TextLayout()) {
            Layout documentLayout = new Layout(document, layout, pageWidth, pageHeight);
            documentLayout.render();
            return documentLayout.toPdf();
        }
    }

    /** One rendered page: its content stream and the image resources it references. */
    private static final class Page {
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        private final LinkedHashSet<String> imageNames = new LinkedHashSet<>();
    }

    /** An embedded RGB image: resource name plus Flate-compressed sample bytes. */
    private record ImageResource(String name, byte[] rgb, int width, int height) {
    }

    private static final class Layout {
        private final ReportDocument document;
        private final TextLayout layout;
        private final int pageWidth;
        private final int pageHeight;
        private final List<Page> pages = new ArrayList<>();
        private final Map<String, ImageResource> images = new LinkedHashMap<>();
        private Page page;
        private double y;

        private Layout(ReportDocument document, TextLayout layout, int pageWidth, int pageHeight) {
            this.document = document;
            this.layout = layout;
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
        }

        private void render() throws IOException {
            newPage();
            header();
            for (Section section : document.sections()) {
                if (section.title() != null && !section.title().isBlank()) {
                    heading(section.title());
                }
                switch (section) {
                    case Section.Table table -> table(table);
                    case Section.Text text -> paragraphs(text.text());
                    case Section.Image image -> image(image);
                    case Section.PageBreak ignored -> newPage();
                }
                y += 10;
            }
        }

        // ------------------------------------------------------------ blocks

        private void header() {
            if (document.title() == null || document.title().isBlank()) {
                return;
            }
            y = wrapped(document.title(), TITLE_FONT, y, document.theme().accent()) + 4;
            if (document.subtitle() != null && !document.subtitle().isBlank()) {
                y = wrapped(document.subtitle(), SUBTITLE_FONT, y, MUTED) + 2;
            }
            if (document.author() != null && !document.author().isBlank()) {
                y = wrapped(document.author(), AUTHOR_FONT, y, MUTED) + 2;
            }
            rule(y + 4, MARGIN, pageWidth - MARGIN);
            y += 16;
        }

        private void heading(String title) {
            ensure(layout.lineHeight(HEADING_FONT) + layout.lineHeight(BODY_FONT));
            y += 6;
            y = wrapped(title, HEADING_FONT, y, document.theme().accent()) + 4;
        }

        private void paragraphs(String value) {
            String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
            for (String line : normalized.split("\n", -1)) {
                for (String wrapped : layout.wrap(line, BODY_FONT, contentWidth())) {
                    ensure(layout.lineHeight(BODY_FONT));
                    text(wrapped, BODY_FONT, MARGIN, y, BLACK);
                    y += layout.lineHeight(BODY_FONT);
                }
            }
        }

        private void table(Section.Table table) {
            if (table.columns().isEmpty()) {
                return;
            }
            double[] widths = widths(table);
            ensure(rowHeight(table.columns(), HEADER_FONT, widths) + layout.lineHeight(CELL_FONT));
            headerRow(table.columns(), widths);
            for (List<String> dataRow : table.rows()) {
                // A single wrapped row can be taller than a page. Draw it in slices, repeating
                // the header on every continuation page, so no text is written outside MediaBox.
                List<List<String>> lines = wrappedCells(dataRow, widths);
                int totalLines = 0;
                for (List<String> columnLines : lines) {
                    totalLines = Math.max(totalLines, columnLines.size());
                }
                int firstLine = 0;
                while (firstLine < totalLines) {
                    int fits = (int) Math.floor(
                            (pageHeight - MARGIN - y - 2 * PADDING) / layout.lineHeight(CELL_FONT));
                    if (fits < 1) {
                        newPage();
                        headerRow(table.columns(), widths);
                        continue;
                    }
                    int count = Math.min(fits, totalLines - firstLine);
                    rowSlice(lines, firstLine, count, widths);
                    y += count * layout.lineHeight(CELL_FONT) + 2 * PADDING;
                    firstLine += count;
                    if (firstLine < totalLines) {
                        newPage();
                        headerRow(table.columns(), widths);
                    }
                }
            }
        }

        private void headerRow(List<String> columns, double[] widths) {
            y = row(columns, HEADER_FONT, widths, y);
            rule(y, MARGIN, MARGIN + total(widths));
        }

        private List<List<String>> wrappedCells(List<String> cells, double[] widths) {
            List<List<String>> lines = new ArrayList<>();
            for (int index = 0; index < widths.length; index++) {
                String cell = index < cells.size() ? cells.get(index) : null;
                lines.add(layout.wrap(cell == null ? "" : cell, CELL_FONT,
                        Math.max(8, widths[index] - 2 * PADDING)));
            }
            return lines;
        }

        private void rowSlice(List<List<String>> lines, int from, int count, double[] widths) {
            double x = MARGIN;
            for (int index = 0; index < widths.length; index++) {
                double lineY = y + PADDING;
                List<String> columnLines = lines.get(index);
                for (int line = from; line < Math.min(from + count, columnLines.size()); line++) {
                    text(columnLines.get(line), CELL_FONT, x + PADDING, lineY, BLACK);
                    lineY += layout.lineHeight(CELL_FONT);
                }
                x += widths[index];
            }
        }

        private void image(Section.Image image) throws IOException {
            BufferedImage decoded;
            try (ByteArrayInputStream input = new ByteArrayInputStream(image.data())) {
                decoded = ImageIO.read(input);
            }
            if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                throw new IllegalArgumentException("pdf: unsupported image format for '"
                        + image.path() + "': expected png, jpeg or gif");
            }
            double width = image.width() > 0 ? image.width() : contentWidth();
            width = Math.min(width, contentWidth());
            double height = width * decoded.getHeight() / decoded.getWidth();
            double maxHeight = pageHeight - 2 * MARGIN;
            if (height > maxHeight) {
                height = maxHeight;
                width = height * decoded.getWidth() / decoded.getHeight();
            }
            ensure(height);
            ImageResource resource = images.get(image.path());
            if (resource == null) {
                resource = new ImageResource("Im" + (images.size() + 1), rgb(decoded),
                        decoded.getWidth(), decoded.getHeight());
                images.put(image.path(), resource);
            }
            page.imageNames.add(resource.name());
            double bottom = pageHeight - y - height;
            write("q " + num(width) + " 0 0 " + num(height) + " " + num(MARGIN) + " "
                    + num(bottom) + " cm /" + resource.name() + " Do Q\n");
            y += height + 8;
        }

        // ------------------------------------------------------------ primitives

        private double wrapped(String value, Font font, double top, int rgb) {
            for (String line : layout.wrap(value, font, contentWidth())) {
                if (top + layout.lineHeight(font) > pageHeight - MARGIN) {
                    newPage();
                    top = y;
                }
                text(line, font, MARGIN, top, rgb);
                top += layout.lineHeight(font);
            }
            return top;
        }

        private double row(List<String> cells, Font font, double[] widths, double top) {
            double height = rowHeight(cells, font, widths);
            double x = MARGIN;
            for (int index = 0; index < widths.length; index++) {
                String cell = index < cells.size() ? cells.get(index) : null;
                List<String> lines = layout.wrap(cell == null ? "" : cell, font,
                        Math.max(8, widths[index] - 2 * PADDING));
                double lineY = top + PADDING;
                for (String line : lines) {
                    text(line, font, x + PADDING, lineY, BLACK);
                    lineY += layout.lineHeight(font);
                }
                x += widths[index];
            }
            return top + height;
        }

        private double rowHeight(List<String> cells, Font font, double[] widths) {
            double lines = 1;
            for (int index = 0; index < widths.length; index++) {
                String cell = index < cells.size() ? cells.get(index) : null;
                int count = layout.wrap(cell == null ? "" : cell, font,
                        Math.max(8, widths[index] - 2 * PADDING)).size();
                lines = Math.max(lines, count);
            }
            return lines * layout.lineHeight(font) + 2 * PADDING;
        }

        private double[] widths(Section.Table table) {
            double[] widths = new double[table.columns().size()];
            for (int index = 0; index < widths.length; index++) {
                double natural = layout.width(table.columns().get(index), HEADER_FONT);
                for (List<String> row : table.rows()) {
                    String cell = index < row.size() ? row.get(index) : null;
                    if (cell != null) {
                        natural = Math.max(natural, layout.width(cell, CELL_FONT));
                    }
                }
                widths[index] = Math.min(natural, 220) + 2 * PADDING;
            }
            double total = total(widths);
            if (total > contentWidth()) {
                double scale = contentWidth() / total;
                for (int index = 0; index < widths.length; index++) {
                    widths[index] = Math.max(20, widths[index] * scale);
                }
            }
            return widths;
        }

        private void text(String value, Font font, double x, double top, int rgb) {
            double baseline = pageHeight - top - layout.ascent(font);
            write("BT /" + (font.isBold() ? "F2" : "F1") + " " + font.getSize() + " Tf "
                    + color(rgb) + " 1 0 0 1 " + num(x) + " " + num(baseline) + " Tm "
                    + pdfString(value) + " Tj ET\n");
        }

        private void rule(double top, double fromX, double toX) {
            double baseline = pageHeight - top;
            write("q 0.5 w 0.65 0.65 0.65 RG " + num(fromX) + " " + num(baseline) + " m "
                    + num(toX) + " " + num(baseline) + " l S Q\n");
        }

        private void ensure(double height) {
            if (y + height > pageHeight - MARGIN) {
                newPage();
            }
        }

        private void newPage() {
            page = new Page();
            pages.add(page);
            y = MARGIN;
        }

        private void write(String operator) {
            page.content.writeBytes(operator.getBytes(StandardCharsets.ISO_8859_1));
        }

        private double contentWidth() {
            return pageWidth - 2.0 * MARGIN;
        }

        // ------------------------------------------------------------ file

        private byte[] toPdf() {
            int catalogId = 1;
            int pagesId = 2;
            int regularFontId = 3;
            int boldFontId = 4;
            List<int[]> pageIds = new ArrayList<>();
            int nextId = 5;
            for (int index = 0; index < pages.size(); index++) {
                pageIds.add(new int[]{nextId++, nextId++});
            }
            Map<String, Integer> imageIds = new LinkedHashMap<>();
            for (ImageResource image : images.values()) {
                imageIds.put(image.name(), nextId++);
            }
            int objectCount = nextId - 1;
            Map<Integer, byte[]> bodies = new TreeMap<>();

            bodies.put(catalogId, ascii("<< /Type /Catalog /Pages 2 0 R >>"));
            StringJoiner kids = new StringJoiner(" ");
            for (int[] ids : pageIds) {
                kids.add(ids[0] + " 0 R");
            }
            bodies.put(pagesId, ascii("<< /Type /Pages /Kids [" + kids + "] /Count "
                    + pages.size() + " >>"));
            bodies.put(regularFontId, ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica"
                    + " /Encoding /WinAnsiEncoding >>"));
            bodies.put(boldFontId, ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold"
                    + " /Encoding /WinAnsiEncoding >>"));

            for (int index = 0; index < pages.size(); index++) {
                Page current = pages.get(index);
                int[] ids = pageIds.get(index);
                byte[] content = current.content.toByteArray();
                StringBuilder resources = new StringBuilder();
                resources.append("<< /Font << /F1 ").append(regularFontId).append(" 0 R /F2 ")
                        .append(boldFontId).append(" 0 R >>");
                if (!current.imageNames.isEmpty()) {
                    resources.append(" /XObject << ");
                    for (String name : current.imageNames) {
                        resources.append('/').append(name).append(' ')
                                .append(imageIds.get(name)).append(" 0 R ");
                    }
                    resources.append(">>");
                }
                resources.append(" >>");
                bodies.put(ids[0], ascii("<< /Type /Page /Parent " + pagesId + " 0 R"
                        + " /MediaBox [0 0 " + pageWidth + " " + pageHeight + "]"
                        + " /Resources " + resources + " /Contents " + ids[1] + " 0 R >>"));
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                stream.writeBytes(ascii("<< /Length " + content.length + " >>\nstream\n"));
                stream.writeBytes(content);
                stream.writeBytes(ascii("\nendstream"));
                bodies.put(ids[1], stream.toByteArray());
            }

            for (ImageResource image : images.values()) {
                byte[] compressed = deflate(image.rgb());
                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                stream.writeBytes(ascii("<< /Type /XObject /Subtype /Image /Width " + image.width()
                        + " /Height " + image.height()
                        + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode"
                        + " /Length " + compressed.length + " >>\nstream\n"));
                stream.writeBytes(compressed);
                stream.writeBytes(ascii("\nendstream"));
                bodies.put(imageIds.get(image.name()), stream.toByteArray());
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(ascii("%PDF-1.4\n"));
            out.writeBytes(new byte[]{(byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});
            int[] offsets = new int[objectCount + 1];
            for (int id = 1; id <= objectCount; id++) {
                offsets[id] = out.size();
                out.writeBytes(ascii(id + " 0 obj\n"));
                out.writeBytes(bodies.get(id));
                out.writeBytes(ascii("\nendobj\n"));
            }
            int xref = out.size();
            out.writeBytes(ascii("xref\n0 " + (objectCount + 1) + "\n0000000000 65535 f \n"));
            for (int id = 1; id <= objectCount; id++) {
                out.writeBytes(ascii(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id])));
            }
            out.writeBytes(ascii("trailer\n<< /Size " + (objectCount + 1) + " /Root 1 0 R >>\n"
                    + "startxref\n" + xref + "\n%%EOF\n"));
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------ helpers

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String color(int rgb) {
        return num(((rgb >> 16) & 0xFF) / 255.0) + " " + num(((rgb >> 8) & 0xFF) / 255.0) + " "
                + num((rgb & 0xFF) / 255.0) + " rg";
    }

    /** PDF literal string; non-WinAnsi characters become octal escapes or {@code ?}. */
    private static String pdfString(String value) {
        StringBuilder out = new StringBuilder("(");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '(' || character == ')') {
                out.append('\\').append(character);
            } else if (character >= 32 && character <= 126) {
                out.append(character);
            } else if (character >= 160 && character <= 255) {
                String octal = Integer.toOctalString(character);
                out.append('\\').append("0".repeat(3 - octal.length())).append(octal);
            } else {
                out.append('?');
            }
        }
        return out.append(')').toString();
    }

    private static double total(double[] values) {
        double total = 0;
        for (double value : values) {
            total += value;
        }
        return total;
    }

    /** Decode any ImageIO-readable raster to 8-bit RGB samples in row-major order. */
    private static byte[] rgb(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        int[] pixels = rgb.getRGB(0, 0, width, height, null, 0, width);
        byte[] samples = new byte[width * height * 3];
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            samples[index * 3] = (byte) ((pixel >> 16) & 0xFF);
            samples[index * 3 + 1] = (byte) ((pixel >> 8) & 0xFF);
            samples[index * 3 + 2] = (byte) (pixel & 0xFF);
        }
        return samples;
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }
}
