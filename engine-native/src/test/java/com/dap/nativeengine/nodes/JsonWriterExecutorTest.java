package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonWriterExecutorTest {
    @TempDir
    Path dir;

    private static final List<ColumnSpec> COLUMNS =
            List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"));

    private static LazyPlan people(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, COLUMNS, List.of(
                new Object[]{1L, "ann"},
                new Object[]{2L, "bob"}));
    }

    private static String fileConfig(Path file) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"}";
    }

    private static long jsonCount(DuckDbSession db, Path file) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM read_json_auto('" + file + "')")) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    void writesAJsonArrayAndPassesThePlanThrough() throws Exception {
        Path file = dir.resolve("out.json");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            LazyPlan plan = new JsonWriterExecutor().execute(context, List.of(input));

            assertSame(input, plan);
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            assertTrue(content.startsWith("["), content);
            assertTrue(content.endsWith("]"), content);
            assertEquals(2, jsonCount(db, file));
        }
    }

    @Test
    void writesNewlineDelimitedJsonWhenArrayIsFalse() throws Exception {
        Path file = dir.resolve("out.ndjson");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = "{\"file\":\"" + NodeTestSupport.path(file) + "\",\"array\":false}";
            NodeContext context = NodeTestSupport.context(db, job, config);

            new JsonWriterExecutor().execute(context, List.of(people(db)));

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertEquals(2, lines.size());
            for (String line : lines) {
                assertTrue(line.strip().startsWith("{"), line);
                assertTrue(line.strip().endsWith("}"), line);
            }
            assertEquals(2, jsonCount(db, file));
        }
    }

    @Test
    void overwritesAnExistingFile() throws Exception {
        Path file = dir.resolve("out.json");
        Files.writeString(file, "[{\"id\":9,\"name\":\"stale\"}]", StandardCharsets.UTF_8);
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new JsonWriterExecutor().execute(context, List.of(people(db)));

            assertEquals(2, jsonCount(db, file));
            assertEquals(3, countWhere(db, file, "SUM(id)"));
        }
    }

    private static long countWhere(DuckDbSession db, Path file, String expression) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT " + expression + " FROM read_json_auto('" + file + "')")) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    void emptyTableWritesAnEmptyJsonArray() throws Exception {
        Path file = dir.resolve("empty.json");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of());
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new JsonWriterExecutor().execute(context, List.of(input));

            String content = Files.readString(file, StandardCharsets.UTF_8);
            assertEquals("[]", content.replaceAll("\\s+", ""));
            assertEquals(0, jsonCount(db, file));
        }
    }

    @Test
    void rejectsANonBooleanArrayFlag() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = "{\"file\":\"" + NodeTestSupport.path(dir.resolve("out.json"))
                    + "\",\"array\":\"yes\"}";
            NodeContext context = NodeTestSupport.context(db, job, config);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new JsonWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("array"), failure.getMessage());
        }
    }

    @Test
    void rejectsMissingFileConfig() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, "{}");

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new JsonWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("file"), failure.getMessage());
        }
    }
}
