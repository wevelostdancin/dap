package com.dap.nativeengine.engine;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * A lazily evaluated table: SQL text over tables registered in {@link DuckDbSession}.
 * The plan is built when a workflow runs; data is read only by /table and /profile.
 */
public final class LazyPlan {
    private final DuckDbSession session;
    private final String sql;
    private final List<ColumnSpec> columns;

    public LazyPlan(DuckDbSession session, String sql, List<ColumnSpec> columns) {
        this.session = Objects.requireNonNull(session, "session");
        this.sql = Objects.requireNonNull(sql, "sql");
        this.columns = List.copyOf(columns);
    }

    public String sql() {
        return sql;
    }

    public List<ColumnSpec> columns() {
        return columns;
    }

    public DuckDbSession session() {
        return session;
    }

    public TableData preview(int limit) throws SQLException {
        return session.preview(sql, columns, limit);
    }

    /** Preview registered against its workflow/node so a job cancel can interrupt it. */
    public TableData preview(int limit, String workflowId, String nodeId) throws SQLException {
        return session.preview(sql, columns, limit, workflowId, nodeId);
    }

    /** Bounded preview: fails with 408 query_timeout after timeoutMillis. */
    public TableData preview(int limit, long timeoutMillis, String workflowId, String nodeId)
            throws SQLException {
        return session.preview(sql, columns, limit, timeoutMillis, workflowId, nodeId);
    }

    public ProfileData profile() throws SQLException {
        return session.profile(sql, columns);
    }

    /** Profile registered against its workflow/node so a job cancel can interrupt it. */
    public ProfileData profile(String workflowId, String nodeId) throws SQLException {
        return session.profile(sql, columns, workflowId, nodeId);
    }

    /** Bounded profile: fails with 408 query_timeout after timeoutMillis. */
    public ProfileData profile(long timeoutMillis, String workflowId, String nodeId)
            throws SQLException {
        return session.profile(sql, columns, timeoutMillis, workflowId, nodeId);
    }

    public long count() throws SQLException {
        return session.count(sql);
    }

    /** One page of this plan; {@code rows} is the total count. */
    public TableData page(int limit, int offset, long timeoutMillis, String workflowId, String nodeId)
            throws SQLException {
        return session.page(sql, columns, limit, offset, timeoutMillis, workflowId, nodeId);
    }

    /**
     * One page of an already filtered/projected variant of this plan (the /table query
     * parameters), reported with the projected columns.
     */
    public TableData page(String pageSql, List<ColumnSpec> pageColumns, int limit, int offset,
                          long timeoutMillis, String workflowId, String nodeId) throws SQLException {
        return session.page(pageSql, List.copyOf(pageColumns), limit, offset, timeoutMillis,
                workflowId, nodeId);
    }

    /** Physical plan of this plan as DuckDB JSON (EXPLAIN FORMAT JSON, no execution). */
    public String explainJson() throws SQLException {
        return session.explainJson(sql);
    }

    /** Wrap this plan in a subquery usable as a table expression. */
    public String asTable() {
        return "(" + stripSemicolon(sql) + ")";
    }

    public static String stripSemicolon(String sql) {
        String value = sql.strip();
        while (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        return value;
    }
}
