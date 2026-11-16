package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.io.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
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
        // File literals are checked after the syntax validation: a read_*() or FROM '...'
        // argument must stay inside the path allowlist (spec §8).
        PathGuard.shared().checkSql(sql);
        int limit = limit(config);
        List<String> aliases = aliases(config, inputs, context.inputNodeIds());

        List<String> ctes = new ArrayList<>(aliases.size() + 1);
        for (int index = 0; index < aliases.size(); index++) {
            String alias = aliases.get(index);
            if (alias == null) {
                continue;
            }
            ctes.add(NodeSql.quote(alias) + " AS (SELECT * FROM " + inputs.get(index).asTable() + ")");
        }
        String analysisSql = (ctes.isEmpty() ? "" : "WITH " + String.join(", ", ctes) + " ")
                + "SELECT * FROM (" + sql + "\n) AS dap_query LIMIT 0";
        List<ColumnSpec> columns;
        try {
            columns = context.db().describe(context.jobConnection(), analysisSql);
        } catch (SQLException failure) {
            throw new IllegalStateException("sql_query: cannot analyse the query: " + failure.getMessage(), failure);
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

    /**
     * One alias per input port (null for an unconnected port). When 'inputs' is omitted
     * the connected source node ids are used, so {@code FROM "1:1"} just works. An
     * explicit 'inputs' is positional: names for every connected port in port order, and
     * a source node id is a valid name. Aliases are case-insensitive (DuckDB CTE rules).
     */
    private static List<String> aliases(JsonNode config, List<LazyPlan> inputs, List<String> inputNodeIds) {
        List<String> aliases = new ArrayList<>(java.util.Collections.nCopies(inputs.size(), null));
        List<Integer> connected = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            if (inputs.get(index) != null) {
                connected.add(index);
            }
        }
        JsonNode node = config.get("inputs");
        if (node == null || node.isNull()) {
            Set<String> seen = new HashSet<>();
            for (int index : connected) {
                String id = index < inputNodeIds.size() ? inputNodeIds.get(index) : null;
                if (id == null) {
                    throw new IllegalArgumentException("sql_query: cannot derive an alias for input port "
                            + index + "; pass 'inputs' explicitly");
                }
                if (seen.contains(id.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("sql_query: source '" + id
                            + "' is connected to more than one input port; pass 'inputs' explicitly");
                }
                checkAlias(id, seen);
                aliases.set(index, id);
            }
            return aliases;
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("sql_query: 'inputs' must be an array of aliases");
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("sql_query: 'inputs' must contain non-empty aliases");
            }
            String name = item.textValue();
            checkAlias(name, seen);
            names.add(name);
        }
        if (names.size() != connected.size()) {
            throw new IllegalArgumentException("sql_query: 'inputs' names " + names.size()
                    + " tables but " + connected.size()
                    + " input ports are connected; name every connected input in port order");
        }
        for (int index = 0; index < connected.size(); index++) {
            aliases.set(connected.get(index), names.get(index));
        }
        return aliases;
    }

    /** Reject duplicate aliases (case-insensitive) and the reserved wrapper name. */
    private static void checkAlias(String alias, Set<String> seen) {
        if ("dap_query".equalsIgnoreCase(alias)) {
            throw new IllegalArgumentException("sql_query: alias '" + alias
                    + "' is reserved for the query wrapper; choose another name");
        }
        if (!seen.add(alias.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("sql_query: duplicate input alias '" + alias
                    + "' (aliases are case-insensitive)");
        }
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
