package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.io.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.db_writer: writes the input table into a DuckDB file, SQLite file or a Postgres/MySQL
 * database, then passes the input through unchanged so the node can sit in the middle of a
 * workflow.
 *
 * <p>The target is attached as a DuckDB database ({@code ATTACH ... (TYPE ...)}), the table is
 * created, replaced or appended to with plain SQL, and the attachment is detached again.
 * Local files go through {@link PathGuard}; remote DSNs may carry credentials, which are
 * masked in every error message.
 */
public final class DbWriterExecutor implements NodeExecutor {
    private static final String NODE = "db_writer";
    private static final Set<String> DRIVERS = Set.of("duckdb", "sqlite", "postgres", "mysql");
    private static final Set<String> MODES = Set.of("create", "replace", "append");
    private static final Map<String, String> ATTACH_TYPES = Map.of(
            "duckdb", "DUCKDB", "sqlite", "SQLITE", "postgres", "POSTGRES", "mysql", "MYSQL");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "sqlite", "sqlite_scanner", "postgres", "postgres_scanner", "mysql", "mysql_scanner");

    @Override
    public String nodeId() {
        return "dap.db_writer";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws SQLException {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String driver = driver(config);
        String mode = mode(config);
        String table = table(config);
        String connection = connection(config, driver);
        String alias = "dap_out_" + sanitize(context.workflowId()) + "_" + sanitize(context.nodeId());

        try (Connection target = context.db().newConnection()) {
            attach(target, driver, connection, alias);
            try {
                write(target, alias, table, mode, input);
            } finally {
                detach(target, alias);
            }
        } catch (SQLException failure) {
            throw translate(driver, failure, config);
        }
        return input;
    }

    private static String driver(JsonNode config) {
        String driver = NodeSql.requiredText(config, "driver").toLowerCase(Locale.ROOT);
        if (!DRIVERS.contains(driver)) {
            throw new IllegalArgumentException("db_writer: 'driver' must be one of "
                    + new java.util.TreeSet<>(DRIVERS) + ", got '" + driver + "'");
        }
        return driver;
    }

    private static String mode(JsonNode config) {
        String mode = NodeSql.text(config, "mode", "create").toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("db_writer: 'mode' must be one of "
                    + new java.util.TreeSet<>(MODES) + ", got '" + mode + "'");
        }
        return mode;
    }

    private static String table(JsonNode config) {
        String table = NodeSql.requiredText(config, "table");
        StringJoiner quoted = new StringJoiner(".");
        for (String part : table.split("\\.")) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("db_writer: invalid table name '" + table + "'");
            }
            quoted.add(DuckDbSession.quote(part.strip()));
        }
        return quoted.toString();
    }

    /** The local file path (guarded) or the remote DSN of the target database. */
    private static String connection(JsonNode config, String driver) {
        String connection = NodeSql.requiredText(config, "connection");
        if ("duckdb".equals(driver) || "sqlite".equals(driver)) {
            Path target = Path.of(connection).toAbsolutePath();
            Path checked = PathGuard.shared().checkWrite(target.toString());
            Path parent = target.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException failure) {
                    throw new IllegalStateException("db_writer: cannot create directory " + parent
                            + ": " + failure.getMessage(), failure);
                }
            }
            return checked.toString();
        }
        return connection;
    }

    private static void attach(Connection connection, String driver, String source, String alias)
            throws SQLException {
        execute(connection, "ATTACH IF NOT EXISTS " + NodeSql.literal(source) + " AS "
                + DuckDbSession.quote(alias) + " (TYPE " + ATTACH_TYPES.get(driver) + ")");
    }

    private static void write(Connection connection, String alias, String table, String mode,
                              LazyPlan input) throws SQLException {
        String target = DuckDbSession.quote(alias) + "." + table;
        String select = "SELECT * FROM " + input.asTable();
        String sql = switch (mode) {
            case "replace" -> "CREATE OR REPLACE TABLE " + target + " AS " + select;
            case "append" -> "INSERT INTO " + target + " " + select;
            default -> "CREATE TABLE " + target + " AS " + select;
        };
        execute(connection, sql);
    }

    private static void detach(Connection connection, String alias) {
        try {
            execute(connection, "DETACH " + DuckDbSession.quote(alias));
        } catch (SQLException ignored) {
            // best effort: the connection close releases whatever is left attached
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private ApiException translate(String driver, SQLException failure, JsonNode config) {
        String message = maskSecrets(String.valueOf(failure.getMessage()), config);
        String extension = EXTENSIONS.get(driver);
        String lower = message.toLowerCase(Locale.ROOT);
        if (extension != null && lower.contains("extension")
                && (lower.contains("not found") || lower.contains("not installed")
                    || lower.contains("install") || lower.contains("load"))) {
            String hint = "run make extensions to install " + extension
                    + " into DAP_DUCKDB_EXTENSION_DIR";
            return new ApiException(500, "extension_missing",
                    "db_writer: the " + extension + " extension is not available; " + hint
                            + " (" + message + ")", hint);
        }
        return new ApiException(500, "db_write_failed",
                "db_writer: cannot write the external database: " + message,
                "check 'driver', 'connection', 'table' and 'mode'; for sqlite/postgres/mysql "
                        + "the DuckDB extension must be installed (make extensions)");
    }

    /** Replace the password of a DSN with *** in a message that may quote it. */
    static String maskSecrets(String message, JsonNode config) {
        String masked = message == null ? "" : message;
        JsonNode node = config == null ? null : config.get("connection");
        String connection = node != null && node.isTextual() ? node.textValue() : null;
        if (connection == null) {
            return masked;
        }
        int scheme = connection.indexOf("://");
        int at = connection.indexOf('@', scheme < 0 ? 0 : scheme + 3);
        if (scheme >= 0 && at > scheme + 3) {
            String userInfo = connection.substring(scheme + 3, at);
            masked = masked.replace(userInfo, "***");
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                masked = masked.replace(userInfo.substring(colon + 1), "***");
            }
        }
        return masked;
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "default";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
