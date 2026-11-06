package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * dap.excel_writer: writes the input table to a minimal .xlsx workbook and passes the plan
 * through. The workbook is assembled with java.util.zip only: the five parts below are enough
 * for Excel, LibreOffice and the DAP excel_reader. Numbers stay numeric, booleans are boolean
 * cells, dates and times are ISO text (Excel would need a style table for real date cells), and
 * NULL is an empty cell.
 */
public final class ExcelWriterExecutor implements NodeExecutor {
    private static final String NODE = "excel_writer";
    private static final String DEFAULT_SHEET = "Sheet1";
    /** Characters Excel forbids in a worksheet name. */
    private static final String INVALID_SHEET_CHARACTERS = "[]:*?/\\";
    private static final String XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String PACKAGE_RELATIONSHIPS =
            "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_RELATIONSHIPS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String SPREADSHEET =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    @Override
    public String nodeId() {
        return "dap.excel_writer";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        String sheet = sheetName(config);

        Path target = IoNodeSupport.targetFile(file, NODE);
        TableData data = input.preview(Integer.MAX_VALUE, context.workflowId(), context.nodeId());

        // Write next to the target and rename, so a failed write never leaves a truncated
        // workbook under the user's path (same pattern as the CSV/Parquet writers).
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            write(temporary, sheet, data);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException(NODE + ": cannot write " + target + ": "
                    + failure.getMessage(), failure);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // best effort: an inert temp file is better than a broken target
            }
        }
        return input;
    }

    private static String sheetName(JsonNode config) {
        String sheet = NodeSql.text(config, "sheet", DEFAULT_SHEET);
        if (sheet.isBlank()) {
            throw new IllegalArgumentException("excel_writer: config field 'sheet' must not be empty");
        }
        for (char character : INVALID_SHEET_CHARACTERS.toCharArray()) {
            if (sheet.indexOf(character) >= 0) {
                throw new IllegalArgumentException("excel_writer: sheet name '" + sheet
                        + "' must not contain any of " + INVALID_SHEET_CHARACTERS);
            }
        }
        return sheet;
    }

    private static void write(Path file, String sheet, TableData data) throws IOException {
        try (OutputStream stream = Files.newOutputStream(file);
             ZipOutputStream zip = new ZipOutputStream(stream, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", contentTypes());
            entry(zip, "_rels/.rels", packageRelationships());
            entry(zip, "xl/workbook.xml", workbook(sheet));
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
            entry(zip, "xl/worksheets/sheet1.xml", worksheet(data));
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return XML_DECLARATION
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\""
                + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd"
                + ".openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd"
                + ".openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "</Types>";
    }

    private static String packageRelationships() {
        return XML_DECLARATION
                + "<Relationships xmlns=\"" + PACKAGE_RELATIONSHIPS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + OFFICE_RELATIONSHIPS
                + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String workbook(String sheet) {
        return XML_DECLARATION
                + "<workbook xmlns=\"" + SPREADSHEET + "\" xmlns:r=\"" + OFFICE_RELATIONSHIPS + "\">"
                + "<sheets><sheet name=\"" + escape(sheet) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>";
    }

    private static String workbookRelationships() {
        return XML_DECLARATION
                + "<Relationships xmlns=\"" + PACKAGE_RELATIONSHIPS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + OFFICE_RELATIONSHIPS
                + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "</Relationships>";
    }

    private static String worksheet(TableData data) {
        List<ColumnSpec> columns = data.columns();
        StringBuilder xml = new StringBuilder(1024);
        xml.append(XML_DECLARATION)
                .append("<worksheet xmlns=\"").append(SPREADSHEET).append("\"><sheetData>");
        if (!columns.isEmpty()) {
            xml.append("<row r=\"1\">");
            for (int column = 0; column < columns.size(); column++) {
                inlineString(xml, reference(column + 1, 1), columns.get(column).name());
            }
            xml.append("</row>");
        }
        for (int row = 0; row < data.data().size(); row++) {
            List<String> values = data.data().get(row);
            int number = row + 2;
            xml.append("<row r=\"").append(number).append("\">");
            for (int column = 0; column < columns.size(); column++) {
                String value = column < values.size() ? values.get(column) : null;
                cell(xml, reference(column + 1, number), columns.get(column), value);
            }
            xml.append("</row>");
        }
        return xml.append("</sheetData></worksheet>").toString();
    }

    private static void cell(StringBuilder xml, String reference, ColumnSpec column, String value) {
        if (value == null) {
            xml.append("<c r=\"").append(reference).append("\"/>");
            return;
        }
        switch (column.arrowType()) {
            case "Int64", "Float64" -> number(xml, reference, column, value);
            case "Boolean" -> bool(xml, reference, column, value);
            default -> inlineString(xml, reference, value);
        }
    }

    private static void number(StringBuilder xml, String reference, ColumnSpec column, String value) {
        String text;
        try {
            if ("Int64".equals(column.arrowType())) {
                text = Long.toString(Long.parseLong(value.trim()));
            } else {
                double parsed = Double.parseDouble(value.trim());
                if (!Double.isFinite(parsed)) {
                    throw new NumberFormatException("not finite");
                }
                // Plain notation: Excel reads 1.0E10, but plain digits keep the sheet readable.
                text = BigDecimal.valueOf(parsed).toPlainString();
            }
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("excel_writer: value '" + value + "' in column '"
                    + column.name() + "' is not a valid " + column.arrowType(), failure);
        }
        xml.append("<c r=\"").append(reference).append("\"><v>").append(text).append("</v></c>");
    }

    private static void bool(StringBuilder xml, String reference, ColumnSpec column, String value) {
        String text = value.trim();
        String bit;
        if ("true".equalsIgnoreCase(text)) {
            bit = "1";
        } else if ("false".equalsIgnoreCase(text)) {
            bit = "0";
        } else {
            throw new IllegalArgumentException("excel_writer: value '" + value + "' in column '"
                    + column.name() + "' is not a valid Boolean");
        }
        xml.append("<c r=\"").append(reference).append("\" t=\"b\"><v>").append(bit)
                .append("</v></c>");
    }

    private static void inlineString(StringBuilder xml, String reference, String value) {
        xml.append("<c r=\"").append(reference).append("\" t=\"inlineStr\"><is>")
                .append("<t xml:space=\"preserve\">").append(escape(value)).append("</t></is></c>");
    }

    private static String reference(int column, int row) {
        return columnName(column) + row;
    }

    /** 1-based column index to spreadsheet letters: 1 = A, 26 = Z, 27 = AA. */
    private static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        int value = index;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            name.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return name.toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
