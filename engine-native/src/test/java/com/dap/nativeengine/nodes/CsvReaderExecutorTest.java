package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

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
    void unquotedEmptyFieldIsNullAndQuotedEmptyFieldIsEmptyString() throws Exception {
        Path file = write("empty.csv", "id,note\n1,\n2,\"\"\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));
            LazyPlan plan = new CsvReaderExecutor().execute(context, List.of());

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("note", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertNull(data.data().get(0).get(1));
            assertEquals("", data.data().get(1).get(1));
        }
    }

    @Test
    void emptyStringIsNullTurnsQuotedEmptyIntoSqlNull() throws Exception {
        Path file = write("empty-null.csv", "id,note\n1,\n2,\"\"\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job,
                    "{\"file\":\"" + NodeTestSupport.path(file) + "\",\"emptyStringIsNull\":true}");
            LazyPlan plan = new CsvReaderExecutor().execute(context, List.of());

            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertNull(data.data().get(0).get(1));
            assertNull(data.data().get(1).get(1));
            assertEquals(2, plan.profile().columns().get(1).missing());
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
        }
    }

    @Test
    void unparseableTypedValueNamesRowAndColumn() throws Exception {
        Path file = write("bad-value.csv", "id\nnope\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = """
                    {"file":"%s","columns":[{"name":"id","type":"Int64"}]}
                    """.formatted(NodeTestSupport.path(file));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CsvReaderExecutor().execute(NodeTestSupport.context(db, job, config), List.of()));
            assertTrue(failure.getMessage().contains("id"), failure.getMessage());
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("row"), failure.getMessage());
        }
    }

    @Test
    void quotedEmptyValueForcesUtf8InferenceAndKeepsEmptyString() throws Exception {
        Path file = write("infer-quoted-empty.csv", "id,note\n1,\"\"\n2,\"\"\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(file)), List.of());

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("note", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals("", data.data().get(0).get(1));
            assertEquals("", data.data().get(1).get(1));
        }
    }

    @Test
    void mixedNumbersAndQuotedEmptyInferUtf8Strings() throws Exception {
        Path file = write("infer-mixed.csv", "value\n1\n2\n\"\"\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan plan = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(file)), List.of());

            assertEquals(List.of(ColumnSpec.of("value", "Utf8")), plan.columns());
            assertEquals(List.of("1", "2", ""), firstColumn(plan));
        }
    }

    @Test
    void duplicateHeaderNamesAreRejected() throws Exception {
        Path file = write("duplicate-header.csv", "a,a\n1,2\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CsvReaderExecutor().execute(
                            NodeTestSupport.context(db, job, fileConfig(file)), List.of()));
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate"),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("a"), failure.getMessage());
        }
    }

    @Test
    void rerunReplacesStableTableInsteadOfCreatingAnother() throws Exception {
        Path first = write("run-1.csv", "id\n1\n2\n");
        Path second = write("run-2.csv", "id\n3\n4\n5\n");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan one = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(first)), List.of());
            assertEquals(List.of("1", "2"), firstColumn(one));

            LazyPlan two = new CsvReaderExecutor().execute(
                    NodeTestSupport.context(db, job, fileConfig(second)), List.of());
            assertEquals(List.of("3", "4", "5"), firstColumn(two));

            assertEquals(1, tableCount(db, "lower(table_name) = 'csv_wf_node'"));
            assertEquals(1, tableCount(db, "lower(table_name) LIKE 'csv_%'"));
        }
    }
}
