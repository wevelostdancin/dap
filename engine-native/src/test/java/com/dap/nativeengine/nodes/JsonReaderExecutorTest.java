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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonReaderExecutorTest {
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

    @Test
    void readsATopLevelJsonArray() throws Exception {
        Path file = write("people.json",
                "[{\"id\":1,\"name\":\"ann\"},{\"id\":2,\"name\":\"bob\"}]");
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, fileConfig(file));
            LazyPlan plan = new JsonReaderExecutor().execute(context, List.of());

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("1", "ann"), data.data().get(0));
            assertEquals(List.of("2", "bob"), data.data().get(1));
        }
    }

    @Test
    void readsNewlineDelimitedJson() throws Exception {
        Path file = write("people.ndjson",
                "{\"id\":1,\"name\":\"ann\"}\n{\"id\":2,\"name\":\"bob\"}\n");
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, fileConfig(file));
            LazyPlan plan = new JsonReaderExecutor().execute(context, List.of());

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8")), plan.columns());
            assertEquals(2, plan.preview(10).rows());
            assertEquals(List.of("2", "bob"), plan.preview(10).data().get(1));
        }
    }

    @Test
    void infersNonStringTypesFromTheDocument() throws Exception {
        Path file = write("typed.json",
                "[{\"flag\":true,\"score\":2.5},{\"flag\":false,\"score\":3.0}]");
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, fileConfig(file));
            LazyPlan plan = new JsonReaderExecutor().execute(context, List.of());

            assertEquals(List.of(ColumnSpec.of("flag", "Boolean"), ColumnSpec.of("score", "Float64")),
                    plan.columns());
            assertEquals(List.of("true", "2.5"), plan.preview(10).data().get(0));
        }
    }

    @Test
    void missingFileIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            Path absent = dir.resolve("absent.json");
            NodeContext context = NodeTestSupport.context(db, fileConfig(absent));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new JsonReaderExecutor().execute(context, List.of()));
            assertTrue(failure.getMessage().contains("absent.json"), failure.getMessage());
        }
    }

    @Test
    void missingFileConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, "{}");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new JsonReaderExecutor().execute(context, List.of()));
            assertTrue(failure.getMessage().contains("file"), failure.getMessage());
        }
    }
}
