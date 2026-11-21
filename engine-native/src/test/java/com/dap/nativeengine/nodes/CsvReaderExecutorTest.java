package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvReaderExecutorTest {
    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String fileConfig(Path file) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"}";
    }

    private static List<String> firstColumn(LazyPlan plan) throws Exception {
        return plan.preview(10).data().stream().map(row -> row.get(0)).toList();
    }

    private static long tableCount(DuckDbSession db, String predicate) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM duckdb_tables() WHERE " + predicate)) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    void unquotedAndQuotedEmptyFieldsAreNull() throws Exception {
        Path file = write("empty.csv", "txt,num\n,1\n\"\",2\n\"x\",3\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));
            LazyPlan plan = new CsvReaderExecutor().execute(context, List.of());

            assertEquals(List.of(ColumnSpec.of("txt", "Utf8"), ColumnSpec.of("num", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertNull(data.data().get(0).get(0));
            assertNull(data.data().get(1).get(0));
            assertEquals("x", data.data().get(2).get(0));
        }
    }

    @Test
    void emptyStringIsNullIsAcceptedButHasNoEffect() throws Exception {
        Path file = write("empty-null.csv", "txt,num\n,1\n\"\",2\n\"x\",3\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            for (boolean flag : new boolean[]{false, true}) {
                String config = "{\"file\":\"" + NodeTestSupport.path(file)
                        + "\",\"emptyStringIsNull\":" + flag + "}";
                LazyPlan plan = new CsvReaderExecutor().execute(
                        NodeTestSupport.context(db, job, config), List.of());

                TableData data = plan.preview(10);
                assertEquals(3, data.rows());
                assertNull(data.data().get(0).get(0));
                assertNull(data.data().get(1).get(0));
                assertEquals(2, plan.profile().columns().get(0).missing());
                assertEquals("x", data.data().get(2).get(0));
            }
        }
    }

    @Test
    void typedColumnsCustomDelimiterAndHeaderRow() throws Exception {
        Path file = write("typed.csv",
                "id;amount;flag;day;ts\n1;2.5;true;2024-01-02;2024-01-02 03:04:05\n2;10;false;2024-02-03;2024-02-04 05:06:07\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","delimiter":";","header":true,"columns":[
                      {"name":"id","type":"Int64"},
                      {"name":"amount","type":"Float64"},
                      {"name":"flag","type":"Boolean"},
                      {"name":"day","type":"Date32"},
                      {"name":"ts","type":"Timestamp"}]}
                    """.formatted(NodeTestSupport.path(file));
            NodeContext context = NodeTestSupport.context(db, job, config);
            LazyPlan plan = new CsvReaderExecutor().execute(context, List.of());

            assertEquals(List.of(
                    ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("amount", "Float64"),
                    ColumnSpec.of("flag", "Boolean"),
                    ColumnSpec.of("day", "Date32"),
                    ColumnSpec.of("ts", "Timestamp")), plan.columns());
            assertEquals("Number (Integer)", plan.columns().get(0).knimeType());
            assertEquals("Number (Float)", plan.columns().get(1).knimeType());
            assertEquals("Boolean", plan.columns().get(2).knimeType());
            assertEquals("Date", plan.columns().get(3).knimeType());
            assertEquals("Date&Time (Timestamp)", plan.columns().get(4).knimeType());

            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("1", "2.5", "true", "2024-01-02", "2024-01-02T03:04:05"), data.data().get(0));
            assertEquals(List.of("2", "10.0", "false", "2024-02-03", "2024-02-04T05:06:07"), data.data().get(1));
        }
    }

    @Test
    void multilineQuotedFieldAndDoubledQuoteAreUnescaped() throws Exception {
        Path file = write("quotes.csv", "id,text\n1,\"line one\nline two\"\n2,\"say \"\"hi\"\"\"\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));
            LazyPlan plan = new CsvReaderExecutor().execute(context, List.of());

            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals("line one\nline two", data.data().get(0).get(1));
            assertEquals("say \"hi\"", data.data().get(1).get(1));
        }
    }

    @Test
    void tabDelimiterEscapeIsHonored() throws Exception {
        Path file = write("tab.csv", "id\tnote\n1\talpha\n2\tbeta\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = "{\"file\":\"" + NodeTestSupport.path(file) + "\",\"delimiter\":\"\\\\t\"}";
            LazyPlan plan = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, config), List.of());
            assertEquals(List.of("1", "2"), firstColumn(plan));
        }
    }

    @Test
    void gzipIsDetectedAndDecompressedByDuckDb() throws Exception {
        Path file = dir.resolve("gzip.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write("id,name\n1,alpha\n2,beta\n".getBytes(StandardCharsets.UTF_8));
        }
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(file)), List.of());
            assertEquals(List.of("alpha", "beta"),
                    plan.preview(10).data().stream().map(row -> row.get(1)).toList());
        }
    }

    @Test
    void missingFileFailsWithReadableMessage() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            Path absent = dir.resolve("absent.csv");
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(absent));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CsvReaderExecutor().execute(context, List.of()));
            assertTrue(failure.getMessage().contains("absent.csv"), failure.getMessage());
        }
    }

    @Test
    void configuredColumnAbsentFromHeaderFails() throws Exception {
        Path file = write("header.csv", "id,other\n1,2\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","columns":[{"name":"id","type":"Int64"},{"name":"missing","type":"Utf8"}]}
                    """.formatted(NodeTestSupport.path(file));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CsvReaderExecutor().execute(NodeTestSupport.context(db, job, config), List.of()));
            assertTrue(failure.getMessage().contains("missing"), failure.getMessage());
            assertTrue(failure.getMessage().contains("header"), failure.getMessage());
        }
    }

    @Test
    void unsupportedColumnTypeFails() throws Exception {
        Path file = write("bad-type.csv", "id\n1\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","columns":[{"name":"id","type":"Mystery"}]}
                    """.formatted(NodeTestSupport.path(file));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CsvReaderExecutor().execute(NodeTestSupport.context(db, job, config), List.of()));
            assertTrue(failure.getMessage().contains("unsupported type"), failure.getMessage());
            assertTrue(failure.getMessage().contains("Mystery"), failure.getMessage());
        }
    }

    @Test
    void unparseableTypedValueNamesColumn() throws Exception {
        Path file = write("bad-value.csv", "id\nnope\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","columns":[{"name":"id","type":"Int64"}]}
                    """.formatted(NodeTestSupport.path(file));
            LazyPlan plan = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, config), List.of());
            SQLException failure = assertThrows(SQLException.class, () -> plan.preview(10));
            assertTrue(failure.getMessage().contains("id"), failure.getMessage());
        }
    }

    @Test
    void zstdFileIsRejectedWithReadableMessage() throws Exception {
        Path file = dir.resolve("sample.csv.zst");
        Files.write(file, new byte[]{0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0, 0, 0, 0});
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CsvReaderExecutor().execute(
                            NodeTestSupport.context(db, job, fileConfig(file)), List.of()));
            assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains("zstd"), failure.getMessage());
        }
    }

    @Test
    void compressionMismatchFailsWithReadableMessage() throws Exception {
        Path plain = write("plain.csv", "id\n1\n");
        Path gzipped = dir.resolve("plain.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzipped))) {
            out.write("id\n1\n".getBytes(StandardCharsets.UTF_8));
        }
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String plainPath = NodeTestSupport.path(plain);
            String gzipPath = NodeTestSupport.path(gzipped);
            IllegalArgumentException plainAsGzip = assertThrows(IllegalArgumentException.class,
                    () -> new CsvReaderExecutor().execute(NodeTestSupport.context(db, job,
                            "{\"file\":\"" + plainPath + "\",\"compression\":\"gzip\"}"), List.of()));
            assertTrue(plainAsGzip.getMessage().contains("not gzip-compressed"), plainAsGzip.getMessage());

            IllegalArgumentException gzipAsNone = assertThrows(IllegalArgumentException.class,
                    () -> new CsvReaderExecutor().execute(NodeTestSupport.context(db, job,
                            "{\"file\":\"" + gzipPath + "\",\"compression\":\"none\"}"), List.of()));
            assertTrue(gzipAsNone.getMessage().contains("gzip-compressed"), gzipAsNone.getMessage());
        }
    }

    @Test
    void quotedEmptyValueIsNullWithInferredType() throws Exception {
        Path file = write("infer-quoted-empty.csv", "id,note\n1,\"\"\n2,\"\"\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(file)), List.of());

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("note", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertNull(data.data().get(0).get(1));
            assertNull(data.data().get(1).get(1));
        }
    }

    @Test
    void mixedNumbersAndQuotedEmptyInferInt64WithNull() throws Exception {
        Path file = write("infer-mixed.csv", "value\n1\n2\n\"\"\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(file)), List.of());

            assertEquals(List.of(ColumnSpec.of("value", "Int64")), plan.columns());
            assertEquals(Arrays.asList("1", "2", null), firstColumn(plan));
        }
    }

    @Test
    void duplicateHeaderNamesAreRenamedByDuckDb() throws Exception {
        Path file = write("duplicate-header.csv", "a,a,b\n1,2,3\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(file)), List.of());

            assertEquals(List.of(ColumnSpec.of("a", "Int64"),
                    ColumnSpec.of("a_1", "Int64"),
                    ColumnSpec.of("b", "Int64")), plan.columns());
            assertEquals(List.of("1", "2", "3"),
                    List.copyOf(plan.preview(10).data().get(0)));
        }
    }

    @Test
    void rerunReadsCurrentFileWithoutCreatingTables() throws Exception {
        Path first = write("run-1.csv", "id\n1\n2\n");
        Path second = write("run-2.csv", "id\n3\n4\n5\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan one = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(first)), List.of());
            assertEquals(List.of("1", "2"), firstColumn(one));

            LazyPlan two = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(second)), List.of());
            assertEquals(List.of("3", "4", "5"), firstColumn(two));
            assertEquals(List.of("1", "2"), firstColumn(one));

            assertEquals(0, tableCount(db, "lower(table_name) LIKE 'csv_%'"));
        }
    }
}
