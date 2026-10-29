package com.dap.nativeengine.engine;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One DuckDB database per DAP session. The base connection owns the schema;
 * every reader/writer gets a duplicate connection sharing the same catalog.
 */
public final class DuckDbSession implements AutoCloseable {
    private static final int MAX_DISTINCT = 100_000;

    private final DuckDBConnection base;
    private final AtomicLong tableIds = new AtomicLong();
    private final Object loadLock = new Object();

    public DuckDbSession() throws SQLException {
        this.base = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        try (Statement statement = base.createStatement()) {
            statement.execute("SET TimeZone='UTC'");
        }
    }

    public DuckDBConnection newConnection() throws SQLException {
        return base.duplicate();
    }

    /**
     * Copy an Arrow table into a DuckDB table. duckdb_jdbc exposes no public
     * zero-copy Arrow registration, so this is a measured row-wise copy.
     */
    public String load(ArrowTable table, String prefix) throws SQLException {
        Objects.requireNonNull(table, "table");
        String name = sanitize(prefix) + "_" + tableIds.incrementAndGet();
        List<ColumnSpec> columns = table.columns();
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(quote(name)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(quote(columns.get(i).name())).append(' ').append(columns.get(i).duckDbType());
        }
        ddl.append(')');
        synchronized (loadLock) {
            try (Statement statement = base.createStatement()) {
                statement.execute(ddl.toString());
            }
            try (DuckDBAppender appender = base.createAppender(name)) {
                for (int row = 0; row < table.rowCount(); row++) {
                    appender.beginRow();
                    for (int column = 0; column < columns.size(); column++) {
                        append(appender, table.get(column, row));
                    }
                    appender.endRow();
                }
            } catch (SQLException | RuntimeException failure) {
                execute("DROP TABLE IF EXISTS " + quote(name));
                throw failure;
            }
        }
        return name;
    }

    private static void append(DuckDBAppender appender, Object value) throws SQLException {
        if (value == null) {
            appender.appendNull();
        } else if (value instanceof Long typed) {
            appender.append(typed.longValue());
        } else if (value instanceof Double typed) {
            appender.append(typed.doubleValue());
        } else if (value instanceof Boolean typed) {
            appender.append(typed.booleanValue());
        } else if (value instanceof LocalDate typed) {
            appender.append(typed);
        } else if (value instanceof LocalTime typed) {
            appender.append(typed);
        } else if (value instanceof java.time.LocalDateTime typed) {
            appender.append(typed);
        } else if (value instanceof BigDecimal typed) {
            appender.append(typed);
        } else {
            appender.append(value.toString());
        }
    }

    public TableData preview(String sql, List<ColumnSpec> columns, int limit) throws SQLException {
        int safeLimit = Math.max(0, limit);
        try (DuckDBConnection connection = newConnection();
             Statement statement = connection.createStatement()) {
            long rows = countWith(statement, sql);
            List<List<String>> data = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT * FROM (" + LazyPlan.stripSemicolon(sql) + ") AS dap_plan LIMIT " + safeLimit)) {
                while (result.next()) {
                    List<String> row = new ArrayList<>(columns.size());
                    for (int column = 1; column <= columns.size(); column++) {
                        row.add(format(result.getObject(column)));
                    }
                    data.add(row);
                }
            }
            return new TableData(rows, columns, data);
        }
    }

    public long count(String sql) throws SQLException {
        try (DuckDBConnection connection = newConnection();
             Statement statement = connection.createStatement()) {
            return countWith(statement, sql);
        }
    }

    private static long countWith(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM (" + LazyPlan.stripSemicolon(sql) + ") AS dap_plan")) {
            result.next();
            return result.getLong(1);
        }
    }

    public ProfileData profile(String sql, List<ColumnSpec> columns) throws SQLException {
        try (DuckDBConnection connection = newConnection();
             Statement statement = connection.createStatement()) {
            long rows = countWith(statement, sql);
            List<ProfileColumn> profile = new ArrayList<>(columns.size());
            for (ColumnSpec column : columns) {
                String quoted = quote(column.name());
                String projected = "CAST(" + quoted + " AS VARCHAR)";
                String stats = "COUNT(*) - COUNT(" + quoted + ") AS missing, COUNT(DISTINCT " + quoted + ") AS distinct";
                if (column.numeric()) {
                    stats += ", MIN(" + quoted + ") AS min_value, MAX(" + quoted + ") AS max_value, AVG(" + quoted + ") AS mean_value";
                }
                String query = "SELECT " + stats + " FROM (" + LazyPlan.stripSemicolon(sql) + ") AS dap_plan";
                try (ResultSet result = statement.executeQuery(query)) {
                    result.next();
                    long missing = result.getLong("missing");
                    long distinct = result.getLong("distinct");
                    Double min = column.numeric() ? asDouble(result.getObject("min_value")) : null;
                    Double max = column.numeric() ? asDouble(result.getObject("max_value")) : null;
                    Double mean = column.numeric() ? asDouble(result.getObject("mean_value")) : null;
                    profile.add(new ProfileColumn(column.name(), column.knimeType(), missing, distinct, min, max, mean));
                }
            }
            return new ProfileData(rows, profile);
        }
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    public void execute(String sql) throws SQLException {
        try (Statement statement = base.createStatement()) {
            statement.execute(sql);
        }
    }

    public void dropTables(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        for (String name : names) {
            try {
                execute("DROP TABLE IF EXISTS " + quote(name));
            } catch (SQLException ignored) {
                // Best effort cleanup: the session database disappears with the session.
            }
        }
    }

    public String nextTableName(String prefix) {
        return sanitize(prefix) + "_" + tableIds.incrementAndGet();
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static String format(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Double typed) {
            return Double.toString(typed);
        }
        if (value instanceof Float typed) {
            return Float.toString(typed);
        }
        if (value instanceof BigDecimal typed) {
            return typed.toPlainString();
        }
        if (value instanceof java.sql.Timestamp typed) {
            return typed.toLocalDateTime().toString();
        }
        return value.toString();
    }

    /** Diagnostics for /debug. */
    public static List<String> columnNames(ResultSetMetaData metadata) throws SQLException {
        List<String> names = new ArrayList<>(metadata.getColumnCount());
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            names.add(metadata.getColumnName(i));
        }
        return names;
    }

    @Override
    public void close() {
        try {
            base.close();
        } catch (SQLException ignored) {
            // shutdown path: nothing useful to do
        }
    }
}
