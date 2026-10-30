package com.dap.nativeengine.engine;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One DuckDB database per DAP session. The base connection owns the schema;
 * every reader/writer gets a duplicate connection sharing the same catalog.
 */
public final class DuckDbSession implements AutoCloseable {
    private static final int MAX_DISTINCT = 100_000;
    /**
     * Sketch estimates this close to the cap are refined with an exact count. The margin is
     * 10 % (about 3 sigma of the sketch's ~3.2 % error), so a column with exactly 100k
     * distinct values is still counted exactly.
     */
    private static final int REFINE_DISTINCT = MAX_DISTINCT + MAX_DISTINCT / 10;

    /** Effective database settings, read back from DuckDB (normalized, e.g. 1GB -> 953.6 MiB). */
    public record Resources(String memoryLimit, Integer threads, String tempDirectory) {
    }

    private final DuckDBConnection base;
    private final Resources resources;
    private final AtomicLong tableIds = new AtomicLong();
    private final Object loadLock = new Object();
    private final ConcurrentHashMap<DuckDBConnection, Read> reads = new ConcurrentHashMap<>();
    private final Object readsLock = new Object();
    private boolean closed;

    /** Defaults: DuckDB's own resource settings are left untouched. */
    public DuckDbSession() throws SQLException {
        this(null, null, null);
    }

    /**
     * Applies the configured DuckDB resources on the base connection, which owns the database:
     * duplicated connections inherit them because memory_limit, threads and temp_directory are
     * database-level settings. Invalid values fail here with a message naming the setting.
     */
    public DuckDbSession(String memoryLimit, Integer threads, String tempDirectory) throws SQLException {
        if (threads != null && threads <= 0) {
            throw new IllegalArgumentException("DuckDB threads must be positive, got " + threads);
        }
        this.base = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        try {
            apply(memoryLimit, threads, tempDirectory);
            this.resources = readResources(memoryLimit);
        } catch (SQLException | RuntimeException failure) {
            closeQuietly(base);
            throw failure;
        }
    }

    public Resources resources() {
        return resources;
    }

    private void apply(String memoryLimit, Integer threads, String tempDirectory) throws SQLException {
        try (Statement statement = base.createStatement()) {
            statement.execute("SET TimeZone='UTC'");
        }
        if (threads != null) {
            try (Statement statement = base.createStatement()) {
                statement.execute("SET threads=" + threads);
            }
        }
        if (memoryLimit != null) {
            try (Statement statement = base.createStatement()) {
                statement.execute("SET memory_limit=" + literal(memoryLimit));
            } catch (SQLException failure) {
                throw new SQLException("invalid DuckDB memory limit '" + memoryLimit + "': "
                        + failure.getMessage(), failure);
            }
        }
        if (tempDirectory != null) {
            try {
                Files.createDirectories(Path.of(tempDirectory));
            } catch (IOException failure) {
                throw new SQLException("cannot create DuckDB temp directory '" + tempDirectory + "': "
                        + failure.getMessage(), failure);
            }
            try (Statement statement = base.createStatement()) {
                statement.execute("SET temp_directory=" + literal(tempDirectory));
            } catch (SQLException failure) {
                throw new SQLException("invalid DuckDB temp directory '" + tempDirectory + "': "
                        + failure.getMessage(), failure);
            }
        }
    }

