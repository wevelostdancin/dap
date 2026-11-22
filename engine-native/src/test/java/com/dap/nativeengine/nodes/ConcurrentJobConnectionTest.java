package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nodes of one DAG level share a single job connection. Two sql_query/writer nodes that start
 * together must not open statements on it concurrently — repeated runs used to fail with
 * DuckDB's "connection already has an outstanding query" error.
 */
class ConcurrentJobConnectionTest {
    private static final int RUNS = 20;
    private static final int ROWS = 4_000;

    @TempDir
    Path dir;

    @Test
    void twentyParallelLevelsOfTwoSqlQueriesAndTwoWritersShareOneConnection() throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"));
        Path first = dir.resolve("a.csv");
        Path second = dir.resolve("b.csv");
        try (DuckDbSession db = new DuckDbSession();
             Connection job = db.newConnection()) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < ROWS; i++) {
                rows.add(new Object[]{(long) i, "name-" + i});
            }
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext sqlA = NodeTestSupport.contextWithInputs(db, job,
                    "{\"sql\":\"SELECT id, name FROM src WHERE id < 100\"}", "src");
            NodeContext sqlB = NodeTestSupport.contextWithInputs(db, job,
                    "{\"sql\":\"SELECT count(*) AS n FROM src\"}", "src");
            NodeContext writeA = NodeTestSupport.context(db, job, fileConfig(first));
            NodeContext writeB = NodeTestSupport.context(db, job, fileConfig(second));

            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                for (int run = 0; run < RUNS; run++) {
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<?>> level = new ArrayList<>();
                    level.add(pool.submit(() -> {
                        start.await();
                        new SqlQueryExecutor().execute(sqlA, List.of(input));
                        return null;
                    }));
                    level.add(pool.submit(() -> {
                        start.await();
                        new SqlQueryExecutor().execute(sqlB, List.of(input));
                        return null;
                    }));
                    level.add(pool.submit(() -> {
                        start.await();
                        new CsvWriterExecutor().execute(writeA, List.of(input));
                        return null;
                    }));
                    level.add(pool.submit(() -> {
                        start.await();
                        new CsvWriterExecutor().execute(writeB, List.of(input));
                        return null;
                    }));
                    start.countDown();
                    for (Future<?> future : level) {
                        future.get(30, TimeUnit.SECONDS);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(ROWS + 1, Files.readAllLines(first).size(), "writer A lost rows");
            assertEquals(ROWS + 1, Files.readAllLines(second).size(), "writer B lost rows");
        }
    }

    private static String fileConfig(Path file) {
        return "{\"file\":\"" + NodeTestSupport.path(file) + "\"}";
    }
}
