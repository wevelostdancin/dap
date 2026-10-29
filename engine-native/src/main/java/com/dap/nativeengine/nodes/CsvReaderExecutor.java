package com.dap.nativeengine.nodes;

import com.dap.nativeengine.csv.CsvTableReader;
import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** dap.csv_reader: parse a CSV file into Arrow, load it into the session database. */
public final class CsvReaderExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.csv_reader";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        JsonNode config = context.config();
        String file = NodeSql.requiredText(config, "file");
        CsvTableReader.Options options = options(config);

        ArrowTable table = CsvTableReader.read(Path.of(file), options);
        try {
            String name = context.db().load(table, "csv_" + context.workflowId() + "_" + context.nodeId());
            return new LazyPlan(context.db(), "SELECT * FROM " + DuckDbSession.quote(name), table.columns());
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("csv_reader: cannot load parsed CSV into DuckDB: " + failure.getMessage(), failure);
        } finally {
            table.close();
        }
    }

    private static CsvTableReader.Options options(JsonNode config) {
        String delimiter = NodeSql.text(config, "delimiter", ",");
        if ("\\t".equals(delimiter)) {
            delimiter = "\t";
        }
        if (delimiter.isEmpty()) {
            throw new IllegalArgumentException("csv_reader: delimiter must not be empty");
        }
        if (delimiter.length() != 1) {
            throw new IllegalArgumentException("csv_reader: delimiter must be exactly one character, got '" + delimiter + "'");
        }
        if (delimiter.charAt(0) == '"' || delimiter.charAt(0) == '\r' || delimiter.charAt(0) == '\n') {
            throw new IllegalArgumentException("csv_reader: invalid delimiter '" + delimiter + "'");
        }
        boolean header = NodeSql.bool(config, "header", true);
        boolean emptyStringIsNull = NodeSql.bool(config, "emptyStringIsNull", false);

        List<ColumnSpec> columns = new ArrayList<>();
        JsonNode columnsNode = config.get("columns");
        if (columnsNode != null && !columnsNode.isNull()) {
            if (!columnsNode.isArray()) {
                throw new IllegalArgumentException("csv_reader: columns must be an array of {name,type}");
            }
            for (JsonNode column : columnsNode) {
                String name = NodeSql.requiredText(column, "name");
                String type = NodeSql.text(column, "type", "Utf8");
                if (!TypeMap.isSupported(type)) {
                    throw new IllegalArgumentException("csv_reader: unsupported type '" + type
                            + "' for column '" + name + "'");
                }
                columns.add(ColumnSpec.of(name, type));
            }
        }
        return new CsvTableReader.Options(delimiter.charAt(0), header, columns, emptyStringIsNull);
    }
}
