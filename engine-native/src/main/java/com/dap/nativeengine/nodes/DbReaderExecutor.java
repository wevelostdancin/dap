package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.io.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * dap.db_reader: copies a table (or the result of a query) of an external database
 * into the session, so the rest of the workflow sees an ordinary session table.
 *
 * <p>DuckDB files are attached natively, SQLite/Postgres/MySQL through the matching
 * DuckDB extension; the attachment is read-only by default and detached after the copy.
 * A missing extension is reported with the {@code make extensions} hint. Credentials
 * never appear in workflow.json (see {@code Workflow.toJson}) or in error messages.
 */
public final class DbReaderExecutor implements NodeExecutor {
    private static final Set<String> DRIVERS = Set.of("duckdb", "sqlite", "postgres", "mysql");
    private static final Map<String, String> ATTACH_TYPES = Map.of(
            "sqlite", "SQLITE", "postgres", "POSTGRES", "mysql", "MYSQL");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "sqlite", "sqlite_scanner", "postgres", "postgres_scanner", "mysql", "mysql_scanner");

    @Override
    public String nodeId() {
        return "dap.db_reader";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        JsonNode config = context.config();
        String driver = driver(config);
        boolean readOnly = NodeSql.bool(config, "read_only", true);
        String table = blankToNull(NodeSql.text(config, "table", null));
        String query = blankToNull(NodeSql.text(config, "query", null));
        if ((table == null) == (query == null)) {
            throw new IllegalArgumentException(
                    "db_reader: exactly one of 'table' or 'query' is required");
        }
        String source = source(config, driver);
        if ("duckdb".equals(driver) || "sqlite".equals(driver)) {
            PathGuard.shared().checkRead(source);
        }
        String alias = "ext_" + sanitize(context.workflowId()) + "_" + sanitize(context.nodeId());
        String target = "db_" + sanitize(context.workflowId()) + "_" + sanitize(context.nodeId());
        Connection connection = context.jobConnection();
        if (connection == null) {
            throw new IllegalStateException("db_reader requires a job connection");
        }
        try {
            // The job connection is shared by every node of the level; DuckDB cannot
            // interleave statements on one connection, so serialize like loadInto does.
            synchronized (connection) {
                attach(connection, driver, source, alias, readOnly);
                try {
                    String copy = table != null
                            ? "SELECT * FROM " + attachedTable(alias, driver, table)
                            : querySource(connection, alias, query);
                    execute(connection, "CREATE OR REPLACE TABLE main." + DuckDbSession.quote(target)
                            + " AS " + copy);
                    List<ColumnSpec> columns = context.db().describe(connection,
                            "SELECT * FROM main." + DuckDbSession.quote(target));
                    return new LazyPlan(context.db(),
                            "SELECT * FROM main." + DuckDbSession.quote(target), columns);
                } finally {
                    detach(connection, alias);
                }
            }
        } catch (SQLException failure) {
            throw translate(driver, failure, config);
        }
    }

    private static String driver(JsonNode config) {
        String driver = NodeSql.requiredText(config, "driver");
        if (!DRIVERS.contains(driver)) {
            throw new IllegalArgumentException("db_reader: 'driver' must be one of "
                    + new java.util.TreeSet<>(DRIVERS) + ", got '" + driver + "'");
        }
        return driver;
    }

    private static String source(JsonNode config, String driver) {
        if ("duckdb".equals(driver) || "sqlite".equals(driver)) {
            String path = blankToNull(NodeSql.text(config, "path", null));
            if (path == null) {
                throw new IllegalArgumentException("db_reader: 'path' is required for driver '" + driver + "'");
            }
            return path;
        }
        String url = blankToNull(NodeSql.text(config, "url", null));
        if (url == null) {
            throw new IllegalArgumentException("db_reader: 'url' is required for driver '" + driver + "'");
        }
        return withCredentials(url, config);
    }

    /** Merge the separate user/password settings into the connection URL. */
    private static String withCredentials(String url, JsonNode config) {
        String user = blankToNull(NodeSql.text(config, "user", null));
        String password = blankToNull(NodeSql.text(config, "password", null));
        if (user == null && password == null) {
            return url;
        }
        String userInfo = (user == null ? "" : user) + (password == null ? "" : ":" + password);
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url + (user == null ? "" : " user=" + user)
                    + (password == null ? "" : " password=" + password);
        }
        int at = url.indexOf('@', scheme + 3);
        int slash = url.indexOf('/', scheme + 3);
        if (at >= 0 && (slash < 0 || at < slash)) {
            return url.substring(0, scheme + 3) + userInfo + url.substring(at);
        }
        return url.substring(0, scheme + 3) + userInfo + "@" + url.substring(scheme + 3);
    }

    private static void attach(Connection connection, String driver, String source, String alias,
                               boolean readOnly) throws SQLException {
        List<String> options = new ArrayList<>();
        String type = ATTACH_TYPES.get(driver);
        if (type != null) {
            options.add("TYPE " + type);
        }
        if (readOnly) {
            options.add("READ_ONLY");
        }
        String sql = "ATTACH IF NOT EXISTS " + NodeSql.literal(source) + " AS "
                + DuckDbSession.quote(alias) + " (" + String.join(", ", options) + ")";
        execute(connection, sql);
    }

    private static void detach(Connection connection, String alias) {
        try {
            execute(connection, "DETACH " + DuckDbSession.quote(alias));
        } catch (SQLException ignored) {
            // best effort: a failed run may leave nothing attached, and the session close
            // detaches whatever is left.
        }
    }

    private static String attachedTable(String alias, String driver, String table) {
        String[] parts = table.split("\\.", 2);
        if (parts.length == 2) {
            return DuckDbSession.quote(alias) + "." + DuckDbSession.quote(parts[0])
                    + "." + DuckDbSession.quote(parts[1]);
        }
        if ("postgres".equals(driver)) {
            return DuckDbSession.quote(alias) + ".\"public\"." + DuckDbSession.quote(table);
        }
        return DuckDbSession.quote(alias) + "." + DuckDbSession.quote(table);
    }

    /**
     * Expose every table of the attached database as a CTE named after the table, so a
     * user query can use bare table names; schema-qualified references still work.
     */
    private static String querySource(Connection connection, String alias, String query)
            throws SQLException {
        String guarded = SqlQueryExecutor.validateReadOnly(query);
        PathGuard.shared().checkSql(guarded);
        List<String> ctes = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT schema_name, table_name FROM duckdb_tables() WHERE database_name = "
                             + NodeSql.literal(alias) + " ORDER BY schema_name, table_name")) {
            while (result.next()) {
                String schema = result.getString(1);
                String table = result.getString(2);
                if (!names.add(table)) {
                    continue;
                }
                ctes.add(DuckDbSession.quote(table) + " AS (SELECT * FROM "
                        + DuckDbSession.quote(alias) + "." + DuckDbSession.quote(schema) + "."
                        + DuckDbSession.quote(table) + ")");
            }
        }
        if (ctes.isEmpty()) {
            return guarded;
        }
        ctes.add("dap_query AS (" + guarded + "\n)");
        return "WITH " + String.join(", ", ctes) + " SELECT * FROM dap_query";
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
                    "db_reader: the " + extension + " extension is not available; " + hint
                            + " (" + message + ")", hint);
        }
        return new ApiException(500, "db_read_failed",
                "db_reader: cannot read the external database: " + message,
                "check 'driver', 'path'/'url' and 'table'/'query'; for sqlite/postgres/mysql "
                        + "the DuckDB extension must be installed (make extensions)");
    }

    /** Replace passwords and URL userinfo with *** in a message that may quote the config. */
    static String maskSecrets(String message, JsonNode config) {
        String masked = message == null ? "" : message;
        String password = textOrNull(config, "password");
        if (password != null && !password.isBlank()) {
            masked = masked.replace(password, "***");
        }
        String url = textOrNull(config, "url");
        if (url != null) {
            int scheme = url.indexOf("://");
            int at = url.indexOf('@', scheme < 0 ? 0 : scheme + 3);
            int slash = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
            if (scheme >= 0 && at > scheme + 3 && (slash < 0 || at < slash)) {
                String userInfo = url.substring(scheme + 3, at);
                masked = masked.replace(userInfo, "***");
                int colon = userInfo.indexOf(':');
                if (colon >= 0) {
                    masked = masked.replace(userInfo.substring(colon + 1), "***");
                }
            }
        }
        return masked;
    }

    private static String textOrNull(JsonNode config, String field) {
        JsonNode value = config == null ? null : config.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "default";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
