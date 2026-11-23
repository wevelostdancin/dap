package com.dap.nativeengine.io;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.xlsx.XlsxTableReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestServiceTest {
    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private IngestService service() {
        return new IngestService(dir.resolve("cache"));
    }

    private static String literal(Path file) {
        return "'" + file.toString().replace("'", "''") + "'";
    }

    @Test
    void csvBecomesParquetWithRowsAndColumns() throws Exception {
        Path source = write("people.csv", "name,age,score\nalice,34,9.5\nbob,28,7.0\n");

        IngestService.Result result = service().ingest(source, "csv");

        assertTrue(Files.isRegularFile(result.path()), "cached Parquet file is missing");
        assertTrue(result.path().getFileName().toString().endsWith(".parquet"));
        assertFalse(result.cached());
        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("name", "Utf8"),
                new IngestService.Column("age", "Int64"),
                new IngestService.Column("score", "Float64")), result.columns());

        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name, age, score FROM read_parquet("
                     + literal(result.path()) + ") ORDER BY name")) {
            assertTrue(rows.next());
            assertEquals("alice", rows.getString(1));
            assertEquals(34L, rows.getLong(2));
            assertEquals(9.5, rows.getDouble(3));
            assertTrue(rows.next());
            assertEquals("bob", rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void secondCallReturnsTheSameFileWithoutRewritingIt() throws Exception {
        Path source = write("people.csv", "id\n1\n2\n");
        IngestService service = service();
        IngestService.Result first = service.ingest(source, "csv");
        FileTime marker = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(first.path(), marker);

        IngestService.Result second = service.ingest(source, "csv");

        assertTrue(second.cached());
        assertEquals(first.path(), second.path());
        assertEquals(marker, Files.getLastModifiedTime(second.path()), "cache entry was rewritten");
        assertEquals(first.rows(), second.rows());
        assertEquals(first.columns(), second.columns());
    }

    @Test
    void jsonBecomesParquet() throws Exception {
        Path source = write("people.json", "[{\"id\":1,\"name\":\"alice\"},{\"id\":2,\"name\":\"bob\"}]");

        IngestService.Result result = service().ingest(source, "json");

        assertTrue(Files.isRegularFile(result.path()));
        assertFalse(result.cached());
        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("id", "Int64"),
                new IngestService.Column("name", "Utf8")), result.columns());
    }

    @Test
    void parquetBecomesParquet() throws Exception {
        Path source = dir.resolve("source.parquet");
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("COPY (SELECT 1 AS id, 'x' AS label UNION ALL SELECT 2, 'y') TO "
                    + literal(source) + " (FORMAT PARQUET)");
        }

        IngestService.Result result = service().ingest(source, "parquet");

        assertTrue(Files.isRegularFile(result.path()));
        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("id", "Int64"),
                new IngestService.Column("label", "Utf8")), result.columns());
    }

    @Test
    void unknownFormatIsRejected() throws Exception {
        Path source = write("data.csv", "id\n1\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().ingest(source, "xml"));

        assertTrue(failure.getMessage().contains("xml"), failure.getMessage());
    }

    private static List<List<String>> rows(Path parquet) throws Exception {
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM read_parquet("
                     + literal(parquet) + ") ORDER BY 1")) {
            List<List<String>> rows = new java.util.ArrayList<>();
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> row = new java.util.ArrayList<>(columns);
                for (int column = 1; column <= columns; column++) {
                    row.add(DuckDbSession.format(result.getObject(column)));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    @Test
    void quotedAndUnquotedEmptyFieldsBecomeNulls() throws Exception {
        Path source = write("empty.csv", "txt,num\n,1\n\"\",2\n\"x\",3\n");

        IngestService.Result result = service().ingest(source, "csv");

        assertEquals(List.of(
                new IngestService.Column("txt", "Utf8"),
                new IngestService.Column("num", "Int64")), result.columns());
        assertEquals(List.of(
                List.of("x", "3"),
                java.util.Arrays.asList(null, "1"),
                java.util.Arrays.asList(null, "2")), rows(result.path()));
    }

    @Test
    void sourceOutsideTheAllowlistIsRejected() {
        ApiException failure = assertThrows(ApiException.class,
                () -> service().ingest(Path.of("/etc/passwd"), "csv"));

        assertEquals(400, failure.status(), failure.getMessage());
        assertEquals("path_not_allowed", failure.code(), failure.getMessage());
    }

    @Test
    void missingFileIsRejected() {
        Path absent = dir.resolve("absent.csv");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().ingest(absent, "csv"));

        assertTrue(failure.getMessage().contains("absent.csv"), failure.getMessage());
    }

    @Test
    void headerlessCsvKeepsEveryRowWithHeaderFalse() throws Exception {
        Path source = write("noheader.csv", "1,alpha\n2,beta\n");
        IngestService.IngestOptions options = new IngestService.IngestOptions(
                false, ",", "\"", "\"", false, false, true);

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(2, result.rows(), "a headerless file must not lose its first row");
        assertEquals(2, result.columns().size());
    }

    @Test
    void malformedRowsCanBeSkippedWithIgnoreErrors() throws Exception {
        Path source = write("ragged.csv", "a,b\n1,2\n3,4,5\n6,7\n");
        IngestService.IngestOptions options = new IngestService.IngestOptions(
                true, ",", "\"", "\"", true, false, true);

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("a", "Int64"),
                new IngestService.Column("b", "Int64")), result.columns());
    }

    @Test
    void nullStringsBecomeNulls() throws Exception {
        Path source = write("nulls.csv", "id,value\n1,NA\n2,5\n");
        IngestService.IngestOptions options = new IngestService.IngestOptions(
                true, ",", "\"", "\"", false, false, true, List.of("NA"));

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(2, result.rows());
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT value FROM read_parquet("
                     + literal(result.path()) + ") ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals(null, rows.getObject(1));
            assertTrue(rows.next());
            assertEquals(5, rows.getInt(1));
        }
    }

    @Test
    void sameSizeAndMtimeWithDifferentContentProducesNewParquet() throws Exception {
        Path source = write("same.csv", "id\n1\n2\n");
        IngestService service = service();
        IngestService.Result first = service.ingest(source, "csv");
        long size = Files.size(source);
        FileTime mtime = Files.getLastModifiedTime(source);

        Files.writeString(source, "id\n3\n4\n", StandardCharsets.UTF_8);
        assertEquals(size, Files.size(source));
        Files.setLastModifiedTime(source, mtime);

        IngestService.Result second = service.ingest(source, "csv");

        assertNotEquals(first.path(), second.path(), "rewritten content must not reuse the old entry");
        assertFalse(second.cached());
        assertEquals(List.of(List.of("3"), List.of("4")), rows(second.path()));
        assertEquals(List.of(List.of("1"), List.of("2")), rows(first.path()));
    }

    @Test
    void changeInTailOfLargeFileInvalidatesCache() throws Exception {
        StringBuilder content = new StringBuilder("id,name\n");
        for (int row = 0; row < 10_000; row++) {
            content.append(row % 10).append(",value\n");
        }
        Path source = write("large.csv", content.toString());
        assertTrue(Files.size(source) > 64 * 1024);
        IngestService service = service();
        IngestService.Result first = service.ingest(source, "csv");
        long size = Files.size(source);
        FileTime mtime = Files.getLastModifiedTime(source);

        String changed = content.substring(0, content.length() - 8) + "0,value\n";
        Files.writeString(source, changed, StandardCharsets.UTF_8);
        assertEquals(size, Files.size(source));
        Files.setLastModifiedTime(source, mtime);

        IngestService.Result second = service.ingest(source, "csv");

        assertNotEquals(first.path(), second.path(), "tail bytes must be part of the fingerprint");
        assertFalse(second.cached());
        assertEquals(10_000, second.rows());
    }

    @Test
    void truncatedCachedParquetIsRegenerated() throws Exception {
        Path source = write("truncate.csv", "id\n7\n8\n");
        IngestService service = service();
        IngestService.Result first = service.ingest(source, "csv");
        try (FileChannel channel = FileChannel.open(first.path(), StandardOpenOption.WRITE)) {
            channel.truncate(100);
        }

        IngestService.Result recovered = service.ingest(source, "csv");

        assertFalse(recovered.cached(), "corrupt cache entry must be rebuilt");
        assertEquals(first.path(), recovered.path());
        assertEquals(List.of(List.of("7"), List.of("8")), rows(recovered.path()));
    }

    @Test
    void parallelIngestSharesOneCacheEntry() throws Exception {
        Path source = write("parallel.csv", "id\n1\n2\n");
        IngestService service = service();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<IngestService.Result>> futures = new ArrayList<>();
            for (int attempt = 0; attempt < 4; attempt++) {
                futures.add(pool.submit(() -> service.ingest(source, "csv")));
            }
            List<IngestService.Result> results = new ArrayList<>();
            for (Future<IngestService.Result> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertEquals(1, results.stream().map(IngestService.Result::path).distinct().count());
            assertEquals(1, results.stream().filter(result -> !result.cached()).count(),
                    "exactly one call writes the cache entry");
            try (Stream<Path> cached = Files.list(dir.resolve("cache"))) {
                assertEquals(1, cached.filter(path -> path.getFileName().toString().endsWith(".parquet"))
                        .count(), "one Parquet file per key");
            }
            assertEquals(List.of(List.of("1"), List.of("2")), rows(results.get(0).path()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unchangedFileIsServedFromCacheWithSamePath() throws Exception {
        Path source = write("stable.csv", "id\n1\n2\n");
        IngestService service = service();

        IngestService.Result first = service.ingest(source, "csv");
        IngestService.Result second = service.ingest(source, "csv");

        assertTrue(second.cached());
        assertEquals(first.path(), second.path());
        assertEquals(first.rows(), second.rows());
    }

    @Test
    void csvOptionsArePartOfTheCacheKey() throws Exception {
        Path source = write("people.csv", "1,alpha\n2,beta\n");
        IngestService service = service();

        IngestService.Result withHeader = service.ingest(source, "csv");
        IngestService.Result withoutHeader = service.ingest(source, "csv",
                new IngestService.IngestOptions(false, ",", "\"", "\"", false, false, true));

        assertFalse(withHeader.path().equals(withoutHeader.path()),
                "different CSV options must not share a cache entry");
    }

    // ---------------------------------------------------------------- Excel ingest

    private static final String XLSX_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static void putXlsx(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** Minimal OOXML workbook: one sheet plus optional shared strings and styles. */
    private static Path writeXlsx(Path dir, String name, String sheetRows, String sharedStrings, String styles)
            throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            putXlsx(zip, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types/>");
            putXlsx(zip, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships>"
                    + "<Relationship Id=\"rId1\" Target=\"xl/workbook.xml\"/></Relationships>");
            putXlsx(zip, "xl/workbook.xml", "<?xml version=\"1.0\"?>"
                    + "<workbook xmlns=\"" + XLSX_NS + "\" xmlns:r=\"" + REL_NS + "\"><sheets>"
                    + "<sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\"?><Relationships>"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/worksheet\""
                    + " Target=\"worksheets/sheet1.xml\"/>");
            if (sharedStrings != null) {
                rels.append("<Relationship Id=\"rId2\" Type=\"" + REL_NS + "/sharedStrings\""
                        + " Target=\"sharedStrings.xml\"/>");
            }
            if (styles != null) {
                rels.append("<Relationship Id=\"rId3\" Type=\"" + REL_NS + "/styles\""
                        + " Target=\"styles.xml\"/>");
            }
            putXlsx(zip, "xl/_rels/workbook.xml.rels", rels.append("</Relationships>").toString());
            putXlsx(zip, "xl/worksheets/sheet1.xml", "<?xml version=\"1.0\"?>"
                    + "<worksheet xmlns=\"" + XLSX_NS + "\"><sheetData>" + sheetRows
                    + "</sheetData></worksheet>");
            if (sharedStrings != null) {
                putXlsx(zip, "xl/sharedStrings.xml", sharedStrings);
            }
            if (styles != null) {
                putXlsx(zip, "xl/styles.xml", styles);
            }
        }
        return file;
    }

    /** Numeric-only sheet: one inline-string header and {@code dataRows} rows of ten integers. */
    private static Path writeWideXlsx(Path dir, String name, int dataRows) throws IOException {
        StringBuilder sheet = new StringBuilder("<row r=\"1\">");
        for (int column = 0; column < 10; column++) {
            sheet.append("<c r=\"").append((char) ('A' + column)).append("1\" t=\"inlineStr\">"
                    + "<is><t>c").append(column).append("</t></is></c>");
        }
        sheet.append("</row>");
        for (int row = 2; row <= dataRows + 1; row++) {
            sheet.append("<row r=\"").append(row).append("\">");
            for (int column = 0; column < 10; column++) {
                sheet.append("<c r=\"").append((char) ('A' + column)).append(row)
                        .append("\"><v>").append(row - 1).append("</v></c>");
            }
            sheet.append("</row>");
        }
        return writeXlsx(dir, name, sheet.toString(), null, null);
    }

    @Test
    void excelBecomesParquetMatchingExcelReader() throws Exception {
        String shared = "<?xml version=\"1.0\"?><sst xmlns=\"" + XLSX_NS + "\">"
                + "<si><t>alpha</t></si></sst>";
        String styles = "<?xml version=\"1.0\"?><styleSheet xmlns=\"" + XLSX_NS + "\">"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs>"
                + "</styleSheet>";
        String sheet = """
                <row r="1">
                  <c r="A1" t="inlineStr"><is><t>id</t></is></c>
                  <c r="B1" t="inlineStr"><is><t>score</t></is></c>
                  <c r="C1" t="inlineStr"><is><t>note</t></is></c>
                  <c r="D1" t="inlineStr"><is><t>born</t></is></c>
                  <c r="E1" t="inlineStr"><is><t>flag</t></is></c>
                </row>
                <row r="2">
                  <c r="A2"><v>1</v></c><c r="B2"><v>9.5</v></c><c r="C2" t="s"><v>0</v></c>
                  <c r="D2" s="1"><v>45292</v></c><c r="E2" t="b"><v>1</v></c>
                </row>
                <row r="3">
                  <c r="A3"><v>2</v></c><c r="B3"><v>7.0</v></c>
                  <c r="D3" t="d"><v>2024-03-04</v></c><c r="E3" t="b"><v>0</v></c>
                </row>
                """;
        Path file = writeXlsx(dir, "small.xlsx", sheet, shared, styles);
        XlsxTableReader.Options options = new XlsxTableReader.Options(null, true, List.of(), true, false);

        List<IngestService.Column> expectedColumns;
        List<List<String>> expectedRows;
        try (ArrowTable expected = XlsxTableReader.read(file, options)) {
            expectedColumns = expected.columns().stream()
                    .map(column -> new IngestService.Column(column.name(), column.arrowType())).toList();
            expectedRows = new ArrayList<>();
            for (int row = 0; row < expected.rowCount(); row++) {
                List<String> values = new ArrayList<>();
                for (int column = 0; column < expected.columns().size(); column++) {
                    values.add(DuckDbSession.format(expected.get(column, row)));
                }
                expectedRows.add(values);
            }
        }

        IngestService.Result result = service().ingest(file, "xlsx");

        assertTrue(Files.isRegularFile(result.path()));
        assertFalse(result.cached());
        assertEquals(2, result.rows());
        assertEquals(expectedColumns, result.columns());
        assertTrue(expectedColumns.contains(new IngestService.Column("born", "Date32")),
                "date inference must survive the streaming ingest path: " + expectedColumns);
        expectedRows.sort(Comparator.comparing(values -> values.get(0)));
        assertEquals(expectedRows, rows(result.path()));
        assertEquals("2024-01-01", expectedRows.get(0).get(3));
        assertEquals("2024-03-04", expectedRows.get(1).get(3));
        assertEquals(null, expectedRows.get(1).get(2), "missing shared-string cell must stay NULL");

        IngestService.Result cached = service().ingest(file, "xlsx");
        assertTrue(cached.cached(), "ingest must keep its rows/columns/cached behaviour");
        assertEquals(result.path(), cached.path());
    }

    @Test
    void excelIngestUsesBoundedChunksRegardlessOfRowCount() throws Exception {
        Path small = writeWideXlsx(dir, "small-wide.xlsx", 2_000);
        Path large = writeWideXlsx(dir, "large-wide.xlsx", 20_000);
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            XlsxTableReader.LoadResult first = IngestService.loadExcel(connection, "ingest_small", small, 512);
            XlsxTableReader.LoadResult second = IngestService.loadExcel(connection, "ingest_large", large, 512);

            assertEquals(2_000, first.rows());
            assertEquals(20_000, second.rows());
            assertEquals(512, first.maxChunkRows());
            assertEquals(512, second.maxChunkRows(),
                    "peak buffered rows must not grow with the sheet size");
            assertEquals(10, second.columns().size());
        }
        assertEquals(2_000, service().ingest(small, "xlsx").rows());
        assertEquals(20_000, service().ingest(large, "xlsx").rows());
    }

    @Test
    void largeSharedStringsAreSpooledAndKeepTheirValues() throws Exception {
        String previous = System.getProperty("dap.xlsx.sst.heapBytes");
        System.setProperty("dap.xlsx.sst.heapBytes", "1");
        try {
            String shared = "<?xml version=\"1.0\"?><sst xmlns=\"" + XLSX_NS + "\">"
                    + "<si><t>alpha</t></si><si><t></t></si>"
                    + "<si><t>beta &amp; &lt;tag&gt;</t></si><si><t>gamma</t></si></sst>";
            String sheet = "<row r=\"1\">"
                    + "<c r=\"A1\" t=\"inlineStr\"><is><t>a</t></is></c>"
                    + "<c r=\"B1\" t=\"inlineStr\"><is><t>b</t></is></c>"
                    + "<c r=\"C1\" t=\"inlineStr\"><is><t>c</t></is></c>"
                    + "<c r=\"D1\" t=\"inlineStr\"><is><t>d</t></is></c></row>"
                    + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c><c r=\"B2\" t=\"s\"><v>1</v></c>"
                    + "<c r=\"C2\" t=\"s\"><v>2</v></c><c r=\"D2\" t=\"s\"><v>3</v></c></row>"
                    + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>3</v></c><c r=\"B3\" t=\"s\"><v>2</v></c>"
                    + "<c r=\"C3\" t=\"s\"><v>0</v></c><c r=\"D3\" t=\"s\"><v>1</v></c></row>";
            Path file = writeXlsx(dir, "spooled.xlsx", sheet, shared, null);

            IngestService.Result result = service().ingest(file, "xlsx");

            assertEquals(2, result.rows());
            assertEquals(List.of(
                    java.util.Arrays.asList("alpha", "", "beta & <tag>", "gamma"),
                    java.util.Arrays.asList("gamma", "beta & <tag>", "alpha", "")),
                    rows(result.path()), "ingest keeps empty strings (emptyStringIsNull=false)");
        } finally {
            if (previous == null) {
                System.clearProperty("dap.xlsx.sst.heapBytes");
            } else {
                System.setProperty("dap.xlsx.sst.heapBytes", previous);
            }
        }
    }

    @Test
    void oversizedSharedStringTableFailsWithActionableMessage() throws Exception {
        String previous = System.getProperty("dap.xlsx.sst.maxStrings");
        System.setProperty("dap.xlsx.sst.maxStrings", "3");
        try {
            StringBuilder sst = new StringBuilder("<?xml version=\"1.0\"?><sst xmlns=\"")
                    .append(XLSX_NS).append("\">");
            for (int index = 0; index < 5; index++) {
                sst.append("<si><t>value-").append(index).append("</t></si>");
            }
            Path file = writeXlsx(dir, "sst-limit.xlsx",
                    "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>name</t></is></c></row>"
                            + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c></row>",
                    sst.append("</sst>").toString(), null);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> service().ingest(file, "xlsx"));

            assertTrue(failure.getMessage().contains("shared strings"), failure.getMessage());
            assertTrue(failure.getMessage().contains("split the file or convert it to CSV"),
                    failure.getMessage());
        } finally {
            if (previous == null) {
                System.clearProperty("dap.xlsx.sst.maxStrings");
            } else {
                System.setProperty("dap.xlsx.sst.maxStrings", previous);
            }
        }
    }

    // ------------------------------------------------ encodings, dialects, sheets

    @Test
    void cp1251SemicolonCsvIsDetectedAndTranscoded() throws Exception {
        Path source = dir.resolve("rating.csv");
        Files.write(source, "Год;Подразделение\n2025;УППР\n2026;ЦДС\n"
                .getBytes(Charset.forName("windows-1251")));

        IngestService.Result result = service().ingest(source, "csv");

        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("Год", "Int64"),
                new IngestService.Column("Подразделение", "Utf8")), result.columns());
        assertEquals("windows-1251", result.encoding());
    }

    @Test
    void explicitCp1251AndSemicolonAreHonoured() throws Exception {
        Path source = dir.resolve("rating-explicit.csv");
        Files.write(source, "Год;Подразделение\n2025;УППР\n"
                .getBytes(Charset.forName("windows-1251")));
        IngestService.IngestOptions options = new IngestService.IngestOptions(
                true, ";", "\"", "\"", false, false, true, List.of(), "cp1251", false, false, null);

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(1, result.rows());
        assertEquals("Год", result.columns().get(0).name());
        assertEquals("windows-1251", result.encoding());
    }

    @Test
    void semicolonDelimiterIsSniffedWhenDelimiterIsNotGiven() throws Exception {
        Path source = write("semicolon.csv", "id;name;score\n1;alpha;9.5\n2;beta;7.0\n");

        IngestService.Result result = service().ingest(source, "csv");

        assertEquals(2, result.rows());
        assertEquals(List.of("id", "name", "score"),
                result.columns().stream().map(IngestService.Column::name).toList());
    }

    @Test
    void unsupportedEncodingIsRejectedWithAHint() throws Exception {
        Path source = write("people.csv", "id\n1\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().ingest(source, "csv", new IngestService.IngestOptions(
                        true, null, "\"", "\"", false, false, true, List.of(), "cp9999",
                        false, false, null)));

        assertTrue(failure.getMessage().contains("unsupported CSV encoding"),
                failure.getMessage());
    }

    @Test
    void allVarcharKeepsEveryColumnAsText() throws Exception {
        Path source = write("numbers.csv", "id,score\n1,9.5\n2,7\n");
        IngestService.IngestOptions options = new IngestService.IngestOptions(
                true, null, "\"", "\"", false, false, true, List.of(), null, true, false, null);

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(List.of(
                new IngestService.Column("id", "Utf8"),
                new IngestService.Column("score", "Utf8")), result.columns());
    }

    @Test
    void decimalCommaTurnsRussianNumbersIntoDoubles() throws Exception {
        // A decimal comma needs an explicit non-comma delimiter; otherwise DuckDB sniffs ','.
        Path source = write("money.csv", "Сумма\n5 449,72\n1 234,50\n");
        IngestService.IngestOptions options = new IngestService.IngestOptions(
                true, ";", "\"", "\"", false, false, true, List.of(), null, false, true, null);

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(List.of(new IngestService.Column("Сумма", "Float64")), result.columns());
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT sum(\"Сумма\") FROM read_parquet("
                     + literal(result.path()) + ")")) {
            assertTrue(rows.next());
            assertEquals(6684.22, rows.getDouble(1), 0.0001);
        }
    }

    @Test
    void excelSheetCanBeSelectedAndIsReported() throws Exception {
        Path file = writeTwoSheetXlsx(dir, "two-sheets.xlsx");
        IngestService.IngestOptions options = new IngestService.IngestOptions(
                true, null, "\"", "\"", false, false, true, List.of(), null, false, false, "Second");

        IngestService.Result result = service().ingest(file, "xlsx", options);

        assertEquals(1, result.rows());
        assertEquals(List.of(new IngestService.Column("value", "Int64")), result.columns());
        assertEquals("Second", result.sheet());
        assertEquals(List.of("First", "Second"), result.sheets());
    }

    @Test
    void unknownExcelSheetListsAvailableOnes() throws Exception {
        Path file = writeTwoSheetXlsx(dir, "two-sheets.xlsx");
        IngestService.IngestOptions options = new IngestService.IngestOptions(
                true, null, "\"", "\"", false, false, true, List.of(), null, false, false, "Missing");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().ingest(file, "xlsx", options));

        assertTrue(failure.getMessage().contains("available sheets"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Second"), failure.getMessage());
    }

    @Test
    void legacyXlsFailsWithAnActionableMessage() throws Exception {
        Path file = dir.resolve("old.xls");
        Files.write(file, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0, 0, 0});

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().ingest(file, "excel"));

        assertTrue(failure.getMessage().contains("legacy .xls"), failure.getMessage());
        assertTrue(failure.getMessage().contains(".xlsx"), failure.getMessage());
    }

    @Test
    void binaryFileIsRejectedAsCsvWithAClearMessage() throws Exception {
        Path file = dir.resolve("book.xls");
        Files.write(file, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0, 0, 0, 0, 0, 0, 0});

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().ingest(file, "csv"));

        assertTrue(failure.getMessage().contains("binary content"), failure.getMessage());
    }

    @Test
    void closedPendingResultWrapperIsStrippedFromTheMessage() {
        String message = "Invalid Input Error: Attempting to execute an unsuccessful or closed "
                + "pending query result\nError: Invalid Input Error: CSV Error on Line: 1\n"
                + "Invalid unicode (byte sequence mismatch) detected.";

        String described = IngestService.describeFailure(new IllegalStateException(message));

        assertTrue(described.startsWith("Invalid Input Error: CSV Error on Line: 1"), described);
        assertTrue(described.contains("Invalid unicode"), described);
    }

    /** Two-sheet workbook: "First" (n=1) and "Second" (value=42). */
    private static Path writeTwoSheetXlsx(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            putXlsx(zip, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types/>");
            putXlsx(zip, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships>"
                    + "<Relationship Id=\"rId1\" Target=\"xl/workbook.xml\"/></Relationships>");
            putXlsx(zip, "xl/workbook.xml", "<?xml version=\"1.0\"?>"
                    + "<workbook xmlns=\"" + XLSX_NS + "\" xmlns:r=\"" + REL_NS + "\"><sheets>"
                    + "<sheet name=\"First\" sheetId=\"1\" r:id=\"rId1\"/>"
                    + "<sheet name=\"Second\" sheetId=\"2\" r:id=\"rId2\"/>"
                    + "</sheets></workbook>");
            putXlsx(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships>"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/worksheet\""
                    + " Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"" + REL_NS + "/worksheet\""
                    + " Target=\"worksheets/sheet2.xml\"/>"
                    + "</Relationships>");
            putXlsx(zip, "xl/worksheets/sheet1.xml", "<?xml version=\"1.0\"?>"
                    + "<worksheet xmlns=\"" + XLSX_NS + "\"><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>n</t></is></c></row>"
                    + "<row r=\"2\"><c r=\"A2\"><v>1</v></c></row>"
                    + "</sheetData></worksheet>");
            putXlsx(zip, "xl/worksheets/sheet2.xml", "<?xml version=\"1.0\"?>"
                    + "<worksheet xmlns=\"" + XLSX_NS + "\"><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>value</t></is></c></row>"
                    + "<row r=\"2\"><c r=\"A2\"><v>42</v></c></row>"
                    + "</sheetData></worksheet>");
        }
        return file;
    }
}
