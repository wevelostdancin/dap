package com.dap.nativeengine.io;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.DuckDbSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestServiceTest {
    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private IngestService service() {
        return new IngestService(dir.resolve("cache"));
    }

    private static String literal(Path file) {
        return "'" + file.toString().replace("'", "''") + "'";
    }

    @Test
    void csvBecomesParquetWithRowsAndColumns() throws Exception {
        Path source = write("people.csv", "name,age,score\nalice,34,9.5\nbob,28,7.0\n");

        IngestService.Result result = service().ingest(source, "csv");

        assertTrue(Files.isRegularFile(result.path()), "cached Parquet file is missing");
        assertTrue(result.path().getFileName().toString().endsWith(".parquet"));
        assertFalse(result.cached());
        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("name", "Utf8"),
                new IngestService.Column("age", "Int64"),
                new IngestService.Column("score", "Float64")), result.columns());

        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name, age, score FROM read_parquet("
                     + literal(result.path()) + ") ORDER BY name")) {
            assertTrue(rows.next());
            assertEquals("alice", rows.getString(1));
            assertEquals(34L, rows.getLong(2));
            assertEquals(9.5, rows.getDouble(3));
            assertTrue(rows.next());
            assertEquals("bob", rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test
    void secondCallReturnsTheSameFileWithoutRewritingIt() throws Exception {
        Path source = write("people.csv", "id\n1\n2\n");
        IngestService service = service();
        IngestService.Result first = service.ingest(source, "csv");
        FileTime marker = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(first.path(), marker);

        IngestService.Result second = service.ingest(source, "csv");

        assertTrue(second.cached());
        assertEquals(first.path(), second.path());
        assertEquals(marker, Files.getLastModifiedTime(second.path()), "cache entry was rewritten");
        assertEquals(first.rows(), second.rows());
        assertEquals(first.columns(), second.columns());
    }

    @Test
    void jsonBecomesParquet() throws Exception {
        Path source = write("people.json", "[{\"id\":1,\"name\":\"alice\"},{\"id\":2,\"name\":\"bob\"}]");

        IngestService.Result result = service().ingest(source, "json");

        assertTrue(Files.isRegularFile(result.path()));
        assertFalse(result.cached());
        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("id", "Int64"),
                new IngestService.Column("name", "Utf8")), result.columns());
    }

    @Test
    void parquetBecomesParquet() throws Exception {
        Path source = dir.resolve("source.parquet");
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("COPY (SELECT 1 AS id, 'x' AS label UNION ALL SELECT 2, 'y') TO "
                    + literal(source) + " (FORMAT PARQUET)");
        }

        IngestService.Result result = service().ingest(source, "parquet");

        assertTrue(Files.isRegularFile(result.path()));
        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("id", "Int64"),
                new IngestService.Column("label", "Utf8")), result.columns());
    }

    @Test
    void unknownFormatIsRejected() throws Exception {
        Path source = write("data.csv", "id\n1\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().ingest(source, "xml"));

        assertTrue(failure.getMessage().contains("xml"), failure.getMessage());
    }

    private static List<List<String>> rows(Path parquet) throws Exception {
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM read_parquet("
                     + literal(parquet) + ") ORDER BY 1")) {
            List<List<String>> rows = new java.util.ArrayList<>();
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<String> row = new java.util.ArrayList<>(columns);
                for (int column = 1; column <= columns; column++) {
                    row.add(DuckDbSession.format(result.getObject(column)));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    @Test
    void streamingCsvPathProducesTheSameTableAsTheArrowPath() throws Exception {
        Path source = write("stream.csv", "name,age\nann,34\nbob,28\n");

        IngestService.Result arrow = new IngestService(dir.resolve("cache-arrow")).ingest(source, "csv");
        IngestService.Result streamed = new IngestService(dir.resolve("cache-stream"), 0)
                .ingest(source, "csv");

        assertFalse(arrow.cached());
        assertFalse(streamed.cached());
        assertEquals(2, streamed.rows());
        assertEquals(arrow.columns(), streamed.columns());
        assertEquals(rows(arrow.path()), rows(streamed.path()));
    }

    @Test
    void streamingThresholdIsMeasuredInMegabytes() {
        assertFalse(IngestService.shouldStream(64L * 1024 * 1024, 64));
        assertTrue(IngestService.shouldStream(64L * 1024 * 1024 + 1, 64));
        assertTrue(IngestService.shouldStream(1, 0));
        assertFalse(IngestService.shouldStream(0, 0));
    }

    @Test
    void sourceOutsideTheAllowlistIsRejected() {
        ApiException failure = assertThrows(ApiException.class,
                () -> service().ingest(Path.of("/etc/passwd"), "csv"));

        assertEquals(400, failure.status(), failure.getMessage());
        assertEquals("path_not_allowed", failure.code(), failure.getMessage());
    }

    @Test
    void missingFileIsRejected() {
        Path absent = dir.resolve("absent.csv");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service().ingest(absent, "csv"));

        assertTrue(failure.getMessage().contains("absent.csv"), failure.getMessage());
    }
}
