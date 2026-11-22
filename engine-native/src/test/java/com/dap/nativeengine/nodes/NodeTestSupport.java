package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/** Builders shared by the five node executor tests. */
final class NodeTestSupport {
    private NodeTestSupport() {
    }

    static NodeContext context(DuckDbSession db, String configJson) throws Exception {
        return context(db, null, configJson);
    }

    static NodeContext context(DuckDbSession db, Connection jobConnection, String configJson) throws Exception {
        ObjectNode config = (ObjectNode) Json.parse(configJson);
        return new NodeContext("wf", "node", "session", config, db, jobConnection);
    }

    /** Context with the source node id of each input port (for sql_query aliases). */
    static NodeContext contextWithInputs(DuckDbSession db, Connection jobConnection, String configJson,
                                         String... inputNodeIds) throws Exception {
        ObjectNode config = (ObjectNode) Json.parse(configJson);
        return new NodeContext("wf", "node", "session", config, db, jobConnection, List.of(inputNodeIds));
    }

    /** Load rows into the session and return a plan over the loaded table. */
    static LazyPlan table(DuckDbSession db, List<ColumnSpec> columns, List<Object[]> rows) throws Exception {
        try (ArrowTable table = ArrowTable.of(columns, rows)) {
            String name = db.load(table, "test");
            return new LazyPlan(db, "SELECT * FROM " + DuckDbSession.quote(name), columns);
        }
    }

    /** Create a large table with DuckDB SQL, without materialising rows in Java. */
    static LazyPlan sqlTable(DuckDbSession db, String name, String query,
                             List<ColumnSpec> columns) throws Exception {
        db.execute("CREATE TABLE " + DuckDbSession.quote(name) + " AS " + query);
        return new LazyPlan(db, "SELECT * FROM " + DuckDbSession.quote(name), columns);
    }

    /** JSON-escaped absolute path for hand-built config objects. */
    static String path(Path file) {
        return file.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
