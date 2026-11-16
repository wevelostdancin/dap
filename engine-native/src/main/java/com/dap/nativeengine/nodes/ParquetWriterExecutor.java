package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** dap.parquet_writer: writes the input table to a Parquet file and passes it through. */
public final class ParquetWriterExecutor implements NodeExecutor {
    private static final String NODE = "parquet_writer";
    private static final Set<String> COMPRESSIONS = Set.of("snappy", "zstd", "gzip", "uncompressed");

    @Override
    public String nodeId() {
        return "dap.parquet_writer";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        String compression = NodeSql.text(config, "compression", "zstd").toLowerCase(Locale.ROOT);
        if (!COMPRESSIONS.contains(compression)) {
            throw new IllegalArgumentException("parquet_writer: unsupported compression '" + compression
                    + "' (expected snappy, zstd, gzip or uncompressed)");
        }

        Path target = IoNodeSupport.targetFile(file, NODE);
        IoNodeSupport.copy(context, input, target, NODE, "FORMAT PARQUET, COMPRESSION " + compression);
        return input;
    }
}
