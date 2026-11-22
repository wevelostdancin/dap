package com.dap.nativeengine.engine;

import com.dap.nativeengine.api.ApiException;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    /** Virtual threads for bounded reads: cheap, daemon, and one per materialization. */
    private static final ExecutorService READS = Executors.newVirtualThreadPerTaskExecutor();

    /** DAP_TABLE_MATERIALIZE_MAX_ROWS: above it /table re-reads the plan with ORDER BY ALL. */
    private static final long DEFAULT_MATERIALIZE_MAX_ROWS = 2_000_000L;
    private static final String MATERIALIZE_MAX_ROWS_ENV = "DAP_TABLE_MATERIALIZE_MAX_ROWS";
    /** Test seam for the env setting, the same pattern as NativeService's dap.port. */
    private static final String MATERIALIZE_MAX_ROWS_PROPERTY = "dap.table.materialize.maxRows";
    /** DAP_TABLE_MATERIALIZE_BUDGET_ROWS: total rows kept in all /table tables of one session. */
    private static final long DEFAULT_PAGE_BUDGET_ROWS = 4_000_000L;
    private static final String PAGE_BUDGET_ROWS_ENV = "DAP_TABLE_MATERIALIZE_BUDGET_ROWS";
    private static final String PAGE_BUDGET_ROWS_PROPERTY = "dap.table.materialize.budgetRows";
    private static final String PAGE_TABLE_PREFIX = "dap_page_";
    private static final String RID_COLUMN = "__dap_rid";

    /** Effective database settings, read back from DuckDB (normalized, e.g. 1GB -> 953.6 MiB). */
    public record Resources(String memoryLimit, Integer threads, String tempDirectory) {
    }

    private final DuckDBConnection base;
    private final Resources resources;
    private final AtomicLong tableIds = new AtomicLong();
    private final Object loadLock = new Object();
    private final ConcurrentHashMap<DuckDBConnection, Read> reads = new ConcurrentHashMap<>();
    /** Shared by report sections and GET /profile within one run; see {@link ProfileCache}. */
    private final ProfileCache profiles = new ProfileCache();
    /** One materialized table per (workflow, node, page SQL, generation) of GET /table. */
    private final Map<PageKey, PageTable> pageTables = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> pageGenerations = new ConcurrentHashMap<>();
    private final AtomicLong pageTableIds = new AtomicLong();
    /** Guards pageTables, pageTableRows and the CREATE/DROP of the tables they name. */
    private final Object pagesLock = new Object();
    private final long materializeMaxRows;
    private final long pageBudgetRows;
    private long pageTableRows;
    /** Guards {@link #reads}: openRead, endRead, close and interruptReads all take it. */
    final Object readsLock = new Object();
    private boolean closed;

    /** Defaults: DuckDB's own resource settings are left untouched. */
    public DuckDbSession() throws SQLException {
        this(null, null, null);
    }

    /** Defaults plus an explicit extension directory; null leaves DuckDB's default. */
    public DuckDbSession(String memoryLimit, Integer threads, String tempDirectory,
                         String extensionDirectory) throws SQLException {
        if (threads != null && threads <= 0) {
            throw new IllegalArgumentException("DuckDB threads must be positive, got " + threads);
        }
        this.materializeMaxRows = materializeMaxRows();
        this.pageBudgetRows = pageBudgetRows();
        this.base = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:");
        try {
            apply(memoryLimit, threads, tempDirectory, extensionDirectory);
            this.resources = readResources(memoryLimit);
        } catch (SQLException | RuntimeException failure) {
            closeQuietly(base);
            throw failure;
        }
    }

    /**
     * Applies the configured DuckDB resources on the base connection, which owns the database:
     * duplicated connections inherit them because memory_limit, threads and temp_directory are
     * database-level settings. Invalid values fail here with a message naming the setting.
     */
    public DuckDbSession(String memoryLimit, Integer threads, String tempDirectory) throws SQLException {
        this(memoryLimit, threads, tempDirectory, null);
    }

    public Resources resources() {
        return resources;
    }

    private void apply(String memoryLimit, Integer threads, String tempDirectory,
                       String extensionDirectory) throws SQLException {
        try (Statement statement = base.createStatement()) {
            statement.execute("SET TimeZone='UTC'");
            // Out-of-core default (spec §8): scans may reorder rows, which lets DuckDB stream
            // and spill instead of buffering the insertion order of every table.
            statement.execute("SET preserve_insertion_order=false");
        }
        applyExtensionDirectory(extensionDirectory);
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

    /**
     * Point DuckDB at the configured extension directory and switch autoinstall off: the
     * engine never downloads extensions on its own, only the explicit installer does.
     */
    private void applyExtensionDirectory(String extensionDirectory) throws SQLException {
        if (extensionDirectory == null) {
            return;
        }
        try {
            Files.createDirectories(Path.of(extensionDirectory));
        } catch (IOException failure) {
            // A read-only home must not keep the engine from starting; extensions are optional.
            System.err.println("dap-native: cannot create DuckDB extension directory '"
                    + extensionDirectory + "': " + failure.getMessage());
            return;
        }
        try (Statement statement = base.createStatement()) {
            statement.execute("SET extension_directory=" + literal(extensionDirectory));
        } catch (SQLException failure) {
            throw new SQLException("invalid DuckDB extension directory '" + extensionDirectory
                    + "': " + failure.getMessage(), failure);
        }
        try (Statement statement = base.createStatement()) {
            statement.execute("SET autoinstall_known_extensions=false");
        } catch (SQLException ignored) {
            // Older DuckDB builds have no such setting; explicit installs still work.
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
                // read-back is the first place a too-small limit surfaces. Any other read-back
                // failure keeps its own message instead of blaming the memory limit.
                String message = String.valueOf(failure.getMessage());
                if (message.contains("Memory") || message.contains("memory")) {
                    throw new SQLException("invalid DuckDB memory limit '" + configuredMemoryLimit + "': "
                            + message, failure);
                }
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
        return preview(sql, columns, limit, 0L, workflowId, nodeId);
    }

    /**
     * Bounded preview: the materialization runs on a virtual thread and is interrupted
     * (connection closed) after {@code timeoutMillis}, then the call fails with 408
     * {@code query_timeout}. A non-positive timeout keeps the unbounded behaviour.
     */
    public TableData preview(String sql, List<ColumnSpec> columns, int limit, long timeoutMillis,
                             String workflowId, String nodeId) throws SQLException {
        int safeLimit = Math.max(0, limit);
        DuckDBConnection connection = openRead(workflowId, nodeId);
        try {
            return bounded("table", timeoutMillis, workflowId,
                    () -> readPreview(connection, sql, columns, safeLimit));
        } finally {
            endRead(connection);
        }
    }

    private static TableData readPreview(DuckDBConnection connection, String sql,
                                         List<ColumnSpec> columns, int safeLimit) throws SQLException {
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
        }
    }

    /**
     * One page of a plan after an optional filter: {@code rows} is the total count after the
     * filter, {@code data} is the projected page starting at {@code offset}.
     */
    public TableData page(String sql, List<ColumnSpec> columns, int limit, int offset,
                          long timeoutMillis, String workflowId, String nodeId) throws SQLException {
        int safeLimit = Math.max(0, limit);
        int safeOffset = Math.max(0, offset);
        DuckDBConnection connection = openRead(workflowId, nodeId);
        try {
            return bounded("table", timeoutMillis, workflowId,
                    () -> readPage(connection, sql, columns, safeLimit, safeOffset, workflowId, nodeId));
        } finally {
            endRead(connection);
        }
    }

    private record PageKey(String workflowId, String nodeId, String sql, long generation) {
    }

    /** name is null when the plan exceeded a cap: rows still carries its count for the fallback. */
    private record PageTable(String name, long rows) {
    }

    private TableData readPage(DuckDBConnection connection, String sql, List<ColumnSpec> columns,
                               int limit, int offset, String workflowId, String nodeId)
            throws SQLException {
        String plan = LazyPlan.stripSemicolon(sql);
        if (workflowId == null || nodeId == null) {
            // No run to scope a cache entry to: the deterministic fallback still applies.
            return readFallback(connection, plan, columns, limit, offset, countWith(connection, sql));
        }
        PageKey key = new PageKey(workflowId, nodeId, sql, pageGeneration(workflowId));
        PageTable cached = getPageTable(key);
        if (cached == null) {
            synchronized (pagesLock) {
                cached = pageTables.get(key);
                if (cached == null) {
                    cached = materializePage(connection, key, plan);
                }
                if (cached.name() != null) {
                    // Refresh access order: the oldest entry is evicted once the budget is full.
                    pageTables.remove(key);
                    pageTables.put(key, cached);
                }
            }
        }
        if (cached.name() == null) {
            return readFallback(connection, plan, columns, limit, offset, cached.rows());
        }
        return readMaterialized(connection, cached, columns, limit, offset);
    }

    private PageTable getPageTable(PageKey key) {
        synchronized (pagesLock) {
            PageTable entry = pageTables.get(key);
            if (entry != null) {
                pageTables.remove(key);
                pageTables.put(key, entry);
            }
            return entry;
        }
    }

    /** Called with {@link #pagesLock} held so concurrent first pages share one table. */
    private PageTable materializePage(DuckDBConnection connection, PageKey key, String plan)
            throws SQLException {
        String name = PAGE_TABLE_PREFIX + sanitize(key.workflowId()) + "_"
                + sanitize(key.nodeId()) + "_" + pageTableIds.incrementAndGet();
        // DuckDB TEMP tables are connection-local, so the scratch table lives in main under a
        // reserved prefix that dropTablesForWorkflow and the budget eviction both know.
        // The stable key is DuckDB's rowid (measured: window row_number() costs ~130 ms extra
        // on 2M rows); __dap_rid is only an alias in the page query, never stored or returned.
        String ddl = "CREATE TABLE " + quote(name) + " AS SELECT * FROM (" + plan + ") AS p";
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException failure) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + quote(name));
            } catch (SQLException ignored) {
                // Best effort: a half-created table must not survive a failed request.
            }
            throw failure;
        }
        // COUNT(*) over the materialized table reads its cardinality instead of re-running
        // the plan: one scan per first page, and no count on any later page.
        long rows;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + quote(name))) {
            result.next();
            rows = result.getLong(1);
        }
        if (rows > materializeMaxRows || rows > pageBudgetRows) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + quote(name));
            } catch (SQLException ignored) {
                // Best effort: an oversized table must not stay in the catalog.
            }
            return new PageTable(null, rows);
        }
        PageTable entry = new PageTable(name, rows);
        pageTables.put(key, entry);
        pageTableRows += rows;
        // ponytail: one session-wide LRU; per-workflow budgets only if /table concurrency matters.
        while (pageTableRows > pageBudgetRows) {
            Map.Entry<PageKey, PageTable> oldest = pageTables.entrySet().iterator().next();
            pageTables.remove(oldest.getKey());
            pageTableRows -= oldest.getValue().rows();
            dropTables(List.of(oldest.getValue().name()));
        }
        return entry;
    }

    private static TableData readMaterialized(DuckDBConnection connection, PageTable table,
                                              List<ColumnSpec> columns, int limit, int offset)
            throws SQLException {
        return readRows(connection, "SELECT * EXCLUDE (" + quote(RID_COLUMN) + ") FROM "
                + "(SELECT rowid AS " + quote(RID_COLUMN) + ", * FROM " + quote(table.name())
                + ") AS dap_rids ORDER BY " + quote(RID_COLUMN)
                + " LIMIT " + limit + " OFFSET " + offset, columns, table.rows());
    }

    /** Correct but expensive: compare every column so a large plan still has one stable order. */
    private static TableData readFallback(DuckDBConnection connection, String plan,
                                          List<ColumnSpec> columns, int limit, int offset, long rows)
            throws SQLException {
        return readRows(connection, "SELECT * FROM (" + plan + ") AS dap_page ORDER BY ALL"
                + " LIMIT " + limit + " OFFSET " + offset, columns, rows);
    }

    private static TableData readRows(DuckDBConnection connection, String sql,
                                      List<ColumnSpec> columns, long rows) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            List<List<String>> data = new ArrayList<>();
            while (result.next()) {
                List<String> row = new ArrayList<>(columns.size());
                for (int column = 1; column <= columns.size(); column++) {
                    row.add(format(result.getObject(column)));
                }
                data.add(row);
            }
            return new TableData(rows, columns, data);
        }
    }

    private static long materializeMaxRows() {
        return configuredRows(MATERIALIZE_MAX_ROWS_PROPERTY, MATERIALIZE_MAX_ROWS_ENV,
                DEFAULT_MATERIALIZE_MAX_ROWS);
    }

    private static long pageBudgetRows() {
        return configuredRows(PAGE_BUDGET_ROWS_PROPERTY, PAGE_BUDGET_ROWS_ENV,
                DEFAULT_PAGE_BUDGET_ROWS);
    }

    private static long configuredRows(String property, String env, long fallback) {
        String configured = System.getProperty(property, System.getenv(env));
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            long value = Long.parseLong(configured.strip());
            if (value < 0) {
                throw new NumberFormatException(configured);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(env + " must be a non-negative integer, got "
                    + configured);
        }
    }

    /**
     * The physical plan of a query as DuckDB's JSON array, without executing it.
     * {@code EXPLAIN (FORMAT JSON)} returns (explain_key, explain_value) rows.
     */
    public String explainJson(String sql) throws SQLException {
        try (DuckDBConnection connection = newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("EXPLAIN (FORMAT JSON) "
                     + LazyPlan.stripSemicolon(sql))) {
            int valueColumn = result.getMetaData().getColumnCount() >= 2 ? 2 : 1;
            while (result.next()) {
                String value = result.getString(valueColumn);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            throw new SQLException("EXPLAIN returned no plan");
        }
    }

    /**
     * Declared output columns of a query: {@code SELECT * FROM (<sql>) LIMIT 0} plus JDBC
     * metadata, mapped through {@link TypeMap}. Nested result types (LIST, STRUCT, MAP)
     * and TIMESTAMPTZ are rejected with the column name because the engine has no Arrow
     * representation for them; duplicate result aliases get deterministic {@code a_1},
     * {@code a_2} suffixes so /profile can address every column.
     */
    public List<ColumnSpec> describe(Connection connection, String sql) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be empty");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT * FROM (" + LazyPlan.stripSemicolon(sql) + "\n) AS dap_describe LIMIT 0")) {
            ResultSetMetaData metadata = result.getMetaData();
            List<ColumnSpec> columns = new ArrayList<>(metadata.getColumnCount());
            Set<String> names = new LinkedHashSet<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                String name = metadata.getColumnName(index);
                String duckDbType = metadata.getColumnTypeName(index);
                String arrowType;
                try {
                    arrowType = TypeMap.fromDuckDb(duckDbType);
                } catch (IllegalArgumentException unsupported) {
                    throw ApiException.badRequest("sql_type_not_supported",
                            "column '" + name + "' has unsupported type " + duckDbType,
                            "LIST, STRUCT, MAP and TIMESTAMPTZ columns cannot be returned; "
                                    + "cast the column to a scalar type in the query");
                }
                columns.add(ColumnSpec.of(uniqueName(names, name), arrowType));
            }
            return columns;
        }
    }

    private static String uniqueName(Set<String> used, String name) {
        String candidate = name;
        int suffix = 1;
        while (!used.add(candidate)) {
            candidate = name + "_" + suffix;
            suffix++;
        }
        return candidate;
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

    private static long countWith(DuckDBConnection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return countWith(statement, sql);
        }
    }

    public ProfileData profile(String sql, List<ColumnSpec> columns) throws SQLException {
        return profile(sql, columns, null, null);
    }

    /** Profile registered against a workflow/node so session close and job cancel can interrupt it. */
    public ProfileData profile(String sql, List<ColumnSpec> columns,
                               String workflowId, String nodeId) throws SQLException {
        return profile(sql, columns, 0L, workflowId, nodeId);
    }

    /** Bounded profile: 408 {@code query_timeout} when the scan exceeds {@code timeoutMillis}. */
    public ProfileData profile(String sql, List<ColumnSpec> columns, long timeoutMillis,
                               String workflowId, String nodeId) throws SQLException {
        return profile(sql, columns, timeoutMillis, workflowId, nodeId, false);
    }

    /**
     * Same as the five-argument profile; {@code withStddev} also computes the sample
     * standard deviation that report sections render. The flag is part of the cache key,
     * so {@code /profile} never pays for statistics it does not return.
     */
    public ProfileData profile(String sql, List<ColumnSpec> columns, long timeoutMillis,
                               String workflowId, String nodeId, boolean withStddev)
            throws SQLException {
        ProfileData cached = profiles.get(workflowId, nodeId, sql, columns, withStddev);
        if (cached != null) {
            return cached;
        }
        DuckDBConnection connection = openRead(workflowId, nodeId);
        try {
            ProfileData data = bounded("profile", timeoutMillis, workflowId,
                    () -> readProfile(connection, sql, columns, withStddev));
            profiles.put(workflowId, nodeId, sql, columns, withStddev, data);
            if (withStddev) {
                // A report section warms the plain entry too: the same scan answers the
                // following GET /profile, which never returns stddev.
                profiles.put(workflowId, nodeId, sql, columns, false, data.withoutStddev());
            }
            return data;
        } finally {
            endRead(connection);
        }
    }

    /** Call at the start of a workflow run: profiles of the previous run are no longer valid. */
    public void invalidateProfiles(String workflowId) {
        profiles.invalidate(workflowId);
    }

    private long pageGeneration(String workflowId) {
        AtomicLong generation = pageGenerations.get(workflowId);
        return generation == null ? 0L : generation.get();
    }

    /** Call at the start of a workflow run: /table tables of the previous run are stale. */
    public void invalidateTables(String workflowId) {
        if (workflowId == null) {
            return;
        }
        pageGenerations.computeIfAbsent(workflowId, ignored -> new AtomicLong()).incrementAndGet();
        dropPageTables(workflowId);
    }

    private void dropPageTables(String workflowId) {
        synchronized (pagesLock) {
            List<String> drop = new ArrayList<>();
            pageTables.entrySet().removeIf(entry -> {
                if (!workflowId.equals(entry.getKey().workflowId())) {
                    return false;
                }
                drop.add(entry.getValue().name());
                pageTableRows -= entry.getValue().rows();
                return true;
            });
            dropTables(drop);
        }
    }

    /** Above this row count the profile skips the broad exact-distinct refinement to stay bounded. */
    private static final long REFINE_ROW_LIMIT = 1_000_000L;
    /**
     * Above {@link #REFINE_ROW_LIMIT} only cheap low-cardinality columns are refined: one extra
     * query counts DISTINCT for columns whose sketch estimate is at most this value. Higher
     * cardinality keeps the sketch — an exact count over millions of unique values is the
     * dominant cost of a large profile, and both /profile and report sections accept it.
     */
    private static final long REFINE_SMALL_DISTINCT = 10_000L;

    private static ProfileData readProfile(DuckDBConnection connection, String sql,
                                           List<ColumnSpec> columns, boolean withStddev)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            long rows = countWith(statement, sql);
            // Up to MAX_DISTINCT rows the exact count is cheap and comes from the main scan;
            // above it the sketch is used and small columns are refined afterwards
            // (docs/QUALITY.md pins the policy).
            boolean exactDistinct = rows <= MAX_DISTINCT;
            boolean approximate = !exactDistinct;
            String table = "(" + LazyPlan.stripSemicolon(sql) + ") AS dap_plan";
            StringBuilder aggregates = new StringBuilder("COUNT(*) AS dap_rows");
            for (int index = 0; index < columns.size(); index++) {
                ColumnSpec column = columns.get(index);
                String quoted = quote(column.name());
                // One scan yields the /profile statistics, the report's stddev, and (when
                // bounded) the exact distinct used for refinement and report sections.
                aggregates.append(", COUNT(").append(quoted).append(") AS \"c").append(index)
                        .append("_present\"");
                if (exactDistinct) {
                    aggregates.append(", COUNT(DISTINCT ").append(quoted).append(") AS \"c")
                            .append(index).append("_exact\"");
                }
                if (approximate) {
                    aggregates.append(", APPROX_COUNT_DISTINCT(").append(quoted)
                            .append(") AS \"c").append(index).append("_approx\"");
                }
                if (column.numeric()) {
                    aggregates.append(", MIN(").append(quoted).append(") AS \"c").append(index)
                            .append("_min\"");
                    aggregates.append(", MAX(").append(quoted).append(") AS \"c").append(index)
                            .append("_max\"");
                    aggregates.append(", AVG(").append(quoted).append(") AS \"c").append(index)
                            .append("_mean\"");
                    if (withStddev) {
                        aggregates.append(", STDDEV_SAMP(").append(quoted).append(") AS \"c")
                                .append(index).append("_stddev\"");
                    }
                }
            }
            long[] present = new long[columns.size()];
            long[] distinct = new long[columns.size()];
            long[] exact = new long[columns.size()];
            Double[] min = new Double[columns.size()];
            Double[] max = new Double[columns.size()];
            Double[] mean = new Double[columns.size()];
            Double[] stddev = new Double[columns.size()];
            try (ResultSet result = statement.executeQuery(
                    "SELECT " + aggregates + " FROM " + table)) {
                result.next();
                for (int index = 0; index < columns.size(); index++) {
                    ColumnSpec column = columns.get(index);
                    present[index] = result.getLong("c" + index + "_present");
                    if (exactDistinct) {
                        exact[index] = result.getLong("c" + index + "_exact");
                        distinct[index] = exact[index];
                    } else {
                        // The sketch is the value until the refinement pass below replaces it.
                        exact[index] = result.getLong("c" + index + "_approx");
                        distinct[index] = exact[index];
                    }
                    if (column.numeric()) {
                        min[index] = asDouble(result.getObject("c" + index + "_min"));
                        max[index] = asDouble(result.getObject("c" + index + "_max"));
                        mean[index] = asDouble(result.getObject("c" + index + "_mean"));
                        if (withStddev) {
                            stddev[index] = asDouble(result.getObject("c" + index + "_stddev"));
                        }
                    }
                }
            }
            if (approximate) {
                // A sketch can say "9" for eight categories. One extra query counts DISTINCT
                // for the cheap low-cardinality columns; high-cardinality ones keep the sketch
                // so a large profile stays bounded. Up to REFINE_ROW_LIMIT the threshold is the
                // documented 110k; above it only very small columns, where sketch error is most
                // visible.
                long refineLimit = rows <= REFINE_ROW_LIMIT ? REFINE_DISTINCT : REFINE_SMALL_DISTINCT;
                List<Integer> refine = new ArrayList<>();
                for (int index = 0; index < columns.size(); index++) {
                    if (distinct[index] <= refineLimit) {
                        refine.add(index);
                    }
                }
                if (!refine.isEmpty()) {
                    StringBuilder small = new StringBuilder();
                    for (int position = 0; position < refine.size(); position++) {
                        if (position > 0) {
                            small.append(", ");
                        }
                        int index = refine.get(position);
                        small.append("COUNT(DISTINCT ").append(quote(columns.get(index).name()))
                                .append(") AS \"r").append(index).append('"');
                    }
                    try (ResultSet result = statement.executeQuery(
                            "SELECT " + small + " FROM " + table)) {
                        result.next();
                        for (int index : refine) {
                            exact[index] = result.getLong("r" + index);
                            distinct[index] = exact[index];
                        }
                    }
                }
            }
            List<ProfileColumn> profile = new ArrayList<>(columns.size());
            for (int index = 0; index < columns.size(); index++) {
                ColumnSpec column = columns.get(index);
                profile.add(new ProfileColumn(column.name(), column.knimeType(), rows - present[index],
                        distinct[index], min[index], max[index], mean[index], stddev[index],
                        exact[index]));
            }
            return new ProfileData(rows, profile);
        }
    }

    /**
     * Run one materialization under a timeout. On expiry the registered reads of the
     * workflow are interrupted (their connections closed) and the caller gets 408.
     */
    private <T> T bounded(String operation, long timeoutMillis, String workflowId,
                          Callable<T> task) throws SQLException {
        if (timeoutMillis <= 0) {
            try {
                return task.call();
            } catch (SQLException | RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new SQLException(failure);
            }
        }
        Future<T> future = READS.submit(task);
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timedOut) {
            interruptReads(workflowId);
            future.cancel(true);
            throw ApiException.timeout("query_timeout",
                    "the " + operation + " materialization timed out after " + timeoutMillis + " ms",
                    "narrow the workflow (filter or aggregate before the join) or raise "
                            + "DAP_QUERY_TIMEOUT_SEC");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SQLException sql) {
                throw sql;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new SQLException(cause);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for the " + operation, interrupted);
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
            } catch (SQLException failure) {
                // Cleanup stays best effort, but a leaked table must not disappear silently.
                System.err.println("dap-native: cannot drop table " + name + ": "
                        + failure.getMessage());
            }
        }
    }

    /**
     * Drop every table a workflow's readers materialized. Reader executors name their tables
     * {@code csv_<workflowId>_<nodeId>} with the id sanitized ('-' and ':' become '_'), so the
     * raw prefix pinned by the contract and the sanitized one are both matched.
     */
    public void dropTablesForWorkflow(String workflowId) {
        if (workflowId == null || workflowId.isEmpty()) {
            return;
        }
        dropPageTables(workflowId);
        Set<String> prefixes = new LinkedHashSet<>();
        prefixes.add("csv_" + workflowId + "_");
        prefixes.add("excel_" + workflowId + "_");
        prefixes.add("csv_" + sanitize(workflowId) + "_");
        prefixes.add("excel_" + sanitize(workflowId) + "_");
        prefixes.add("db_" + sanitize(workflowId) + "_");
        prefixes.add("files_" + sanitize(workflowId) + "_");
        // text_normalize loads a distinct-value mapping table per selected column.
        prefixes.add("textnorm_");
        // The in-memory statistics nodes (outlier methods, cluster, pca) materialize one
        // per-row result mapping table named stats_<workflow>_<node>.
        prefixes.add("stats_" + workflowId + "_");
        prefixes.add("stats_" + sanitize(workflowId) + "_");
        List<String> names = new ArrayList<>();
        try (Statement statement = base.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = 'main'")) {
            while (result.next()) {
                String name = result.getString(1);
                for (String prefix : prefixes) {
                    if (name.startsWith(prefix)) {
                        names.add(name);
                        break;
                    }
                }
            }
        } catch (SQLException failure) {
            // Best effort cleanup, but a leak must be visible in the engine log.
            System.err.println("dap-native: cannot list tables of workflow " + workflowId
                    + ": " + failure.getMessage());
            return;
        }
        dropTables(names);
    }

    /** Suffix of a DuckDB extension file, shared by the installer and the health listing. */
    public static final String EXTENSION_SUFFIX = ".duckdb_extension";

    /**
     * Install the named extensions into directory. Explicit and network-bound by design:
     * the engine itself never auto-installs (autoinstall_known_extensions=false).
     */
    public static void installExtensions(String directory, List<String> names) throws SQLException {
        if (directory == null || directory.isBlank()) {
            throw new SQLException("DAP_DUCKDB_EXTENSION_DIR is not configured");
        }
        if (names == null || names.isEmpty()) {
            throw new SQLException("no extension names given");
        }
        try {
            Files.createDirectories(Path.of(directory));
        } catch (IOException failure) {
            throw new SQLException("cannot create DuckDB extension directory '" + directory
                    + "': " + failure.getMessage(), failure);
        }
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("SET extension_directory=" + literal(directory));
            try {
                statement.execute("SET autoinstall_known_extensions=false");
            } catch (SQLException ignored) {
                // Older DuckDB builds have no such setting.
            }
            for (String name : names) {
                if (name == null || !name.matches("[A-Za-z0-9_]+")) {
                    throw new SQLException("invalid extension name '" + name + "'");
                }
                statement.execute("INSTALL " + name);
            }
        }
    }

    /** Base names of the extensions installed under directory (sorted, duplicates merged). */
    public static List<String> installedExtensions(String directory) {
        if (directory == null || directory.isBlank()) {
            return List.of();
        }
        Path root = Path.of(directory);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.walk(root, 4)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(EXTENSION_SUFFIX))
                    .map(name -> name.substring(0, name.length() - EXTENSION_SUFFIX.length()))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (IOException failure) {
            System.err.println("dap-native: cannot list DuckDB extensions in '" + directory
                    + "': " + failure.getMessage());
            return List.of();
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

    /**
     * Interrupt the in-flight /table and /profile reads of one workflow only. The reads
     * lock makes the cancel atomic with openRead: a read that was registered before this
     * call cannot slip past the iteration and keep running.
     */
    public void interruptReads(String workflowId) {
        if (workflowId == null) {
            return;
        }
        synchronized (readsLock) {
            for (Map.Entry<DuckDBConnection, Read> entry : reads.entrySet()) {
                if (workflowId.equals(entry.getValue().workflowId())
                        && reads.remove(entry.getKey()) != null) {
                    closeQuietly(entry.getKey());
                }
            }
        }
    }

    /** In-flight /table and /profile reads; diagnostics and tests. */
    public int activeReadCount() {
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
