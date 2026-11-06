package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** dap.concatenate: stacks two tables by column name or by position. */
public final class ConcatenateExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.concatenate";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        if (inputs == null || inputs.size() < 2 || inputs.get(0) == null || inputs.get(1) == null) {
            throw new IllegalArgumentException("concatenate: both input ports must be connected");
        }
        LazyPlan left = inputs.get(0);
        LazyPlan right = inputs.get(1);
        JsonNode config = context.config();
        String mode = NodeSql.requiredText(config, "mode").toLowerCase(Locale.ROOT);
        String sql;
        switch (mode) {
            case "name" -> sql = "SELECT * FROM " + left.asTable()
                    + " UNION ALL BY NAME SELECT * FROM " + right.asTable();
            case "position" -> {
                if (left.columns().size() != right.columns().size()) {
                    throw new IllegalArgumentException("concatenate: position mode requires the same number of "
                            + "columns in both inputs (left " + left.columns().size()
                            + ", right " + right.columns().size() + ")");
                }
                sql = "SELECT * FROM " + left.asTable() + " UNION ALL SELECT * FROM " + right.asTable();
            }
            default -> throw new IllegalArgumentException("concatenate: unknown mode '" + mode
                    + "' (expected name or position)");
        }
        List<ColumnSpec> columns = describe(context, sql, mode);
        return new LazyPlan(context.db(), sql, columns);
    }

    private static List<ColumnSpec> describe(NodeContext context, String sql, String mode) {
        List<ColumnSpec> columns = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "DESCRIBE SELECT * FROM (" + sql + ") AS dap_concatenate")) {
            while (result.next()) {
                String name = result.getString("column_name");
                String duckDbType = result.getString("column_type");
                try {
                    columns.add(ColumnSpec.of(name, TypeMap.fromDuckDb(duckDbType)));
                } catch (IllegalArgumentException unsupported) {
                    throw new IllegalArgumentException("concatenate: column '" + name
                            + "' has unsupported DuckDB type '" + duckDbType + "'", unsupported);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalArgumentException("concatenate: " + mode
                    + " concatenation failed: " + failure.getMessage(), failure);
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("concatenate: inputs have no columns");
        }
        return columns;
    }
}
