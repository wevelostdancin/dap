package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParquetWriterExecutorTest {
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

    /** Read the written file back through DuckDB, the way a parquet_reader would. */
    private static long scalar(DuckDbSession db, String sql) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long parquetCount(DuckDbSession db, Path file) throws Exception {
        return scalar(db, "SELECT COUNT(*) FROM read_parquet('" + file + "')");
    }

    @Test
    void writesAReadableParquetFileAndPassesThePlanThrough() throws Exception {
        Path file = dir.resolve("out.parquet");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            LazyPlan plan = new ParquetWriterExecutor().execute(context, List.of(input));

            assertSame(input, plan);
            assertEquals(2, parquetCount(db, file));
            assertEquals(3, scalar(db, "SELECT SUM(id) FROM read_parquet('" + file + "')"));
        }
    }

    @Test
    void acceptsEverySupportedCompression() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            for (String compression : List.of("snappy", "zstd", "gzip", "uncompressed")) {
                Path file = dir.resolve(compression + ".parquet");
                String config = "{\"file\":\"" + NodeTestSupport.path(file)
                        + "\",\"compression\":\"" + compression + "\"}";
                NodeContext context = NodeTestSupport.context(db, job, config);

                new ParquetWriterExecutor().execute(context, List.of(people(db)));

                assertEquals(2, parquetCount(db, file), compression);
            }
        }
    }

    @Test
    void rejectsAnUnknownCompression() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            String config = "{\"file\":\"" + NodeTestSupport.path(dir.resolve("out.parquet"))
                    + "\",\"compression\":\"brotli\"}";
            NodeContext context = NodeTestSupport.context(db, job, config);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ParquetWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("compression"), failure.getMessage());
            assertTrue(failure.getMessage().contains("brotli"), failure.getMessage());
        }
    }

    @Test
    void overwritesAnExistingFile() throws Exception {
        Path file = dir.resolve("out.parquet");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));
            new ParquetWriterExecutor().execute(context, List.of(people(db)));
            assertEquals(2, parquetCount(db, file));

            LazyPlan single = NodeTestSupport.table(db, COLUMNS, List.<Object[]>of(new Object[]{7L, "solo"}));
            new ParquetWriterExecutor().execute(context, List.of(single));

            assertEquals(1, parquetCount(db, file));
            assertEquals(7, scalar(db, "SELECT id FROM read_parquet('" + file + "')"));
        }
    }

    @Test
    void emptyTableWritesAnEmptyParquetFile() throws Exception {
        Path file = dir.resolve("empty.parquet");
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of());
            NodeContext context = NodeTestSupport.context(db, job, fileConfig(file));

            new ParquetWriterExecutor().execute(context, List.of(input));

            assertEquals(0, parquetCount(db, file));
        }
    }

    @Test
    void rejectsMissingFileConfig() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, job, "{}");

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ParquetWriterExecutor().execute(context, List.of(people(db))));
            assertTrue(failure.getMessage().contains("file"), failure.getMessage());
        }
    }
}
