package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.sql_query: one read-only SELECT/WITH statement over the tables of the
 * connected inputs. Inputs are exposed as CTEs named by the {@code inputs}
 * config array (port order), the user query is wrapped so its columns are
 * declared by {@code SELECT * FROM (<sql>) LIMIT 0}, and the row count is
 * capped by {@code limit}. DDL/DML, multiple statements and nested result
 * types are rejected before anything runs.
 */
public final class SqlQueryExecutor implements NodeExecutor {
    private static final int DEFAULT_LIMIT = 10_000;
    private static final int MAX_LIMIT = 1_000_000;

    /** Statements that would write, attach or reconfigure the engine. */
    private static final Set<String> DENIED = Set.of(
            "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "ATTACH", "DETACH",
            "COPY", "INSTALL", "LOAD", "PRAGMA", "SET", "EXPORT", "IMPORT", "CALL");

    @Override
    public String nodeId() {
        return "dap.sql_query";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        JsonNode config = context.config();
        String sql = validateReadOnly(NodeSql.requiredText(config, "sql"));
        int limit = limit(config);
        List<String> aliases = aliases(config, inputs);

        String analysisSql = (aliases.isEmpty() ? "" : inputCtes(aliases, inputs))
                + "SELECT * FROM (" + sql + "\n) AS dap_query LIMIT 0";
        List<ColumnSpec> columns;
        try {
            columns = context.db().describe(context.jobConnection(), analysisSql);
        } catch (SQLException failure) {
            throw new IllegalStateException("sql_query: cannot analyse the query: " + failure.getMessage(), failure);
        }
        List<String> ctes = new ArrayList<>(aliases.size() + 1);
        for (int index = 0; index < aliases.size(); index++) {
            ctes.add(NodeSql.quote(aliases.get(index)) + " AS (SELECT * FROM "
                    + inputs.get(index).asTable() + ")");
        }
        // A trailing line comment must not swallow the closing parenthesis of the CTE.
        ctes.add("dap_query AS (" + sql + "\n)");
        String planSql = "WITH " + String.join(", ", ctes) + " SELECT " + projection(columns)
                + " FROM dap_query LIMIT " + limit;
        return new LazyPlan(context.db(), planSql, columns);
    }

    /** Alias the input table by position so every declared column has a stable name. */
    private static String projection(List<ColumnSpec> columns) {
        StringJoiner joiner = new StringJoiner(", ");
        for (int index = 0; index < columns.size(); index++) {
            joiner.add("#" + (index + 1) + " AS " + NodeSql.quote(columns.get(index).name()));
        }
        return joiner.toString();
    }

    private static String inputCtes(List<String> aliases, List<LazyPlan> inputs) {
        if (aliases.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ", "WITH ", " ");
        for (int index = 0; index < aliases.size(); index++) {
            joiner.add(NodeSql.quote(aliases.get(index)) + " AS (SELECT * FROM "
                    + inputs.get(index).asTable() + ")");
        }
        return joiner.toString();
    }

    private static int limit(JsonNode config) {
        JsonNode node = config.get("limit");
        if (node == null || node.isNull()) {
            return DEFAULT_LIMIT;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("sql_query: 'limit' must be an integer, got " + node);
        }
        long value = node.longValue();
        if (value < 1 || value > MAX_LIMIT) {
            throw new IllegalArgumentException("sql_query: 'limit' must be between 1 and " + MAX_LIMIT
                    + ", got " + value);
        }
        return (int) value;
    }

    /** The {@code inputs} array names the connected input tables in port order. */
    private static List<String> aliases(JsonNode config, List<LazyPlan> inputs) {
        JsonNode node = config.get("inputs");
        List<String> aliases = new ArrayList<>();
        if (node != null && !node.isNull()) {
            if (!node.isArray()) {
                throw new IllegalArgumentException("sql_query: 'inputs' must be an array of node ids");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode item : node) {
                if (!item.isTextual() || item.textValue().isBlank()) {
                    throw new IllegalArgumentException("sql_query: 'inputs' must contain non-empty node ids");
                }
                String alias = item.textValue();
                if (!seen.add(alias)) {
                    throw new IllegalArgumentException("sql_query: duplicate input '" + alias + "'");
                }
                aliases.add(alias);
            }
        }
        int connected = 0;
        for (LazyPlan input : inputs) {
            if (input != null) {
                connected++;
            }
        }
        if (aliases.size() != connected) {
            throw new IllegalArgumentException("sql_query: 'inputs' names " + aliases.size()
                    + " tables but " + connected + " input ports are connected; name every connected input");
        }
        return aliases;
    }

    /**
     * Reject anything but one SELECT/WITH statement: a trailing semicolon is
     * allowed, any other statement separator, DDL/DML keyword or non-SELECT
     * start is not. Quoted strings, quoted identifiers and comments are skipped
     * so {@code 'a;b'} and {@code -- create} are not mistaken for SQL.
     */
    static String validateReadOnly(String raw) {
        String sql = LazyPlan.stripSemicolon(raw == null ? "" : raw);
        if (sql.isEmpty()) {
            throw notAllowed("'sql' is empty", "provide one SELECT or WITH statement");
        }
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipQuoted(sql, index, '\'');
                continue;
            }
            if (current == '"') {
                index = skipQuoted(sql, index, '"');
                continue;
            }
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < sql.length()
                        && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) {
                    index++;
                }
                index = Math.min(sql.length(), index + 2);
                continue;
            }
            if (current == ';') {
                throw notAllowed("only one statement is allowed",
                        "remove the statement after ';' and call sql_query once per query");
            }
            if (Character.isLetter(current) || current == '_') {
                word.setLength(0);
                while (index < sql.length()) {
                    char letter = sql.charAt(index);
                    if (!Character.isLetterOrDigit(letter) && letter != '_') {
                        break;
                    }
                    word.append(letter);
                    index++;
                }
                words.add(word.toString().toUpperCase(Locale.ROOT));
                continue;
            }
            index++;
        }
        if (words.isEmpty()) {
            throw notAllowed("no statement found", "provide one SELECT or WITH statement");
        }
        String first = words.get(0);
        if (!"SELECT".equals(first) && !"WITH".equals(first)) {
            throw notAllowed("only SELECT and WITH statements are allowed, got " + first,
                    "rewrite the query as a single SELECT or WITH statement");
        }
        for (String keyword : words) {
            if (DENIED.contains(keyword)) {
                throw notAllowed(keyword + " is not allowed in a read-only query",
                        "sql_query accepts SELECT/WITH only; remove the " + keyword + " statement");
            }
        }
        return sql;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return index;
    }

    private static ApiException notAllowed(String message, String hint) {
        return ApiException.badRequest("sql_not_allowed", "sql_query: " + message, hint);
    }
}
