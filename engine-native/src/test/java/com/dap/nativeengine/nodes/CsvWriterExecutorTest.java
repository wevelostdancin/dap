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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvWriterExecutorTest {
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

    @Test
    void writesHeaderAndRowsAndPassesThePlanThrough() throws Exception {
        Path file = dir.resolve("out.csv");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            LazyPlan plan = new CsvWriterExecutor().execute(context, List.of(input));

            assertSame(input, plan);
            assertEquals(List.of("id,name", "1,ann", "2,bob"), Files.readAllLines(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void honoursDelimiterAndHeaderFlag() throws Exception {
        Path file = dir.resolve("out.csv");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = "{\"file\":\"" + NodeTestSupport.path(file) + "\",\"delimiter\":\";\",\"header\":false}";
            NodeContext context = NodeTestSupport.context(db, job, config);

            new CsvWriterExecutor().execute(context, List.of(people(db)));

            assertEquals(List.of("1;ann", "2;bob"), Files.readAllLines(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void escapesSingleQuotesInThePath() throws Exception {
        Path file = dir.resolve("it's.csv");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new CsvWriterExecutor().execute(context, List.of(people(db)));

            assertEquals(List.of("id,name", "1,ann", "2,bob"), Files.readAllLines(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void createsParentDirectories() throws Exception {
        Path file = dir.resolve("nested/deeper/out.csv");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new CsvWriterExecutor().execute(context, List.of(people(db)));

            assertTrue(Files.isRegularFile(file), file.toString());
        }
    }

    @Test
    void overwritesAnExistingFile() throws Exception {
        Path file = dir.resolve("out.csv");
        Files.writeString(file, "stale,rows\n9,x\n10,y\n", StandardCharsets.UTF_8);
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new CsvWriterExecutor().execute(context, List.of(people(db)));

            assertEquals(List.of("id,name", "1,ann", "2,bob"), Files.readAllLines(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void emptyTableWritesOnlyTheHeader() throws Exception {
        Path file = dir.resolve("empty.csv");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of());
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new CsvWriterExecutor().execute(context, List.of(input));

            assertEquals(List.of("id,name"), Files.readAllLines(file, StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsAMultiCharacterDelimiter() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = "{\"file\":\"" + NodeTestSupport.path(dir.resolve("out.csv"))
                    + "\",\"delimiter\":\";;\"}";
            NodeContext context = NodeTestSupport.context(db, job, config);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CsvWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("delimiter"), failure.getMessage());
        }
    }

    @Test
    void rejectsMissingFileConfig() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, "{}");

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new CsvWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("file"), failure.getMessage());
        }
    }
}
