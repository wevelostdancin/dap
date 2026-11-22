package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.xlsx.XlsxTableReader;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** dap.excel_reader: streams one worksheet of an .xlsx workbook into DuckDB. */
public final class ExcelReaderExecutor implements NodeExecutor {
    private static final String NODE = "excel_reader";
    private static final Set<String> SUPPORTED =
            Set.of("Int64", "Float64", "Utf8", "Boolean", "Date32", "Timestamp");

    @Override
    public String nodeId() {
        return "dap.excel_reader";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws InterruptedException {
        JsonNode config = context.config();
        // PathGuard + existence check, exactly like the other file readers.
        Path file = IoNodeSupport.sourceFile(config, NODE);
        XlsxTableReader.Options options = options(config);

        String name = tableName(context);
        try {
            XlsxTableReader.LoadResult loaded =
                    XlsxTableReader.streamInto(context.jobConnection(), name, file, options);
            return new LazyPlan(context.db(), "SELECT * FROM " + DuckDbSession.quote(name), loaded.columns());
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("excel_reader: cannot load workbook into DuckDB: "
                    + failure.getMessage(), failure);
        }
    }

    /** Stable per-node table: a re-run replaces it instead of leaking a copy. */
    private static String tableName(NodeContext context) {
        return "excel_" + sanitize(context.workflowId()) + "_" + sanitize(context.nodeId());
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "default";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static XlsxTableReader.Options options(JsonNode config) {
        String sheet = null;
        JsonNode sheetNode = config.get("sheet");
        if (sheetNode != null && !sheetNode.isNull()) {
            if (sheetNode.isTextual()) {
                if (!sheetNode.textValue().isBlank()) {
                    sheet = sheetNode.textValue().trim();
                }
            } else {
                // configSchema declares a string: a zero-based index is passed as "0", not 0.
                throw new IllegalArgumentException(
                        "excel_reader: config field 'sheet' must be a string (name or zero-based index)");
            }
        }
        boolean header = NodeSql.bool(config, "header", true);
        // Empty cells are NULL by default, same as csv_reader; false keeps the
        // legacy empty-string value. Cell errors are loud unless explicitly relaxed.
        boolean emptyStringIsNull = NodeSql.bool(config, "emptyStringIsNull", true);
        boolean errorsAsNull = NodeSql.bool(config, "errorsAsNull", false);

        List<ColumnSpec> columns = new ArrayList<>();
        JsonNode columnsNode = config.get("columns");
        if (columnsNode != null && !columnsNode.isNull()) {
            if (!columnsNode.isArray()) {
                throw new IllegalArgumentException("excel_reader: columns must be an array of {name,type}");
            }
            for (JsonNode column : columnsNode) {
                String name = NodeSql.requiredText(column, "name");
                String type = TypeMap.canonical(NodeSql.text(column, "type", "Utf8"));
                if (!SUPPORTED.contains(type)) {
                    throw new IllegalArgumentException("excel_reader: unsupported type '" + type
                            + "' for column '" + name + "'");
                }
                columns.add(ColumnSpec.of(name, type));
            }
        }
        return new XlsxTableReader.Options(sheet, header, columns, emptyStringIsNull, errorsAsNull);
    }
}
