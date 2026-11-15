package com.dap.nativeengine.engine;

import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DuckDB resource settings applied on the session database (Phase 3 memory controls). */
class DuckDbSessionSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    void appliesConfiguredResourcesAndDuplicatesInheritThem() throws Exception {
        Path spill = tempDir.resolve("spill");
        try (DuckDbSession session = new DuckDbSession("1GB", 2, spill.toString())) {
            assertEquals(2, session.resources().threads());
            assertEquals(spill.toString(), session.resources().tempDirectory());
            assertTrue(session.resources().memoryLimit().endsWith("MiB"), session.resources().memoryLimit());
            assertTrue(Files.isDirectory(spill), "the temp directory must be created");

            try (DuckDBConnection duplicate = session.newConnection();
                 Statement statement = duplicate.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT current_setting('memory_limit'), current_setting('threads'), "
                                 + "current_setting('temp_directory')")) {
                assertTrue(result.next());
                assertEquals(session.resources().memoryLimit(), result.getString(1));
                assertEquals("2", result.getString(2));
                assertEquals(spill.toString(), result.getString(3));
            }
        }
    }

    @Test
    void defaultsLeaveDuckDbSettingsUntouched() throws Exception {
        try (DuckDBConnection plain = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:")) {
            try (DuckDbSession session = new DuckDbSession();
                 Statement statement = plain.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT current_setting('memory_limit'), current_setting('threads'), "
                                 + "current_setting('temp_directory')")) {
                assertTrue(result.next());
                assertEquals(result.getString(1), session.resources().memoryLimit());
                assertEquals(Integer.valueOf(result.getString(2).strip()), session.resources().threads());
                String temp = result.getString(3);
                assertEquals(temp == null || temp.isEmpty() ? null : temp, session.resources().tempDirectory());
            }
        }
    }

    @Test
    void preserveInsertionOrderIsOffByDefault() throws Exception {
        try (DuckDbSession session = new DuckDbSession();
             DuckDBConnection connection = session.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT current_setting('preserve_insertion_order')")) {
            assertTrue(result.next());
            assertEquals("false", result.getString(1));
        }
    }

    @Test
    void invalidMemoryLimitFailsLoudly() {
        SQLException notAMemoryLimit = assertThrows(SQLException.class,
                () -> new DuckDbSession("banana", null, null));
        assertTrue(notAMemoryLimit.getMessage().contains("memory limit"), notAMemoryLimit.getMessage());
        assertTrue(notAMemoryLimit.getMessage().contains("banana"), notAMemoryLimit.getMessage());

        SQLException zero = assertThrows(SQLException.class,
                () -> new DuckDbSession("0GB", null, null));
        assertTrue(zero.getMessage().contains("memory limit"), zero.getMessage());
    }

    @Test
    void nonPositiveThreadsRejected() {
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> new DuckDbSession(null, 0, null));
        assertTrue(zero.getMessage().contains("threads"), zero.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new DuckDbSession(null, -1, null));
    }

    @Test
    void uncreatableTempDirectoryFailsLoudly() throws Exception {
        Path file = Files.writeString(tempDir.resolve("not-a-directory"), "x");
        SQLException failure = assertThrows(SQLException.class,
                () -> new DuckDbSession(null, null, file.resolve("spill").toString()));
        assertTrue(failure.getMessage().contains("temp directory"), failure.getMessage());
    }

    @Test
    void smallMemoryLimitSpillsToTempDirectoryWithoutFailing() throws Exception {
        Path spill = tempDir.resolve("spill");
        try (DuckDbSession session = new DuckDbSession("64MB", 2, spill.toString())) {
            AtomicBoolean sawTempFiles = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread sort = new Thread(() -> {
                try (DuckDBConnection connection = session.newConnection();
                     Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery(
                             "SELECT COUNT(*) FROM (SELECT i FROM range(20000000) t(i) ORDER BY i DESC)")) {
                    assertTrue(result.next());
                    assertEquals(20_000_000L, result.getLong(1));
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            }, "dap-spill-test");
            sort.start();
            while (sort.isAlive() && !sawTempFiles.get()) {
                try (Stream<Path> files = Files.walk(spill)) {
                    if (files.anyMatch(Files::isRegularFile)) {
                        sawTempFiles.set(true);
                    }
                } catch (Exception ignored) {
                    // the directory may not exist for the first few milliseconds
                }
                Thread.sleep(2);
            }
            sort.join(Duration.ofSeconds(60).toMillis());
            assertFalse(sort.isAlive(), "the spill query must finish");
            assertNull(failure.get(), () -> "spill query failed: " + failure.get());
            assertTrue(sawTempFiles.get(), "DuckDB must spill to the configured temp directory");
        }
    }
}
