package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParquetReaderExecutorTest {
    @TempDir
    Path dir;

    private Path parquet(DuckDbSession db, String name, String select) throws Exception {
        Path file = dir.resolve(name);
        try (Connection connection = db.newConnection(); Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + select + ") TO '" + file + "' (FORMAT PARQUET)");
        }
        return file;
    }

    private static String fileConfig(Path file) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"}";
    }

    @Test
    void readsColumnsAndRowsFromParquet() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            Path file = parquet(db, "people.parquet",
                    "SELECT 1::BIGINT AS id, 'ann' AS name, 2.5::DOUBLE AS score "
                            + "UNION ALL SELECT 2, 'bob', 3.0");
            NodeContext context = NodeTestSupport.context(db, fileConfig(file));
            LazyPlan plan = new ParquetReaderExecutor().execute(context, List.of());

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"),
                    ColumnSpec.of("score", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            assertEquals(List.of("1", "ann", "2.5"), data.data().get(0));
            assertEquals(List.of("2", "bob", "3.0"), data.data().get(1));
        }
    }

    @Test
    void mapsParquetTypesToArrowTypes() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            Path file = parquet(db, "typed.parquet",
                    "SELECT 1::BIGINT AS id, true AS flag, DATE '2024-01-02' AS day, "
                            + "TIMESTAMP '2024-01-02 03:04:05' AS ts");
            NodeContext context = NodeTestSupport.context(db, fileConfig(file));
            LazyPlan plan = new ParquetReaderExecutor().execute(context, List.of());

            assertEquals(List.of(
                    ColumnSpec.of("id", "Int64"),
                    ColumnSpec.of("flag", "Boolean"),
                    ColumnSpec.of("day", "Date32"),
                    ColumnSpec.of("ts", "Timestamp")), plan.columns());
            assertEquals(List.of("1", "true", "2024-01-02", "2024-01-02T03:04:05"),
                    plan.preview(10).data().get(0));
        }
    }

    @Test
    void emptyParquetStillYieldsAColumnSchema() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            Path file = parquet(db, "empty.parquet", "SELECT 1::BIGINT AS id WHERE false");
            NodeContext context = NodeTestSupport.context(db, fileConfig(file));
            LazyPlan plan = new ParquetReaderExecutor().execute(context, List.of());

            assertEquals(List.of(ColumnSpec.of("id", "Int64")), plan.columns());
            assertEquals(0, plan.preview(10).rows());
        }
    }

    @Test
    void missingFileIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            Path absent = dir.resolve("absent.parquet");
            NodeContext context = NodeTestSupport.context(db, fileConfig(absent));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ParquetReaderExecutor().execute(context, List.of()));
            assertTrue(failure.getMessage().contains("absent.parquet"), failure.getMessage());
        }
    }

    @Test
    void blankFileConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            NodeContext context = NodeTestSupport.context(db, "{\"file\":\"  \"}");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new ParquetReaderExecutor().execute(context, List.of()));
            assertTrue(failure.getMessage().contains("file"), failure.getMessage());
        }
    }
}
