package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelReaderExecutorTest {
    private static final Path CORPUS =
            Path.of(System.getProperty("dap.project.root", ".."), "tests", "goldens-native", "data");

    @TempDir
    Path dir;

    private static Path irisXlsx() {
        return CORPUS.resolve("iris.xlsx");
    }

    private static Path irisCsv() {
        return CORPUS.resolve("iris.csv");
    }

    private static String jsonPath(Path file) {
        return NodeTestSupport.path(file);
    }

    private LazyPlan read(DuckDbSession db, Connection job, String config) throws Exception {
        NodeContext context = NodeTestSupport.context(db, job, config);
        return new ExcelReaderExecutor().execute(context, List.of());
    }

    private static Path writeXlsx(Path dir, String fileName, Map<String, String> sheets,
                                  String sharedStrings, String styles) throws IOException {
        Path file = dir.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/workbook.xml", workbook(sheets.keySet()));
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size(), sharedStrings != null, styles != null));
            int index = 1;
            for (String sheet : sheets.values()) {
                put(zip, "xl/worksheets/sheet" + index + ".xml", sheet);
                index++;
            }
            if (sharedStrings != null) {
                put(zip, "xl/sharedStrings.xml", sharedStrings);
            }
            if (styles != null) {
                put(zip, "xl/styles.xml", styles);
            }
        }
        return file;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "</Types>";
    }

    private static String workbook(java.util.Collection<String> names) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        int index = 1;
        for (String name : names) {
            xml.append("<sheet name=\"").append(name).append("\" sheetId=\"").append(index)
                    .append("\" r:id=\"rId").append(index).append("\"/>");
            index++;
        }
        return xml.append("</sheets></workbook>").toString();
    }

    private static String workbookRels(int sheetCount, boolean sharedStrings, boolean styles) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int index = 1; index <= sheetCount; index++) {
            xml.append("<Relationship Id=\"rId").append(index)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"")
                    .append(" Target=\"worksheets/sheet").append(index).append(".xml\"/>");
        }
        int next = sheetCount + 1;
        if (sharedStrings) {
            xml.append("<Relationship Id=\"rId").append(next)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\"")
                    .append(" Target=\"sharedStrings.xml\"/>");
            next++;
        }
        if (styles) {
            xml.append("<Relationship Id=\"rId").append(next)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\"")
                    .append(" Target=\"styles.xml\"/>");
        }
        return xml.append("</Relationships>").toString();
    }

    private static String sheet(String rows) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData>" + rows + "</sheetData></worksheet>";
    }

    private static String inline(int row, String cell, String text) {
        return "<c r=\"" + cell + row + "\" t=\"inlineStr\"><is><t>" + text + "</t></is></c>";
    }

    @Test
    void irisWorkbookMatchesCsvWithHeaderAndInferredTypes() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan csv = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, "{\"file\":\"" + jsonPath(irisCsv()) + "\"}"), List.of());
            LazyPlan excel = read(db, job, "{\"file\":\"" + jsonPath(irisXlsx()) + "\"}");

            assertEquals(csv.columns(), excel.columns());
            assertEquals(List.of(
                    ColumnSpec.of("sepal_length", "Float64"),
                    ColumnSpec.of("sepal_width", "Float64"),
                    ColumnSpec.of("petal_length", "Float64"),
                    ColumnSpec.of("petal_width", "Float64"),
                    ColumnSpec.of("species", "Utf8")), excel.columns());
            assertEquals("Number (Float)", excel.columns().get(0).knimeType());
            assertEquals("String", excel.columns().get(4).knimeType());

            TableData data = excel.preview(200);
            assertEquals(150, data.rows());
            assertEquals(List.of("5.1", "3.5", "1.4", "0.2", "setosa"), data.data().get(0));
            assertEquals(List.of("5.9", "3.0", "5.1", "1.8", "virginica"), data.data().get(149));
            assertEquals(csv.preview(200).data(), data.data());
        }
    }

    @Test
    void sheetCanBeSelectedByNameAndByIndex() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan byName = read(db, job,
                    "{\"file\":\"" + jsonPath(irisXlsx()) + "\",\"sheet\":\"iris\"}");
            LazyPlan byIndex = read(db, job,
                    "{\"file\":\"" + jsonPath(irisXlsx()) + "\",\"sheet\":\"0\"}");

            for (LazyPlan plan : List.of(byName, byIndex)) {
                assertEquals(List.of(
                        ColumnSpec.of("sepal_length", "Float64"),
                        ColumnSpec.of("sepal_width", "Float64"),
                        ColumnSpec.of("petal_length", "Float64"),
                        ColumnSpec.of("petal_width", "Float64"),
                        ColumnSpec.of("species", "Utf8")), plan.columns());
                assertEquals(List.of("5.1", "3.5", "1.4", "0.2", "setosa"),
                        plan.preview(1).data().get(0));
            }
        }
    }

    @Test
    void zeroBasedSheetIndexSelectsAnotherWorksheet() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("first", sheet("<row r=\"1\">" + inline(1, "A", "a") + "</row>"
                + "<row r=\"2\"><c r=\"A2\"><v>1</v></c></row>"));
        sheets.put("second", sheet("<row r=\"1\">" + inline(1, "A", "b") + "</row>"
                + "<row r=\"2\"><c r=\"A2\"><v>2</v></c></row>"));
        Path file = writeXlsx(dir, "two-sheets.xlsx", sheets, null, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan byIndex = read(db, job, "{\"file\":\"" + jsonPath(file) + "\",\"sheet\":\"1\"}");
            LazyPlan byName = read(db, job, "{\"file\":\"" + jsonPath(file) + "\",\"sheet\":\"second\"}");

            for (LazyPlan plan : List.of(byIndex, byName)) {
                assertEquals(List.of(ColumnSpec.of("b", "Int64")), plan.columns());
                assertEquals(List.of("2"), plan.preview(10).data().get(0));
            }
            try (Statement statement = job.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT COUNT(*) FROM duckdb_tables() WHERE lower(table_name) = 'excel_wf_node'")) {
                result.next();
                assertEquals(1, result.getLong(1));
            }
        }
    }

    @Test
    void numericSheetIndexIsRejectedBecauseTheSchemaDeclaresAString() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("only", sheet("<row r=\"1\">" + inline(1, "A", "x") + "</row>"));
        Path file = writeXlsx(dir, "numeric-sheet.xlsx", sheets, null, null);
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, job, "{\"file\":\"" + jsonPath(file) + "\",\"sheet\":0}"));
            assertTrue(failure.getMessage().contains("sheet"), failure.getMessage());
        }
    }

    @Test
    void explicitSchemaOverridesInferenceAndCastsValues() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","columns":[
                      {"name":"sepal_length","type":"Float64"},
                      {"name":"species","type":"Utf8"}]}
                    """.formatted(jsonPath(irisXlsx()));
            LazyPlan plan = read(db, job, config);

            assertEquals(List.of(ColumnSpec.of("sepal_length", "Float64"),
                    ColumnSpec.of("species", "Utf8")), plan.columns());
            TableData data = plan.preview(200);
            assertEquals(150, data.rows());
            assertEquals(List.of("5.1", "setosa"), data.data().get(0));
            assertEquals(List.of("5.9", "virginica"), data.data().get(149));
        }
    }

    @Test
    void numericCellsCanBeCastToStringWithExplicitSchema() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "n") + inline(1, "B", "m") + "</row>"
                + "<row r=\"2\"><c r=\"A2\"><v>5.1</v></c><c r=\"B2\"><v>3</v></c></row>"));
        Path file = writeXlsx(dir, "cast.xlsx", sheets, null, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","columns":[{"name":"n","type":"Utf8"},{"name":"m","type":"Int64"}]}
                    """.formatted(jsonPath(file));
            LazyPlan plan = read(db, job, config);

            assertEquals(List.of(ColumnSpec.of("n", "Utf8"), ColumnSpec.of("m", "Int64")), plan.columns());
            assertEquals(List.of("5.1", "3"), plan.preview(10).data().get(0));
        }
    }

    @Test
    void missingCellBecomesNull() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "a") + inline(1, "B", "b")
                + inline(1, "C", "c") + "</row>"
                + "<row r=\"2\"><c r=\"A2\"><v>1</v></c><c r=\"C2\"><v>3</v></c></row>"));
        Path file = writeXlsx(dir, "missing-cell.xlsx", sheets, null, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}");

            assertEquals(List.of(ColumnSpec.of("a", "Int64"), ColumnSpec.of("b", "Utf8"),
                    ColumnSpec.of("c", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals("1", data.data().get(0).get(0));
            assertNull(data.data().get(0).get(1));
            assertEquals("3", data.data().get(0).get(2));
            assertEquals(1, plan.profile().columns().get(1).missing());
        }
    }

    @Test
    void sharedStringsConcatenateRunsAndEmptyValueStaysEmptyUnlessEmptyStringIsNull() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "greeting") + inline(1, "B", "note") + "</row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c><c r=\"B2\" t=\"s\"><v>1</v></c></row>"));
        String sharedStrings = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"2\" uniqueCount=\"2\">"
                + "<si><r><rPr><b/></rPr><t>Hel</t></r><r><t>lo</t></r></si>"
                + "<si><t></t></si></sst>";
        Path file = writeXlsx(dir, "shared.xlsx", sheets, sharedStrings, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}");

            TableData data = plan.preview(10);
            assertEquals("Hello", data.data().get(0).get(0));
            assertEquals("", data.data().get(0).get(1));
            assertEquals(0, plan.profile().columns().get(1).missing());

            LazyPlan nulls = read(db, job, "{\"file\":\"" + jsonPath(file) + "\",\"emptyStringIsNull\":true}");
            TableData withNulls = nulls.preview(10);
            assertEquals("Hello", withNulls.data().get(0).get(0));
            assertNull(withNulls.data().get(0).get(1));
            assertEquals(1, nulls.profile().columns().get(1).missing());
        }
    }

    @Test
    void unknownSheetListsAvailableSheets() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, job,
                            "{\"file\":\"" + jsonPath(irisXlsx()) + "\",\"sheet\":\"nope\"}"));
            assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
            assertTrue(failure.getMessage().contains("iris"), failure.getMessage());
        }
    }

    @Test
    void missingWorkbookFailsWithPath() throws Exception {
        Path absent = dir.resolve("absent.xlsx");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, job, "{\"file\":\"" + jsonPath(absent) + "\"}"));
            assertTrue(failure.getMessage().contains("absent.xlsx"), failure.getMessage());
        }
    }

    @Test
    void fileThatIsNotAZipFailsWithPath() throws Exception {
        Path plain = dir.resolve("plain.xlsx");
        Files.writeString(plain, "not a workbook", StandardCharsets.UTF_8);
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, job, "{\"file\":\"" + jsonPath(plain) + "\"}"));
            assertTrue(failure.getMessage().contains("plain.xlsx"), failure.getMessage());
        }
    }

    @Test
    void zipWithoutWorkbookPartsIsNotAnXlsx() throws Exception {
        Path file = dir.resolve("valid-zip.xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "readme.txt", "not a workbook");
        }

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}"));
            assertTrue(failure.getMessage().contains("valid-zip.xlsx"), failure.getMessage());
        }
    }

    @Test
    void nonIntegralValueCannotBeCastToInt64() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","columns":[{"name":"sepal_length","type":"Int64"}]}
                    """.formatted(jsonPath(irisXlsx()));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, job, config));
            assertTrue(failure.getMessage().contains("sepal_length"), failure.getMessage());
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("row"),
                    failure.getMessage());
        }
    }

    @Test
    void isoDateCellsInferDate32AndTimestamp() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "day") + inline(1, "B", "moment") + "</row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"d\"><v>2024-01-02</v></c>"
                + "<c r=\"B2\" t=\"d\"><v>2024-01-02T03:04:05</v></c></row>"));
        Path file = writeXlsx(dir, "iso-dates.xlsx", sheets, null, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}");

            assertEquals(List.of(ColumnSpec.of("day", "Date32"),
                    ColumnSpec.of("moment", "Timestamp")), plan.columns());
            assertEquals("Date", plan.columns().get(0).knimeType());
            assertEquals("Date&Time (Timestamp)", plan.columns().get(1).knimeType());
            assertEquals(List.of("2024-01-02", "2024-01-02T03:04:05"), plan.preview(10).data().get(0));
        }
    }

    @Test
    void dateStylesTurnNumericCellsIntoDates() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "builtin") + inline(1, "B", "custom") + "</row>"
                + "<row r=\"2\"><c r=\"A2\" s=\"1\"><v>45293.5</v></c>"
                + "<c r=\"B2\" s=\"2\"><v>45293.5</v></c></row>"));
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd\"/></numFmts>"
                + "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"14\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"
                + "</styleSheet>";
        Path file = writeXlsx(dir, "dates-styled.xlsx", sheets, null, styles);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan inferred = read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}");
            assertEquals(List.of(ColumnSpec.of("builtin", "Date32"),
                    ColumnSpec.of("custom", "Date32")), inferred.columns());
            assertEquals(List.of("2024-01-02", "2024-01-02"), inferred.preview(10).data().get(0));

            LazyPlan explicitDate = read(db, job,
                    "{\"file\":\"" + jsonPath(file) + "\",\"columns\":[{\"name\":\"builtin\",\"type\":\"Date32\"}]}");
            assertEquals(List.of("2024-01-02"), explicitDate.preview(10).data().get(0));

            LazyPlan explicitTimestamp = read(db, job,
                    "{\"file\":\"" + jsonPath(file) + "\",\"columns\":[{\"name\":\"builtin\",\"type\":\"Timestamp\"}]}");
            assertEquals(List.of("2024-01-02T12:00"), explicitTimestamp.preview(10).data().get(0));
        }
    }

    @Test
    void formulaCachedStringBooleansAndErrorsBecomeTypedValuesOrNull() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "formula") + inline(1, "B", "flag")
                + inline(1, "C", "broken") + inline(1, "D", "plain") + "</row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"str\"><f>CONCATENATE(\"ca\",\"ched\")</f><v>cached</v></c>"
                + "<c r=\"B2\" t=\"b\"><v>1</v></c>"
                + "<c r=\"C2\" t=\"e\"><v>#DIV/0!</v></c>"
                + "<c r=\"D2\"><v>7</v></c></row>"));
        Path file = writeXlsx(dir, "formula-bool-error.xlsx", sheets, null, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}");

            assertEquals(List.of(ColumnSpec.of("formula", "Utf8"), ColumnSpec.of("flag", "Boolean"),
                    ColumnSpec.of("broken", "Utf8"), ColumnSpec.of("plain", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            List<String> row = data.data().get(0);
            assertEquals("cached", row.get(0));
            assertEquals("true", row.get(1));
            assertNull(row.get(2));
            assertEquals("7", row.get(3));
            assertEquals(1, plan.profile().columns().get(2).missing());
        }
    }

    @Test
    void headerFalseReadsFirstRowAsDataAndUsesPositionalSchema() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"B1\"><v>2</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\"><v>3</v></c><c r=\"B2\"><v>4</v></c></row>"));
        Path file = writeXlsx(dir, "no-header.xlsx", sheets, null, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","header":false,"columns":[{"name":"x","type":"Int64"},{"name":"y","type":"Utf8"}]}
                    """.formatted(jsonPath(file));
            LazyPlan plan = read(db, job, config);

            assertEquals(List.of(ColumnSpec.of("x", "Int64"), ColumnSpec.of("y", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("1", "2"), data.data().get(0));
            assertEquals(List.of("3", "4"), data.data().get(1));
        }
    }

    @Test
    void blankHeaderNamesUseColumnFallback() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "id") + inline(1, "B", "") + "</row>"
                + "<row r=\"2\"><c r=\"A2\"><v>1</v></c><c r=\"B2\"><v>2</v></c></row>"));
        Path file = writeXlsx(dir, "blank-header.xlsx", sheets, null, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}");

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("column2", "Int64")),
                    plan.columns());
            assertEquals(List.of("1", "2"), plan.preview(10).data().get(0));
        }
    }

    @Test
    void duplicateHeaderNamesAreRejectedWithTheColumnName() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "id") + inline(1, "B", "id") + "</row>"
                + "<row r=\"2\"><c r=\"A2\"><v>1</v></c><c r=\"B2\"><v>2</v></c></row>"));
        Path file = writeXlsx(dir, "duplicate-header.xlsx", sheets, null, null);
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}"));
            assertTrue(failure.getMessage().contains("id"), failure.getMessage());
            assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
        }
    }

    @Test
    void plainNumberCannotBeSilentlyReadAsADate() throws Exception {
        Map<String, String> sheets = new LinkedHashMap<>();
        sheets.put("Sheet1", sheet("<row r=\"1\">" + inline(1, "A", "n") + "</row>"
                + "<row r=\"2\"><c r=\"A2\"><v>45293</v></c></row>"));
        Path file = writeXlsx(dir, "plain-number.xlsx", sheets, null, null);

        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan inferred = read(db, job, "{\"file\":\"" + jsonPath(file) + "\"}");
            assertEquals(List.of(ColumnSpec.of("n", "Int64")), inferred.columns());

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, job,
                            "{\"file\":\"" + jsonPath(file) + "\",\"columns\":[{\"name\":\"n\",\"type\":\"Date32\"}]}"));
            assertTrue(failure.getMessage().contains("'n'"), failure.getMessage());
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("row"),
                    failure.getMessage());
        }
    }

    @Test
    void interruptAbortsRead() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job,
                    "{\"file\":\"" + jsonPath(irisXlsx()) + "\"}");
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedException.class,
                        () -> new ExcelReaderExecutor().execute(context, List.of()));
            } finally {
                Thread.interrupted();
            }
        }
    }
}