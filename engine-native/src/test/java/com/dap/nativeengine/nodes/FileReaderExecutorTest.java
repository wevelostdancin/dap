package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.ApiException;
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
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dap.file_reader: multi-file/glob/directory reads through DuckDB's streaming readers. */
class FileReaderExecutorTest {
    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent() == null ? dir : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String config(String json) {
        return json;
    }

    private static String pathConfig(Path path, String format) {
        return "{\"path\":\"" + NodeTestSupport.path(path) + "\",\"format\":\"" + format + "\"}";
    }

    private static LazyPlan read(DuckDbSession db, String config) throws Exception {
        NodeContext context = NodeTestSupport.context(db, config);
        return new FileReaderExecutor().execute(context, List.of());
    }

    @Test
    void readsTwoCsvFilesAsOneTable() throws Exception {
        Path first = write("a.csv", "id,name\n1,ann\n2,bob\n");
        Path second = write("b.csv", "id,name\n3,cid\n4,dan\n");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = read(db, config("{\"paths\":[\"" + NodeTestSupport.path(first)
                    + "\",\"" + NodeTestSupport.path(second) + "\"],\"format\":\"csv\"}"));

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8")),
                    plan.columns());
            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            assertEquals(4, data.data().size());
        }
    }

    @Test
    void expandsAGlobAndSortsTheMatches() throws Exception {
        write("part-1.csv", "id\n1\n");
        write("part-2.csv", "id\n2\n");
        write("ignore.txt", "nope");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = read(db, config("{\"path\":\"" + NodeTestSupport.path(dir)
                    + "/*.csv\",\"format\":\"csv\"}"));

            assertEquals(List.of(ColumnSpec.of("id", "Int64")), plan.columns());
            assertEquals(2, plan.preview(10).rows());
        }
    }

    @Test
    void readsADirectoryRecursively() throws Exception {
        write("top.csv", "id\n1\n");
        write("nested/deep.csv", "id\n2\n");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan flat = read(db, config("{\"path\":\"" + NodeTestSupport.path(dir)
                    + "\",\"format\":\"csv\"}"));
            assertEquals(1, flat.preview(10).rows());

            LazyPlan recursive = read(db, config("{\"path\":\"" + NodeTestSupport.path(dir)
                    + "\",\"format\":\"csv\",\"recursive\":true}"));
            assertEquals(2, recursive.preview(10).rows());
        }
    }

    @Test
    void unionByNameMergesDifferentSchemas() throws Exception {
        write("one.csv", "id,a\n1,x\n");
        write("two.csv", "id,b\n2,y\n");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = read(db, config("{\"path\":\"" + NodeTestSupport.path(dir)
                    + "/*.csv\",\"format\":\"csv\",\"union_by_name\":true}"));

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("a", "Utf8"),
                    ColumnSpec.of("b", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            List<String> rows = data.data().stream().map(row -> String.join("|",
                    row.stream().map(value -> value == null ? "<null>" : value).toList())).sorted().toList();
            assertEquals(List.of("1|x|<null>", "2|<null>|y"), rows);
        }
    }

    @Test
    void readsAParquetDirectory() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            for (int part = 1; part <= 2; part++) {
                Path file = dir.resolve("part-" + part + ".parquet");
                try (Connection connection = db.newConnection();
                     Statement statement = connection.createStatement()) {
                    statement.execute("COPY (SELECT " + part + "::BIGINT AS id, 'p" + part
                            + "' AS tag) TO '" + file + "' (FORMAT PARQUET)");
                }
            }
            LazyPlan plan = read(db, pathConfig(dir, "parquet"));

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("tag", "Utf8")),
                    plan.columns());
            assertEquals(2, plan.preview(10).rows());
        }
    }

    @Test
    void readsNewlineDelimitedJson() throws Exception {
        Path file = write("rows.jsonl", "{\"id\":1,\"name\":\"ann\"}\n{\"id\":2,\"name\":\"bob\"}\n");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = read(db, pathConfig(file, "json"));

            assertEquals(2, plan.preview(10).rows());
            assertTrue(plan.columns().stream().anyMatch(column -> column.name().equals("name")),
                    plan.columns().toString());
        }
    }

    @Test
    void filenameSettingAddsTheSourceFileColumn() throws Exception {
        write("a.csv", "id\n1\n");
        write("b.csv", "id\n2\n");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = read(db, config("{\"path\":\"" + NodeTestSupport.path(dir)
                    + "/*.csv\",\"format\":\"csv\",\"filename\":true}"));

            assertTrue(plan.columns().stream().anyMatch(column -> column.name().equals("filename")),
                    plan.columns().toString());
            TableData data = plan.preview(10);
            List<String> names = data.data().stream().map(row -> row.get(1)).sorted().toList();
            assertTrue(names.get(0).endsWith("a.csv"), names.toString());
            assertTrue(names.get(1).endsWith("b.csv"), names.toString());
        }
    }

    @Test
    void csvOptionsAreApplied() throws Exception {
        write("semi.csv", "id;name\n1;ann\n");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = read(db, config("{\"path\":\"" + NodeTestSupport.path(dir)
                    + "/*.csv\",\"format\":\"csv\",\"delimiter\":\";\"}"));

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8")),
                    plan.columns());
            assertEquals(1, plan.preview(10).rows());
        }
    }

    @Test
    void emptyMatchSetIsARejectedWithAHint() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, config("{\"path\":\"" + NodeTestSupport.path(dir)
                            + "/*.csv\",\"format\":\"csv\"}")));
            assertTrue(failure.getMessage().contains("no files"), failure.getMessage());
        }
    }

    @Test
    void unknownFormatListsTheSupportedOnes() throws Exception {
        Path file = write("a.txt", "x");
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> read(db, pathConfig(file, "xml")));
            for (String format : List.of("csv", "parquet", "json")) {
                assertTrue(failure.getMessage().contains(format), failure.getMessage());
            }
        }
    }

    @Test
    void orcAndAvroAreRejectedWithAConversionHint() throws Exception {
        Path file = write("a.orc", "x");
        try (DuckDbSession db = new DuckDbSession()) {
            for (String format : List.of("orc", "avro")) {
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> read(db, pathConfig(file, format)));
                assertTrue(failure.getMessage().toLowerCase().contains("convert"), failure.getMessage());
            }
        }
    }

    @Test
    void deniedPathIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> read(db, "{\"path\":\"/etc/passwd\",\"format\":\"csv\"}"));
            assertEquals("path_not_allowed", failure.code(), failure.getMessage());
        }
    }

    @Test
    void pathAndPathsAreMutuallyExclusive() throws Exception {
        Path file = write("a.csv", "id\n1\n");
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
                    () -> read(db, config("{\"path\":\"" + NodeTestSupport.path(file)
                            + "\",\"paths\":[\"" + NodeTestSupport.path(file)
                            + "\"],\"format\":\"csv\"}")));
            assertTrue(both.getMessage().contains("path"), both.getMessage());

            IllegalArgumentException neither = assertThrows(IllegalArgumentException.class,
                    () -> read(db, config("{\"format\":\"csv\"}")));
            assertTrue(neither.getMessage().contains("path"), neither.getMessage());
        }
    }
}
