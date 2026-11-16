package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.Workflow;
import com.dap.nativeengine.exec.WorkflowNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DbReaderExecutorTest {
    private static final Path DATA = Path.of(System.getProperty("dap.project.root", "."),
            "tests", "goldens-native", "data");

    @TempDir
    Path tempDir;

    private static LazyPlan plan(DuckDbSession db, Connection connection, ObjectNode config)
            throws Exception {
        return new DbReaderExecutor().execute(NodeTestSupport.context(db, connection,
                Json.write(config)), List.of());
    }

    private static ObjectNode base(String driver) {
        ObjectNode config = Json.object();
        config.put("driver", driver);
        return config;
    }

    private static Map<String, List<String>> byId(TableData data) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (List<String> row : data.data()) {
            rows.put(row.get(0), row);
        }
        return rows;
    }

    private static String extensionDirectory() {
        String configured = System.getenv("DAP_DUCKDB_EXTENSION_DIR");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path build = Path.of(System.getProperty("dap.project.root", "."), "build", "duckdb-ext");
        return Files.isDirectory(build.resolve("v1.5.6")) ? build.toString() : null;
    }

    /** Point the session at the local extension cache and load the scanner, or skip the test. */
    private static void requireExtension(DuckDbSession db, String extension) throws Exception {
        String directory = extensionDirectory();
        if (directory != null) {
            db.execute("SET extension_directory='" + directory.replace("'", "''") + "'");
        }
        boolean loaded;
        try {
            db.execute("LOAD " + extension);
            loaded = true;
        } catch (Exception missing) {
            loaded = false;
        }
        assumeTrue(loaded, extension + " is not installed; run make extensions");
    }

    @Test
    void duckDbFileAttachReadsTheTable() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            ObjectNode config = base("duckdb");
            config.put("path", DATA.resolve("fixture.duckdb").toString());
            config.put("table", "items");

            LazyPlan plan = plan(db, connection, config);
            assertEquals(List.of(
                    ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("name", "Utf8"),
                    ColumnSpec.of("score", "Float64"),
                    ColumnSpec.of("created", "Utf8")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            Map<String, List<String>> rows = byId(data);
            assertEquals(List.of("1", "alpha", "1.5", "2026-01-01"), rows.get("1"));
            assertEquals(List.of("2", "beta", "2.5", "2026-01-02"), rows.get("2"));
            assertEquals(List.of("3", "gamma"), rows.get("3").subList(0, 2));
            assertNull(rows.get("3").get(2));
            assertEquals("2026-01-03", rows.get("3").get(3));
        }
    }

    @Test
    void sqliteFileAttachReadsTheTable() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            requireExtension(db, "sqlite_scanner");
            ObjectNode config = base("sqlite");
            config.put("path", DATA.resolve("fixture.db").toString());
            config.put("table", "items");

            LazyPlan plan = plan(db, connection, config);
            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("1", "alpha", "1.5", "2026-01-01"), byId(data).get("1"));
            assertNull(byId(data).get("3").get(2));
        }
    }

    @Test
    void queryModeSeesTheAttachedTablesByName() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            ObjectNode config = base("duckdb");
            config.put("path", DATA.resolve("fixture.duckdb").toString());
            config.put("query", "SELECT id, name FROM items WHERE score IS NOT NULL ORDER BY id");

            LazyPlan plan = plan(db, connection, config);
            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8")),
                    plan.columns());
            assertEquals(List.of(List.of("1", "alpha"), List.of("2", "beta")),
                    plan.preview(10).data());
        }
    }

    @Test
    void missingExtensionIsReportedWithTheMakeExtensionsHint() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            Path empty = tempDir.resolve("extensions");
            Files.createDirectories(empty);
            db.execute("SET extension_directory='" + empty.toString().replace("'", "''") + "'");
            db.execute("SET autoinstall_known_extensions=false");
            db.execute("SET autoload_known_extensions=false");

            ObjectNode config = base("sqlite");
            config.put("path", DATA.resolve("fixture.db").toString());
            config.put("table", "items");

            ApiException failure = assertThrows(ApiException.class, () -> plan(db, connection, config));
            assertEquals(500, failure.status());
            assertEquals("extension_missing", failure.code());
            assertTrue(failure.getMessage().contains("make extensions"), failure.getMessage());
            assertTrue(failure.hint().contains("make extensions"), failure.hint());
        }
    }

    @Test
    void invalidConfigIsRejectedBeforeConnecting() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            ObjectNode both = base("duckdb");
            both.put("path", DATA.resolve("fixture.duckdb").toString());
            both.put("table", "items");
            both.put("query", "SELECT 1");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> plan(db, connection, both))
                    .getMessage().contains("exactly one"));

            ObjectNode neither = base("duckdb");
            neither.put("path", DATA.resolve("fixture.duckdb").toString());
            assertTrue(assertThrows(IllegalArgumentException.class, () -> plan(db, connection, neither))
                    .getMessage().contains("exactly one"));

            ObjectNode unknownDriver = base("oracle");
            unknownDriver.put("path", "x.db");
            unknownDriver.put("table", "items");
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> plan(db, connection, unknownDriver)).getMessage().contains("driver"));

            ObjectNode missingPath = base("duckdb");
            missingPath.put("table", "items");
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> plan(db, connection, missingPath)).getMessage().contains("path"));

            ObjectNode missingUrl = base("postgres");
            missingUrl.put("table", "items");
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> plan(db, connection, missingUrl)).getMessage().contains("url"));
        }
    }

    @Test
    void credentialsNeverReachTheSavedWorkflow() {
        Workflow workflow = new Workflow("wf-1", "reader", "session");
        WorkflowNode node = workflow.addNode("dap.db_reader", "Database Reader", Json.object());
        ObjectNode settings = Json.object();
        settings.put("driver", "postgres");
        settings.put("url", "postgresql://bob:secret@dbhost:5432/prod");
        settings.put("table", "items");
        settings.put("user", "bob");
        settings.put("password", "secret");
        workflow.setConfig(node.id(), settings);

        String saved = Json.write(workflow.toJson());
        assertTrue(saved.contains("dap.db_reader"));
        assertFalse(saved.contains("secret"), saved);
        assertFalse(saved.contains("\"password\""), saved);
        assertFalse(saved.contains("bob:secret"), saved);
    }

    @Test
    void errorMessagesDoNotLeakThePassword() throws Exception {
        ObjectNode config = base("postgres");
        config.put("url", "postgresql://bob:secret@dbhost:5432/prod");
        config.put("user", "bob");
        config.put("password", "secret");
        String masked = DbReaderExecutor.maskSecrets(
                "cannot attach postgresql://bob:secret@dbhost:5432/prod: connection refused", config);
        assertFalse(masked.contains("secret"), masked);
        assertTrue(masked.contains("postgresql://"), masked);
    }
}
