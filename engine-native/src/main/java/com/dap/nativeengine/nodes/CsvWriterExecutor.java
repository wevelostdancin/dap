package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

/** dap.csv_writer: writes the input table to a delimited text file and passes it through. */
public final class CsvWriterExecutor implements NodeExecutor {
    private static final String NODE = "csv_writer";

    @Override
    public String nodeId() {
        return "dap.csv_writer";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        String delimiter = NodeSql.text(config, "delimiter", ",");
        if ("\\t".equals(delimiter)) {
            delimiter = "\t";
        }
        if (delimiter.isEmpty()) {
            throw new IllegalArgumentException("csv_writer: delimiter must not be empty");
        }
        if (delimiter.length() != 1) {
            throw new IllegalArgumentException("csv_writer: delimiter must be exactly one character, got '"
                    + delimiter + "'");
        }
        if (delimiter.charAt(0) == '"' || delimiter.charAt(0) == '\r' || delimiter.charAt(0) == '\n') {
            throw new IllegalArgumentException("csv_writer: invalid delimiter '" + delimiter + "'");
        }
        boolean header = NodeSql.bool(config, "header", true);

        Path target = IoNodeSupport.targetFile(file, NODE);
        IoNodeSupport.copy(context, input, target, NODE,
                "FORMAT CSV, HEADER " + header + ", DELIMITER " + NodeSql.literal(delimiter));
        return input;
    }
}
