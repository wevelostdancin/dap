package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DuckDbSession.loadInto: connection type guard for the job-connection ingest path. */
class DuckDbSessionLoadIntoTest {
    @Test
    void nonDuckDbConnectionIsRejectedWithClearError() throws Exception {
        Connection foreign = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (instance, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        try (DuckDbSession db = new DuckDbSession();
             ArrowTable table = ArrowTable.of(List.of(ColumnSpec.of("id", "Int64")),
                     List.<Object[]>of(new Object[]{1L}))) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> db.loadInto(foreign, table, "foreign_table"));
            assertTrue(failure.getMessage().contains("DuckDBConnection"), failure.getMessage());
        }
    }

    @Test
    void concurrentLoadIntoOnOneSharedConnectionIsSerialized() throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("value", "Float64"));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            rows.add(new Object[]{(long) i, i / 10.0});
        }
        try (DuckDbSession db = new DuckDbSession();
             ArrowTable table = ArrowTable.of(columns, rows);
             Connection connection = db.newConnection()) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<String> first = pool.submit(() -> {
                    start.await();
                    return db.loadInto(connection, table, "parallel_a");
                });
                Future<String> second = pool.submit(() -> {
                    start.await();
                    return db.loadInto(connection, table, "parallel_b");
                });
                start.countDown();
                assertEquals("parallel_a", first.get(30, TimeUnit.SECONDS));
                assertEquals("parallel_b", second.get(30, TimeUnit.SECONDS));
                assertEquals(20_000, new LazyPlan(db, "SELECT * FROM parallel_a", columns).count());
                assertEquals(20_000, new LazyPlan(db, "SELECT * FROM parallel_b", columns).count());
            } finally {
                pool.shutdownNow();
            }
        }
    }
}