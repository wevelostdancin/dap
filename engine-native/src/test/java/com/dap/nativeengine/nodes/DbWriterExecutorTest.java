package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dap.db_writer: writes the input table into duckdb/sqlite/postgres/mysql and passes it through. */
class DbWriterExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"));
    private static final List<Object[]> ROWS = List.<Object[]>of(
            new Object[]{1L, "ann"}, new Object[]{2L, "bob"});

    @TempDir
    Path dir;

    private static String config(Path connection, String mode) {
        return "{\"driver\":\"duckdb\",\"connection\":\"" + NodeTestSupport.path(connection)
                + "\",\"table\":\"people\",\"mode\":\"" + mode + "\"}";
    }

    private static LazyPlan write(DuckDbSession db, LazyPlan input, String config) throws Exception {
        NodeContext context = NodeTestSupport.context(db, config);
        return new DbWriterExecutor().execute(context, List.of(input));
    }

    private static long rowsIn(Path database, String table) throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ATTACH '" + database + "' AS \"check_db\" (READ_ONLY)");
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM \"check_db\"." + DuckDbSession.quote(table))) {
                result.next();
                return result.getLong(1);
            } finally {
                statement.execute("DETACH \"check_db\"");
            }
        }
    }

    @Test
    void createsATableInADuckDbFileAndPassesTheInputThrough() throws Exception {
        Path database = dir.resolve("out.duckdb");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan passed = write(db, input, config(database, "create"));

            assertEquals(input.sql(), passed.sql(), "the writer is a pass-through");
            assertEquals(2, rowsIn(database, "people"));
        }
    }

    @Test
    void replacesAnExistingTable() throws Exception {
        Path database = dir.resolve("out.duckdb");
        try (DuckDbSession db = new DuckDbSession()) {
            write(db, NodeTestSupport.table(db, COLUMNS, ROWS), config(database, "create"));
            write(db, NodeTestSupport.table(db, COLUMNS, List.<Object[]>of(new Object[]{9L, "zoe"})),
                    config(database, "replace"));

            assertEquals(1, rowsIn(database, "people"));
        }
    }

    @Test
    void appendsToAnExistingTable() throws Exception {
        Path database = dir.resolve("out.duckdb");
        try (DuckDbSession db = new DuckDbSession()) {
            write(db, NodeTestSupport.table(db, COLUMNS, ROWS), config(database, "create"));
            write(db, NodeTestSupport.table(db, COLUMNS, List.<Object[]>of(new Object[]{3L, "cid"})),
                    config(database, "append"));

            assertEquals(3, rowsIn(database, "people"));
        }
    }

    @Test
    void writesToSqliteWhenTheExtensionIsAvailable() throws Exception {
        Assumptions.assumeTrue(sqliteAvailable(), "the sqlite extension is not installed");
        Path database = dir.resolve("out.sqlite");
        String config = "{\"driver\":\"sqlite\",\"connection\":\"" + NodeTestSupport.path(database)
                + "\",\"table\":\"people\",\"mode\":\"create\"}";
        try (DuckDbSession db = new DuckDbSession()) {
            write(db, NodeTestSupport.table(db, COLUMNS, ROWS), config);
        }
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ATTACH '" + database + "' AS \"check_db\" (TYPE SQLITE, READ_ONLY)");
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM \"check_db\".people")) {
                result.next();
                assertEquals(2, result.getLong(1));
            }
        }
    }

    @Test
    void unknownDriverIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> write(db, input, "{\"driver\":\"oracle\",\"connection\":\"/tmp/x\","
                            + "\"table\":\"t\",\"mode\":\"create\"}"));
            for (String driver : List.of("duckdb", "sqlite", "postgres", "mysql")) {
                assertTrue(failure.getMessage().contains(driver), failure.getMessage());
            }
        }
    }

    @Test
    void unknownModeIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> write(db, input, config(dir.resolve("x.duckdb"), "upsert")));
            for (String mode : List.of("create", "replace", "append")) {
                assertTrue(failure.getMessage().contains(mode), failure.getMessage());
            }
        }
    }

    @Test
    void pathOutsideTheAllowlistIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            ApiException failure = assertThrows(ApiException.class,
                    () -> write(db, input, "{\"driver\":\"duckdb\",\"connection\":\"/etc/out.duckdb\","
                            + "\"table\":\"t\",\"mode\":\"create\"}"));
            assertEquals("path_not_allowed", failure.code(), failure.getMessage());
        }
    }

    @Test
    void rejectedTargetDoesNotCreateItsParentDirectory() throws Exception {
        Path parent = Path.of(System.getProperty("user.home"), ".ssh",
                "dap-dbwriter-" + UUID.randomUUID());
        Path database = parent.resolve("out.duckdb");
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            ApiException failure = assertThrows(ApiException.class,
                    () -> write(db, input, config(database, "create")));
            assertEquals("path_not_allowed", failure.code(), failure.getMessage());
            assertTrue(!Files.exists(parent), "a rejected target must not create directories: " + parent);
        } finally {
            Files.deleteIfExists(database);
            Files.deleteIfExists(parent);
        }
    }

    @Test
    void missingInputIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, config(dir.resolve("x.duckdb"), "create"));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new DbWriterExecutor().execute(context, List.of()));
            assertTrue(failure.getMessage().contains("input"), failure.getMessage());
        }
    }

    private static boolean sqliteAvailable() {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("INSTALL sqlite");
            statement.execute("LOAD sqlite");
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
