package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileListExecutorTest {
    @TempDir
    Path tempDir;

    private static LazyPlan plan(DuckDbSession db, ObjectNode config) throws Exception {
        try (java.sql.Connection connection = db.newConnection()) {
            return new FileListExecutor().execute(
                    NodeTestSupport.context(db, connection, Json.write(config)), List.of());
        }
    }

    private static ObjectNode config(Path directory) {
        ObjectNode config = Json.object();
        config.put("directory", directory.toString());
        return config;
    }

    private static Map<String, List<String>> byName(TableData data) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (List<String> row : data.data()) {
            rows.put(row.get(1), row);
        }
        return rows;
    }

    private void tree() throws Exception {
        Files.createDirectories(tempDir.resolve("nested"));
        Files.writeString(tempDir.resolve("alpha.csv"), "a,b\n1,2\n");
        Files.writeString(tempDir.resolve("beta.csv"), "a,b\n3,4\n5,6\n");
        Files.writeString(tempDir.resolve("notes.txt"), "hello\n");
        Files.writeString(tempDir.resolve("nested").resolve("gamma.csv"), "a,b\n7,8\n");
    }

    @Test
    void listsTheTreeWithTypesAndSizes() throws Exception {
        tree();
        try (DuckDbSession db = new DuckDbSession()) {
            ObjectNode config = config(tempDir);
            config.put("recursive", true);
            LazyPlan plan = plan(db, config);

            assertEquals(List.of(
                    ColumnSpec.of("path", "Utf8"),
                    ColumnSpec.of("name", "Utf8"),
                    ColumnSpec.of("extension", "Utf8"),
                    ColumnSpec.of("size", "Int64"),
                    ColumnSpec.of("modified", "Timestamp"),
                    ColumnSpec.of("is_directory", "Boolean")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(5, data.rows());
            Map<String, List<String>> rows = byName(data);
            assertEquals(List.of("alpha.csv", "csv", "8"), rows.get("alpha.csv").subList(1, 4));
            assertEquals("false", rows.get("alpha.csv").get(5));
            assertEquals(List.of("notes.txt", "txt"), rows.get("notes.txt").subList(1, 3));
            assertEquals("true", rows.get("nested").get(5));
            assertEquals("", rows.get("nested").get(2));
            assertEquals("false", rows.get("gamma.csv").get(5));
            assertTrue(rows.get("alpha.csv").get(0).endsWith("alpha.csv"));
        }
    }

    @Test
    void nonRecursiveListsOnlyTopLevelAndPatternFiltersNames() throws Exception {
        tree();
        try (DuckDbSession db = new DuckDbSession()) {
            ObjectNode config = config(tempDir);
            config.put("pattern", "*.csv");
            TableData data = plan(db, config).preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("alpha.csv", "beta.csv"), List.copyOf(byName(data).keySet()));

            ObjectNode nested = config(tempDir);
            nested.put("pattern", "*.csv");
            nested.put("recursive", true);
            assertEquals(List.of("alpha.csv", "beta.csv", "gamma.csv"),
                    List.copyOf(byName(plan(db, nested).preview(10)).keySet()));
        }
    }

    @Test
    void hiddenEntriesAreSkippedUnlessRequested() throws Exception {
        tree();
        Files.writeString(tempDir.resolve(".hidden.csv"), "a\n1\n");
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = plan(db, config(tempDir)).preview(10);
            assertFalse(byName(data).containsKey(".hidden.csv"));

            ObjectNode hidden = config(tempDir);
            hidden.put("include_hidden", true);
            assertTrue(byName(plan(db, hidden).preview(10)).containsKey(".hidden.csv"));
        }
    }

    @Test
    void sortsByNameSizeAndModified() throws Exception {
        tree();
        Files.writeString(tempDir.resolve("delta.csv"), "a,b\n9,10\n11,12\n");
        Files.setLastModifiedTime(tempDir.resolve("delta.csv"), FileTime.fromMillis(1_700_000_000_000L));
        Files.setLastModifiedTime(tempDir.resolve("beta.csv"), FileTime.fromMillis(1_700_000_100_000L));
        Files.setLastModifiedTime(tempDir.resolve("alpha.csv"), FileTime.fromMillis(1_700_000_200_000L));
        try (DuckDbSession db = new DuckDbSession()) {
            ObjectNode bySize = config(tempDir);
            bySize.put("pattern", "*.csv");
            bySize.put("sort", "size");
            assertEquals(List.of("alpha.csv", "beta.csv", "delta.csv"),
                    List.copyOf(byName(plan(db, bySize).preview(10)).keySet()));

            ObjectNode byModified = config(tempDir);
            byModified.put("pattern", "*.csv");
            byModified.put("sort", "modified");
            assertEquals(List.of("delta.csv", "beta.csv", "alpha.csv"),
                    List.copyOf(byName(plan(db, byModified).preview(10)).keySet()));
        }
    }

    @Test
    void limitCapsTheSortedResult() throws Exception {
        tree();
        try (DuckDbSession db = new DuckDbSession()) {
            ObjectNode config = config(tempDir);
            config.put("pattern", "*.csv");
            config.put("limit", 2);
            assertEquals(List.of("alpha.csv", "beta.csv"),
                    List.copyOf(byName(plan(db, config).preview(10)).keySet()));

            config.put("limit", 0);
            IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                    () -> plan(db, config));
            assertTrue(invalid.getMessage().contains("limit"), invalid.getMessage());
        }
    }

    @Test
    void symlinkLoopIsNotFollowed() throws Exception {
        tree();
        Path loop = tempDir.resolve("loop");
        try {
            Files.createSymbolicLink(loop, tempDir);
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            assumeTrue(false, "symlinks are not available");
        }
        try (DuckDbSession db = new DuckDbSession()) {
            ObjectNode config = config(tempDir);
            config.put("recursive", true);
            TableData data = plan(db, config).preview(100);
            assertEquals(1, data.data().stream().filter(row -> row.get(1).equals("loop")).count());
            assertEquals("false", byName(data).get("loop").get(5));
        }
    }

    @Test
    void missingDirectoryIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            ObjectNode config = config(tempDir.resolve("does-not-exist"));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> plan(db, config));
            assertTrue(failure.getMessage().contains("directory"), failure.getMessage());
        }
    }

    @Test
    void modifiedTimestampsAreUtc() throws Exception {
        tree();
        try (DuckDbSession db = new DuckDbSession()) {
            long millis = 1_700_000_000_000L;
            Files.setLastModifiedTime(tempDir.resolve("alpha.csv"), FileTime.fromMillis(millis));
            ObjectNode config = config(tempDir);
            config.put("pattern", "alpha.csv");
            List<String> row = plan(db, config).preview(10).data().get(0);
            assertEquals(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).toString(),
                    row.get(4));
        }
    }
}
