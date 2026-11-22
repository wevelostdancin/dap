package com.dap.nativeengine.io;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.DuckDbSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void quotedAndUnquotedEmptyFieldsBecomeNulls() throws Exception {
        Path source = write("empty.csv", "txt,num\n,1\n\"\",2\n\"x\",3\n");

        IngestService.Result result = service().ingest(source, "csv");

        assertEquals(List.of(
                new IngestService.Column("txt", "Utf8"),
                new IngestService.Column("num", "Int64")), result.columns());
        assertEquals(List.of(
                List.of("x", "3"),
                java.util.Arrays.asList(null, "1"),
                java.util.Arrays.asList(null, "2")), rows(result.path()));
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

    @Test
    void headerlessCsvKeepsEveryRowWithHeaderFalse() throws Exception {
        Path source = write("noheader.csv", "1,alpha\n2,beta\n");
        IngestService.CsvOptions options = new IngestService.CsvOptions(
                false, ",", "\"", "\"", false, false, true);

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(2, result.rows(), "a headerless file must not lose its first row");
        assertEquals(2, result.columns().size());
    }

    @Test
    void malformedRowsCanBeSkippedWithIgnoreErrors() throws Exception {
        Path source = write("ragged.csv", "a,b\n1,2\n3,4,5\n6,7\n");
        IngestService.CsvOptions options = new IngestService.CsvOptions(
                true, ",", "\"", "\"", true, false, true);

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(2, result.rows());
        assertEquals(List.of(
                new IngestService.Column("a", "Int64"),
                new IngestService.Column("b", "Int64")), result.columns());
    }

    @Test
    void nullStringsBecomeNulls() throws Exception {
        Path source = write("nulls.csv", "id,value\n1,NA\n2,5\n");
        IngestService.CsvOptions options = new IngestService.CsvOptions(
                true, ",", "\"", "\"", false, false, true, List.of("NA"));

        IngestService.Result result = service().ingest(source, "csv", options);

        assertEquals(2, result.rows());
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT value FROM read_parquet("
                     + literal(result.path()) + ") ORDER BY id")) {
            assertTrue(rows.next());
            assertEquals(null, rows.getObject(1));
            assertTrue(rows.next());
            assertEquals(5, rows.getInt(1));
        }
    }

    @Test
    void sameSizeAndMtimeWithDifferentContentProducesNewParquet() throws Exception {
        Path source = write("same.csv", "id\n1\n2\n");
        IngestService service = service();
        IngestService.Result first = service.ingest(source, "csv");
        long size = Files.size(source);
        FileTime mtime = Files.getLastModifiedTime(source);

        Files.writeString(source, "id\n3\n4\n", StandardCharsets.UTF_8);
        assertEquals(size, Files.size(source));
        Files.setLastModifiedTime(source, mtime);

        IngestService.Result second = service.ingest(source, "csv");

        assertNotEquals(first.path(), second.path(), "rewritten content must not reuse the old entry");
        assertFalse(second.cached());
        assertEquals(List.of(List.of("3"), List.of("4")), rows(second.path()));
        assertEquals(List.of(List.of("1"), List.of("2")), rows(first.path()));
    }

    @Test
    void changeInTailOfLargeFileInvalidatesCache() throws Exception {
        StringBuilder content = new StringBuilder("id,name\n");
        for (int row = 0; row < 10_000; row++) {
            content.append(row % 10).append(",value\n");
        }
        Path source = write("large.csv", content.toString());
        assertTrue(Files.size(source) > 64 * 1024);
        IngestService service = service();
        IngestService.Result first = service.ingest(source, "csv");
        long size = Files.size(source);
        FileTime mtime = Files.getLastModifiedTime(source);

        String changed = content.substring(0, content.length() - 8) + "0,value\n";
        Files.writeString(source, changed, StandardCharsets.UTF_8);
        assertEquals(size, Files.size(source));
        Files.setLastModifiedTime(source, mtime);

        IngestService.Result second = service.ingest(source, "csv");

        assertNotEquals(first.path(), second.path(), "tail bytes must be part of the fingerprint");
        assertFalse(second.cached());
        assertEquals(10_000, second.rows());
    }

    @Test
    void truncatedCachedParquetIsRegenerated() throws Exception {
        Path source = write("truncate.csv", "id\n7\n8\n");
        IngestService service = service();
        IngestService.Result first = service.ingest(source, "csv");
        try (FileChannel channel = FileChannel.open(first.path(), StandardOpenOption.WRITE)) {
            channel.truncate(100);
        }

        IngestService.Result recovered = service.ingest(source, "csv");

        assertFalse(recovered.cached(), "corrupt cache entry must be rebuilt");
        assertEquals(first.path(), recovered.path());
        assertEquals(List.of(List.of("7"), List.of("8")), rows(recovered.path()));
    }

    @Test
    void parallelIngestSharesOneCacheEntry() throws Exception {
        Path source = write("parallel.csv", "id\n1\n2\n");
        IngestService service = service();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<IngestService.Result>> futures = new ArrayList<>();
            for (int attempt = 0; attempt < 4; attempt++) {
                futures.add(pool.submit(() -> service.ingest(source, "csv")));
            }
            List<IngestService.Result> results = new ArrayList<>();
            for (Future<IngestService.Result> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertEquals(1, results.stream().map(IngestService.Result::path).distinct().count());
            assertEquals(1, results.stream().filter(result -> !result.cached()).count(),
                    "exactly one call writes the cache entry");
            try (Stream<Path> cached = Files.list(dir.resolve("cache"))) {
                assertEquals(1, cached.filter(path -> path.getFileName().toString().endsWith(".parquet"))
                        .count(), "one Parquet file per key");
            }
            assertEquals(List.of(List.of("1"), List.of("2")), rows(results.get(0).path()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unchangedFileIsServedFromCacheWithSamePath() throws Exception {
        Path source = write("stable.csv", "id\n1\n2\n");
        IngestService service = service();

        IngestService.Result first = service.ingest(source, "csv");
        IngestService.Result second = service.ingest(source, "csv");

        assertTrue(second.cached());
        assertEquals(first.path(), second.path());
        assertEquals(first.rows(), second.rows());
    }

    @Test
    void csvOptionsArePartOfTheCacheKey() throws Exception {
        Path source = write("people.csv", "1,alpha\n2,beta\n");
        IngestService service = service();

        IngestService.Result withHeader = service.ingest(source, "csv");
        IngestService.Result withoutHeader = service.ingest(source, "csv",
                new IngestService.CsvOptions(false, ",", "\"", "\"", false, false, true));

        assertFalse(withHeader.path().equals(withoutHeader.path()),
                "different CSV options must not share a cache entry");
    }
}
