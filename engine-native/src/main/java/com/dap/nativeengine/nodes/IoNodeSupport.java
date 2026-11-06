package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.exec.NodeContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Shared plumbing for the /IO nodes: file validation, DESCRIBE typing and COPY. */
final class IoNodeSupport {
    private IoNodeSupport() {
    }

    /** Config field 'file' as written by the user. */
    static String requiredFile(JsonNode config) {
        return NodeSql.requiredText(config, "file");
    }

    /** An existing regular file to read, as an absolute path. */
    static Path sourceFile(JsonNode config, String node) {
        Path file = Path.of(requiredFile(config)).toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(node + ": file not found: " + file);
        }
        return file;
    }

    /** An absolute target path whose parent directories exist. */
    static Path targetFile(String file, String node) {
        Path target = Path.of(file).toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException failure) {
                throw new IllegalStateException(node + ": cannot create directory " + parent + ": "
                        + failure.getMessage(), failure);
            }
        }
        return target;
    }

    /**
     * Column names and Arrow types from DuckDB metadata. DESCRIBE binds the file reader on a
     * separate connection, so the executor never materializes rows.
     */
    static List<ColumnSpec> describe(NodeContext context, String sql, String node) {
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("DESCRIBE " + sql)) {
            List<ColumnSpec> columns = new ArrayList<>();
            while (result.next()) {
                String name = result.getString("column_name");
                String duckDbType = result.getString("column_type");
                try {
                    columns.add(ColumnSpec.of(name, TypeMap.fromDuckDb(duckDbType)));
                } catch (IllegalArgumentException unsupported) {
                    throw new IllegalArgumentException(node + ": column '" + name
                            + "' has unsupported DuckDB type '" + duckDbType + "'", unsupported);
                }
            }
            return columns;
        } catch (SQLException failure) {
            throw new IllegalArgumentException(node + ": cannot read file: " + failure.getMessage(), failure);
        }
    }

    /** COPY the input plan to {@code target}; callers return the plan unchanged. */
    static void copy(NodeContext context, LazyPlan input, Path target, String node, String options) {
        Connection connection = context.jobConnection();
        if (connection == null) {
            throw new IllegalStateException(node + ": no job connection");
        }
        String sql = "COPY (SELECT * FROM " + input.asTable() + ") TO "
                + NodeSql.literal(target.toString()) + " (" + options + ")";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(node + ": cannot write " + target + ": "
                    + failure.getMessage(), failure);
        }
    }
}
