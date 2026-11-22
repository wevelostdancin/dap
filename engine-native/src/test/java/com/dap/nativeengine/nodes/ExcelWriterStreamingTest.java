package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.xlsx.XlsxTableReader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Streaming contract of the xlsx writer: bounded pages, bounded write buffer, same workbook. */
class ExcelWriterStreamingTest {
    private static final List<ColumnSpec> COLUMNS =
            List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("text", "Utf8"));

    @TempDir
    Path dir;

    /** Records the largest single write instead of accumulating the whole sheet. */
    private static final class TrackingAppendable implements Appendable {
        private int maxChunk;
        private long totalChars;
        private final StringBuilder tail = new StringBuilder(128);

        @Override
        public Appendable append(CharSequence text) throws IOException {
            return append(text, 0, text.length());
        }

        @Override
        public Appendable append(CharSequence text, int start, int end) throws IOException {
            int length = end - start;
            maxChunk = Math.max(maxChunk, length);
            totalChars += length;
            tail.setLength(0);
            tail.append(text, Math.max(start, end - 128), end);
            if (tail.length() > 128) {
                tail.delete(0, tail.length() - 128);
            }
            return this;
        }

        @Override
        public Appendable append(char character) throws IOException {
            maxChunk = Math.max(maxChunk, 1);
            totalChars++;
            if (tail.length() == 128) {
                tail.deleteCharAt(0);
            }
            tail.append(character);
            return this;
        }
    }

    @Test
    void writes100kRowsInBoundedPagesWithoutBuildingTheWholeSheet() throws Exception {
        int total = 100_000;
        AtomicInteger maxPage = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        ExcelWriterExecutor.PageSource pages = (limit, offset) -> {
            assertTrue(limit <= ExcelWriterExecutor.PAGE_ROWS, "page limit " + limit);
            int size = Math.min(limit, total - offset);
            maxPage.accumulateAndGet(size, Math::max);
            calls.incrementAndGet();
            List<List<String>> data = new ArrayList<>(size);
            for (int row = 0; row < size; row++) {
                data.add(List.of(Integer.toString(offset + row), "value-" + (offset + row)));
            }
            return new TableData(total, COLUMNS, data);
        };
        TrackingAppendable xml = new TrackingAppendable();

        long written = ExcelWriterExecutor.writeSheet(xml, pages);

        assertEquals(total, written);
        assertEquals(ExcelWriterExecutor.PAGE_ROWS, maxPage.get());
        assertEquals(total / ExcelWriterExecutor.PAGE_ROWS, calls.get());
        assertTrue(xml.maxChunk < 300, "single append of " + xml.maxChunk + " chars holds a row, not the sheet");
        assertTrue(xml.totalChars > 5_000_000, "all rows written, got " + xml.totalChars);
        assertTrue(xml.tail.toString().endsWith("</sheetData></worksheet>"), xml.tail.toString());
    }

    @Test
    void rejectsTablesAboveTheSheetRowLimit() {
        ExcelWriterExecutor.PageSource oversized = (limit, offset) ->
                new TableData(ExcelWriterExecutor.MAX_ROWS + 1L, COLUMNS,
                        List.of(List.of("1", "x")));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ExcelWriterExecutor.writeSheet(new StringBuilder(), oversized));

        assertTrue(failure.getMessage().contains("1048575"), failure.getMessage());
    }

    @Test
    void roundTripsPagedDataWithNullsDatesAndUnicode() throws Exception {
        Path file = dir.resolve("paged.xlsx");
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("id", "Int64"),
                ColumnSpec.of("name", "Utf8"),
                ColumnSpec.of("born", "Date32"));
        LazyPlan input = new LazyPlan(new DuckDbSession(),
                "SELECT * FROM (VALUES (1, 'Анна & <b>', DATE '2024-01-02'),"
                        + " (2, NULL, DATE '2023-12-31')) AS t(id, name, born)", columns);
        NodeContext context = NodeTestSupport.context(input.session(), null,
                "{\"file\":\"" + NodeTestSupport.path(file) + "\"}");

        new ExcelWriterExecutor().execute(context, List.of(input));

        XlsxTableReader.Options options = new XlsxTableReader.Options("Sheet1", true, columns, false);
        try (ArrowTable table = XlsxTableReader.read(file, options)) {
            assertEquals(columns, table.columns());
            assertEquals(2, table.rowCount());
            assertEquals(1L, table.get(0, 0));
            assertEquals("Анна & <b>", table.get(1, 0));
            assertNull(table.get(1, 1));
            assertEquals(LocalDate.of(2024, 1, 2), table.get(2, 0));
            assertEquals(LocalDate.of(2023, 12, 31), table.get(2, 1));
        } finally {
            input.session().close();
        }
    }

    private static LazyPlan largePlan(DuckDbSession db) {
        List<ColumnSpec> columns = new ArrayList<>();
        columns.add(ColumnSpec.of("id", "Int64"));
        StringBuilder sql = new StringBuilder("SELECT CAST(i AS BIGINT) AS id");
        for (int column = 1; column < 10; column++) {
            columns.add(ColumnSpec.of("s" + column, "Utf8"));
            sql.append(", 'row-' || CAST(i AS VARCHAR) AS s").append(column);
        }
        sql.append(" FROM range(0, 200000) t(i)");
        return new LazyPlan(db, sql.toString(), columns);
    }

    /** StAX row count: reads the produced sheet without loading it into heap. */
    private static long sheetRows(Path file) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile());
             java.io.InputStream in = zip.getInputStream(
                     zip.getEntry("xl/worksheets/sheet1.xml"))) {
            javax.xml.stream.XMLStreamReader xml = javax.xml.stream.XMLInputFactory.newFactory()
                    .createXMLStreamReader(in);
            long rows = 0;
            while (xml.hasNext()) {
                if (xml.next() == javax.xml.stream.XMLStreamReader.START_ELEMENT
                        && "row".equals(xml.getLocalName())) {
                    rows++;
                }
            }
            return rows;
        }
    }

    /** Manual check: {@code -Dgroups=soak -Dtest=ExcelWriterStreamingTest#writes200kRowsWith256mHeap
     * -Ddap.test.jvmargs=-Xmx256m}. */
    @Test
    @Tag("soak")
    void writes200kRowsWith256mHeap() throws Exception {
        Path file = dir.resolve("large-256m.xlsx");
        long started = System.nanoTime();
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job,
                    "{\"file\":\"" + NodeTestSupport.path(file) + "\"}");
            new ExcelWriterExecutor().execute(context, List.of(largePlan(db)));
        }
        long written = System.nanoTime();
        assertEquals(200_001, sheetRows(file)); // header + 200k data rows
        System.out.printf("xlsx 200k x 10 write (-Xmx256m): %.2fs, file %.1f MB%n",
                (written - started) / 1e9, java.nio.file.Files.size(file) / 1e6);
    }

    /** The reader parses the whole sheet in heap, so this needs the default heap. */
    @Test
    @Tag("soak")
    void roundTrips200kRows() throws Exception {
        Path file = dir.resolve("large-roundtrip.xlsx");
        long started = System.nanoTime();
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = largePlan(db);
            NodeContext context = NodeTestSupport.context(db, job,
                    "{\"file\":\"" + NodeTestSupport.path(file) + "\"}");
            new ExcelWriterExecutor().execute(context, List.of(input));
            long written = System.nanoTime();

            XlsxTableReader.Options options =
                    new XlsxTableReader.Options("Sheet1", true, input.columns(), false);
            try (ArrowTable table = XlsxTableReader.read(file, options)) {
                assertEquals(200_000, table.rowCount());
                assertEquals(0L, table.get(0, 0));
                assertEquals("row-199999", table.get(1, 199_999));
                assertEquals("row-199999", table.get(9, 199_999));
            }
            long read = System.nanoTime();
            System.out.printf("xlsx 200k x 10: write %.2fs, read %.2fs, file %.1f MB%n",
                    (written - started) / 1e9, (read - written) / 1e9,
                    java.nio.file.Files.size(file) / 1e6);
        }
    }
}
