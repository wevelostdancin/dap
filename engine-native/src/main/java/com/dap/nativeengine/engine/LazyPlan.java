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

    public ProfileData profile() throws SQLException {
        return session.profile(sql, columns);
    }

    public long count() throws SQLException {
        return session.count(sql);
    }

    /** Wrap this plan in a subquery usable as a table expression. */
    public String asTable() {
        return "(" + stripSemicolon(sql) + ")";
    }

    static String stripSemicolon(String sql) {
        String value = sql.strip();
        while (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).strip();
        }
        return value;
    }
}