    private Resources readResources(String configuredMemoryLimit) throws SQLException {
        try (Statement statement = base.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT current_setting('memory_limit'), current_setting('threads'), "
                             + "current_setting('temp_directory')")) {
            result.next();
            String tempDirectory = result.getString(3);
            return new Resources(result.getString(1),
                    Integer.valueOf(result.getString(2).strip()),
                    tempDirectory == null || tempDirectory.isEmpty() ? null : tempDirectory);
        } catch (SQLException failure) {
            if (configuredMemoryLimit != null) {
                // DuckDB accepts SET memory_limit='0GB' and then cannot allocate at all, so the
                // read-back is the first place a too-small limit surfaces.
                throw new SQLException("invalid DuckDB memory limit '" + configuredMemoryLimit + "': "
                        + failure.getMessage(), failure);
            }
            throw failure;
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
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

    /**
     * Create or replace {@code tableName} with this Arrow table on the given
     * job connection. A stable name means a re-run replaces the previous copy
     * instead of leaking one table per execution, and ingest runs on the job
     * connection so a cancelled or timed-out job can interrupt it.
     */
    public String loadInto(Connection connection, ArrowTable table, String tableName) throws SQLException {
        Objects.requireNonNull(table, "table");
        if (connection == null) {
            throw new IllegalArgumentException("loadInto requires a DuckDBConnection, got null");
        }
        if (!(connection instanceof DuckDBConnection duck)) {
            throw new IllegalArgumentException("loadInto requires a DuckDBConnection, got "
                    + connection.getClass().getName());
        }
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("table name must not be empty");
        }
        // Parallel csv_reader nodes share one job connection, and DuckDB cannot
        // interleave pending query results on a connection: serialize ingest.
        synchronized (connection) {
            List<ColumnSpec> columns = table.columns();
            StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TABLE ").append(quote(tableName)).append(" (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    ddl.append(", ");
                }
                ddl.append(quote(columns.get(i).name())).append(' ').append(columns.get(i).duckDbType());
            }
            ddl.append(')');
            try (Statement statement = connection.createStatement()) {
                statement.execute(ddl.toString());
            }
            try (DuckDBAppender appender = duck.createAppender(tableName)) {
                for (int row = 0; row < table.rowCount(); row++) {
                    appender.beginRow();
                    for (int column = 0; column < columns.size(); column++) {
                        append(appender, table.get(column, row));
                    }
                    appender.endRow();
                }
            } catch (SQLException | RuntimeException failure) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE IF EXISTS " + quote(tableName));
                } catch (SQLException | RuntimeException ignored) {
                    // best effort: the failed job owns this table
                }
                throw failure;
            }
            return tableName;
        }
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
        return preview(sql, columns, limit, null, null);
    }

    /** Preview registered against a workflow/node so session close and job cancel can interrupt it. */
    public TableData preview(String sql, List<ColumnSpec> columns, int limit,
                             String workflowId, String nodeId) throws SQLException {
        int safeLimit = Math.max(0, limit);
        DuckDBConnection connection = openRead(workflowId, nodeId);
        try (Statement statement = connection.createStatement()) {
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
        } finally {
            endRead(connection);
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
        return profile(sql, columns, null, null);
    }

    /** Profile registered against a workflow/node so session close and job cancel can interrupt it. */
    public ProfileData profile(String sql, List<ColumnSpec> columns,
                               String workflowId, String nodeId) throws SQLException {
        DuckDBConnection connection = openRead(workflowId, nodeId);
        try (Statement statement = connection.createStatement()) {
            long rows = countWith(statement, sql);
            List<ProfileColumn> profile = new ArrayList<>(columns.size());
            for (ColumnSpec column : columns) {
                String quoted = quote(column.name());
                // KNIME counts distinct exactly up to 100k distinct values per column; above
                // that the approximate sketch is allowed to differ (PARITY.md §6.3). Decide
                // from the single COUNT(*) taken for the whole plan, not per column.
                String distinctSql = rows > MAX_DISTINCT
                        ? "APPROX_COUNT_DISTINCT(" + quoted + ")"
                        : "COUNT(DISTINCT " + quoted + ")";
                String stats = "COUNT(*) - COUNT(" + quoted + ") AS missing, " + distinctSql + " AS distinct";
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
                    if (rows > MAX_DISTINCT && distinct <= REFINE_DISTINCT) {
                        // The sketch may only differ for genuinely large cardinality: when its
                        // estimate is still near the cap, refine to the exact count so the
                        // "exact up to 100k" promise survives the sketch's ~3 % error. A second
                        // statement keeps the open stats ResultSet out of the driver's way.
                        try (Statement refine = connection.createStatement()) {
                            distinct = exactDistinct(refine, sql, quoted);
                        }
                    }
                    profile.add(new ProfileColumn(column.name(), column.knimeType(), missing, distinct, min, max, mean));
                }
            }
            return new ProfileData(rows, profile);
        } finally {
            endRead(connection);
        }
    }

    private static long exactDistinct(Statement statement, String sql, String quoted) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "SELECT COUNT(DISTINCT " + quoted + ") FROM (" + LazyPlan.stripSemicolon(sql) + ") AS dap_plan")) {
            result.next();
            return result.getLong(1);
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

    /** One in-flight materialization; closing its connection interrupts the running query. */
    private record Read(String workflowId, String nodeId) {
    }

    private DuckDBConnection openRead(String workflowId, String nodeId) throws SQLException {
        DuckDBConnection connection = newConnection();
        synchronized (readsLock) {
            if (closed) {
                closeQuietly(connection);
                throw new SQLException("session is closed");
            }
            reads.put(connection, new Read(workflowId, nodeId));
        }
        return connection;
    }

    private void endRead(DuckDBConnection connection) {
        reads.remove(connection);
        closeQuietly(connection);
    }

    /** Interrupt the in-flight /table and /profile reads of one workflow only. */
    public void interruptReads(String workflowId) {
        if (workflowId == null) {
            return;
        }
        for (Map.Entry<DuckDBConnection, Read> entry : reads.entrySet()) {
            if (workflowId.equals(entry.getValue().workflowId())
                    && reads.remove(entry.getKey()) != null) {
                closeQuietly(entry.getKey());
            }
        }
    }

    int activeReadCount() {
        return reads.size();
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort: closing a connection interrupts its running query
        }
    }

    @Override
    public void close() {
        synchronized (readsLock) {
            closed = true;
            for (DuckDBConnection connection : reads.keySet()) {
                closeQuietly(connection);
            }
            reads.clear();
        }
        closeQuietly(base);
    }
}
