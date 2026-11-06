package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.xlsx.XlsxTableReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelWriterExecutorTest {
    private static final String XML_DECLARATION =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String SPREADSHEET =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final List<String> PARTS = List.of(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/worksheets/sheet1.xml");

    @TempDir
    Path dir;

    private static final List<ColumnSpec> PEOPLE =
            List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"));

    private static LazyPlan people(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, PEOPLE, List.of(
                new Object[]{1L, "ann"},
                new Object[]{2L, null}));
    }

    private static String fileConfig(Path file) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"}";
    }

    private static String config(Path file, String extra) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"" + extra + "}";
    }

    private static String part(Path file, String name) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry(name);
            assertNotNull(entry, name);
            try (InputStream stream = zip.getInputStream(entry)) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static List<String> entries(Path file) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.stream().map(ZipEntry::getName).toList();
        }
    }

    @Test
    void writesTheRequiredPartsAndPassesThePlanThrough() throws Exception {
        Path file = dir.resolve("out.xlsx");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            LazyPlan plan = new ExcelWriterExecutor().execute(context, List.of(input));

            assertSame(input, plan);
            assertEquals(PARTS, entries(file));
            assertEquals(XML_DECLARATION
                    + "<worksheet xmlns=\"" + SPREADSHEET + "\"><sheetData>"
                    + "<row r=\"1\">"
                    + "<c r=\"A1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">id</t></is></c>"
                    + "<c r=\"B1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">name</t></is></c>"
                    + "</row>"
                    + "<row r=\"2\">"
                    + "<c r=\"A2\"><v>1</v></c>"
                    + "<c r=\"B2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">ann</t></is></c>"
                    + "</row>"
                    + "<row r=\"3\">"
                    + "<c r=\"A3\"><v>2</v></c>"
                    + "<c r=\"B3\"/>"
                    + "</row>"
                    + "</sheetData></worksheet>",
                    part(file, "xl/worksheets/sheet1.xml"));
        }
    }

    @Test
    void writesTypedCellsAndEscapesXml() throws Exception {
        Path file = dir.resolve("typed.xlsx");
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("score", "Float64"),
                ColumnSpec.of("active", "Boolean"),
                ColumnSpec.of("born", "Date32"),
                ColumnSpec.of("at", "Timestamp"),
                ColumnSpec.of("note", "Utf8"));
        List<Object[]> rows = List.of(
                new Object[]{1.5, true, LocalDate.of(2024, 1, 2),
                        LocalDateTime.of(2024, 1, 2, 3, 4, 5), "a & <b> \"c\" 'd'"},
                new Object[]{2.25, false, LocalDate.of(2025, 12, 31),
                        LocalDateTime.of(2025, 12, 31, 23, 59, 59), ""});
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new ExcelWriterExecutor().execute(context, List.of(input));

            String sheet = part(file, "xl/worksheets/sheet1.xml");
            assertTrue(sheet.contains("<c r=\"A2\"><v>1.5</v></c>"), sheet);
            assertTrue(sheet.contains("<c r=\"A3\"><v>2.25</v></c>"), sheet);
            assertTrue(sheet.contains("<c r=\"B2\" t=\"b\"><v>1</v></c>"), sheet);
            assertTrue(sheet.contains("<c r=\"B3\" t=\"b\"><v>0</v></c>"), sheet);
            assertTrue(sheet.contains("<c r=\"C2\" t=\"inlineStr\">"
                    + "<is><t xml:space=\"preserve\">2024-01-02</t></is></c>"), sheet);
            assertTrue(sheet.contains("<c r=\"D2\" t=\"inlineStr\">"
                    + "<is><t xml:space=\"preserve\">2024-01-02T03:04:05</t></is></c>"), sheet);
            assertTrue(sheet.contains("a &amp; &lt;b&gt; &quot;c&quot; &apos;d&apos;"), sheet);
            assertFalse(sheet.contains("<b>"), sheet);
        }
    }

    @Test
    void createsParentDirectories() throws Exception {
        Path file = dir.resolve("nested/deeper/out.xlsx");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new ExcelWriterExecutor().execute(context, List.of(people(db)));

            assertTrue(Files.isRegularFile(file), file.toString());
        }
    }

    @Test
    void defaultsToSheet1AndHonoursACustomSheet() throws Exception {
        Path file = dir.resolve("out.xlsx");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));
            new ExcelWriterExecutor().execute(context, List.of(people(db)));

            String workbook = part(file, "xl/workbook.xml");
            assertTrue(workbook.contains("<sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/>"), workbook);
        }
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job,
                    config(file, ",\"sheet\":\"Q1 & Q2\""));
            new ExcelWriterExecutor().execute(context, List.of(people(db)));

            String workbook = part(file, "xl/workbook.xml");
            assertTrue(workbook.contains("<sheet name=\"Q1 &amp; Q2\" sheetId=\"1\" r:id=\"rId1\"/>"), workbook);
        }
    }

    @Test
    void emptyTableWritesOnlyTheHeader() throws Exception {
        Path file = dir.resolve("empty.xlsx");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, PEOPLE, List.of());
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new ExcelWriterExecutor().execute(context, List.of(input));

            String sheet = part(file, "xl/worksheets/sheet1.xml");
            assertTrue(sheet.contains("<row r=\"1\">"), sheet);
            assertFalse(sheet.contains("<row r=\"2\">"), sheet);
        }
    }

    @Test
    void overwritesAnExistingWorkbook() throws Exception {
        Path file = dir.resolve("out.xlsx");
        Files.write(file, new byte[]{1, 2, 3});
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new ExcelWriterExecutor().execute(context, List.of(people(db)));

            assertEquals(PARTS, entries(file));
        }
    }

    @Test
    void rejectsMissingFileConfig() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, "{}");

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ExcelWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("file"), failure.getMessage());
        }
    }

    @Test
    void rejectsBlankSheet() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job,
                    config(dir.resolve("out.xlsx"), ",\"sheet\":\"  \""));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ExcelWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("sheet"), failure.getMessage());
        }
    }

    @Test
    void rejectsSheetWithInvalidCharacters() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job,
                    config(dir.resolve("out.xlsx"), ",\"sheet\":\"Q1/Q2\""));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ExcelWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("sheet"), failure.getMessage());
        }
    }

    @Test
    void rejectsANonStringSheet() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job,
                    config(dir.resolve("out.xlsx"), ",\"sheet\":1"));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ExcelWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("sheet"), failure.getMessage());
        }
    }

    @Test
    void roundTripsThroughTheExcelReader() throws Exception {
        Path file = dir.resolve("roundtrip.xlsx");
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("id", "Int64"),
                ColumnSpec.of("name", "Utf8"),
                ColumnSpec.of("born", "Date32"));
        List<Object[]> rows = List.of(
                new Object[]{1L, "ann", LocalDate.of(2024, 1, 2)},
                new Object[]{2L, null, LocalDate.of(2023, 12, 31)});
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new ExcelWriterExecutor().execute(context, List.of(input));
        }

        XlsxTableReader.Options options = new XlsxTableReader.Options("Sheet1", true, columns, false);
        try (ArrowTable table = XlsxTableReader.read(file, options)) {
            assertEquals(columns, table.columns());
            assertEquals(2, table.rowCount());
            assertEquals(1L, table.get(0, 0));
            assertEquals("ann", table.get(1, 0));
            assertNull(table.get(1, 1));
            assertEquals(LocalDate.of(2024, 1, 2), table.get(2, 0));
            assertEquals(LocalDate.of(2023, 12, 31), table.get(2, 1));
        }
    }
}