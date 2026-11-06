package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;

import java.nio.file.Path;
import java.util.List;

/**
 * dap.json_reader: reads a JSON document as a lazy SQL relation. read_json_auto accepts both
 * a top-level JSON array and newline-delimited JSON (NDJSON).
 */
public final class JsonReaderExecutor implements NodeExecutor {
    private static final String NODE = "json_reader";

    @Override
    public String nodeId() {
        return "dap.json_reader";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        Path file = IoNodeSupport.sourceFile(context.config(), NODE);
        String sql = "SELECT * FROM read_json_auto(" + NodeSql.literal(file.toString()) + ")";
        List<ColumnSpec> columns = IoNodeSupport.describe(context, sql, NODE);
        return new LazyPlan(context.db(), sql, columns);
    }
}
