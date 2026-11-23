package com.dap.nativeengine.xlsx;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.DuckDbSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XlsxTableReaderTest {
    private static final byte[] OLE2_MAGIC = {
            (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };
    private static final String XLSX_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @TempDir
    Path dir;

    private static XlsxTableReader.Options options() {
        return new XlsxTableReader.Options(null, true, List.of(), true);
    }

    private static void assertRejected(Executable action, String... fragments) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action);
        for (String fragment : fragments) {
            assertTrue(failure.getMessage().contains(fragment),
                    "expected '" + fragment + "' in: " + failure.getMessage());
        }
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private Path legacyXls() throws IOException {
        Path file = dir.resolve("legacy.xls");
        byte[] content = new byte[64];
        System.arraycopy(OLE2_MAGIC, 0, content, 0, OLE2_MAGIC.length);
        Arrays.fill(content, OLE2_MAGIC.length, content.length, (byte) 'x');
        Files.write(file, content);
        return file;
    }

    private Path binaryWorkbook() throws IOException {
        Path file = dir.resolve("binary.xlsb");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "xl/workbook.bin", "binary workbook data");
        }
        return file;
    }

    private Path twoSheetWorkbook() throws IOException {
        Path file = dir.resolve("two-sheets.xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "</Types>");
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"" + XLSX_NS + "\" xmlns:r=\"" + REL_NS + "\"><sheets>"
                    + "<sheet name=\"Первый\" sheetId=\"1\" r:id=\"rId1\"/>"
                    + "<sheet name=\"Второй\" sheetId=\"2\" r:id=\"rId2\"/>"
                    + "</sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"" + REL_NS + "/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/worksheets/sheet1.xml", sheet("who", "first-sheet"));
            put(zip, "xl/worksheets/sheet2.xml", sheet("who", "second-sheet"));
        }
        return file;
    }

    private static String sheet(String header, String value) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"" + XLSX_NS + "\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>" + header + "</t></is></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>" + value + "</t></is></c></row>"
                + "</sheetData></worksheet>";
    }

    @Test
    void legacyXlsFailsWithClearMessage() throws Exception {
        Path file = legacyXls();
        assertRejected(() -> XlsxTableReader.read(file, options()), "legacy .xls", "not supported");
        assertRejected(() -> XlsxTableReader.sheetNames(file), "legacy .xls", "not supported");
    }

    @Test
    void streamIntoRejectsLegacyXls() throws Exception {
        Path file = legacyXls();
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            assertRejected(() -> XlsxTableReader.streamInto(job, "legacy", file, options()),
                    "legacy .xls", "not supported");
        }
    }

    @Test
    void xlsbFailsWithClearMessage() throws Exception {
        Path file = binaryWorkbook();
        assertRejected(() -> XlsxTableReader.read(file, options()), ".xlsb", "not supported");
        assertRejected(() -> XlsxTableReader.sheetNames(file), ".xlsb", "not supported");
    }

    @Test
    void plainFileIsNotAnXlsx() throws Exception {
        Path file = dir.resolve("plain.xlsx");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        assertRejected(() -> XlsxTableReader.read(file, options()), "not an .xlsx workbook");
        assertRejected(() -> XlsxTableReader.sheetNames(file), "not an .xlsx workbook");
    }

    @Test
    void sheetNamesAreInWorkbookOrderAndSelectedSheetIsRead() throws Exception {
        Path file = twoSheetWorkbook();
        assertEquals(List.of("Первый", "Второй"), XlsxTableReader.sheetNames(file));

        ArrowTable table = XlsxTableReader.read(file,
                new XlsxTableReader.Options("Второй", true, List.of(), true));
        assertEquals(1, table.rowCount());
        assertEquals("second-sheet", table.get(0, 0));
    }

    @Test
    void unknownSheetListsAvailableSheets() throws Exception {
        Path file = twoSheetWorkbook();
        assertRejected(() -> XlsxTableReader.read(file,
                new XlsxTableReader.Options("Третий", true, List.of(), true)), "available sheets");
    }
}
