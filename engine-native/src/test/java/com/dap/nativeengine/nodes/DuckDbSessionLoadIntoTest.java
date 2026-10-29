package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;

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
}
