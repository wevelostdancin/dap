package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

/** dap.json_writer: writes the input table as a JSON array or NDJSON and passes it through. */
public final class JsonWriterExecutor implements NodeExecutor {
    private static final String NODE = "json_writer";

    @Override
    public String nodeId() {
        return "dap.json_writer";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        boolean array = NodeSql.bool(config, "array", true);

        Path target = IoNodeSupport.targetFile(file, NODE);
        IoNodeSupport.copy(context, input, target, NODE, "FORMAT JSON, ARRAY " + array);
        return input;
    }
}
