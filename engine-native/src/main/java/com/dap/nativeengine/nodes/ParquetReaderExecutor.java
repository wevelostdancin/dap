package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;

import java.nio.file.Path;
import java.util.List;

/** dap.parquet_reader: reads a Parquet file as a lazy SQL relation. */
public final class ParquetReaderExecutor implements NodeExecutor {
    private static final String NODE = "parquet_reader";

    @Override
    public String nodeId() {
        return "dap.parquet_reader";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        Path file = IoNodeSupport.sourceFile(context.config(), NODE);
        String sql = "SELECT * FROM read_parquet(" + NodeSql.literal(file.toString()) + ")";
        List<ColumnSpec> columns = IoNodeSupport.describe(context, sql, NODE);
        return new LazyPlan(context.db(), sql, columns);
    }
}
