package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.data_quality: one combined aggregate statement checks the input table and returns one
 * row per check ({@code long}) or one summary row ({@code summary}): check, column, status,
 * message, failed_rows, total_rows. Checks are not_null, unique, range, pattern, referential,
 * row_count and allowed_values; severity (error/warn) and fail_on (error/warn/never) decide
 * whether the node aborts the run.
 */
public final class DataQualityExecutor implements NodeExecutor {
    private static final String NODE = "data_quality";
    private static final Set<String> KINDS = Set.of("not_null", "unique", "range", "pattern",
            "referential", "row_count", "allowed_values");
    private static final Set<String> SEVERITIES = Set.of("error", "warn");
    private static final Set<String> FAIL_ON = Set.of("error", "warn", "never");
    private static final Set<String> OUTPUTS = Set.of("long", "summary");
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("check", "Utf8"),
            ColumnSpec.of("column", "Utf8"),
            ColumnSpec.of("status", "Utf8"),
            ColumnSpec.of("message", "Utf8"),
            ColumnSpec.of("failed_rows", "Int64"),
            ColumnSpec.of("total_rows", "Int64"));

    @Override
    public String nodeId() {
        return "dap.data_quality";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        LazyPlan reference = inputs.size() > 1 ? inputs.get(1) : null;
        JsonNode config = context.config();
        String failOn = choice(config, "fail_on", "error", FAIL_ON);
        String output = choice(config, "output", "long", OUTPUTS);
        List<Check> checks = checks(config, input.columns(), reference);

        Scan scan = scan(context, input, reference, checks);
        List<Result> results = new ArrayList<>();
        for (int index = 0; index < checks.size(); index++) {
            results.add(new Result(checks.get(index), scan.failedRows().get(index), scan.totalRows()));
        }
        abort(results, failOn);
        return output(context, output, results);
    }

    // ------------------------------------------------------------------ config

    private record Check(String kind, String column, JsonNode min, JsonNode max, String regex,
                         List<JsonNode> values, String severity) {
    }

    private record Result(Check check, long failedRows, long totalRows) {
        boolean passed() {
            return failedRows == 0;
        }

        String label() {
            return check.column() == null ? check.kind() : check.kind() + "(" + check.column() + ")";
        }
    }

    private static List<Check> checks(JsonNode config, List<ColumnSpec> columns, LazyPlan reference) {
        JsonNode node = config.get("checks");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(NODE + ": 'checks' must be a non-empty array");
        }
        List<Check> checks = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(NODE + ": every check must be an object");
            }
            String kind = NodeSql.requiredText(item, "kind");
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException(NODE + ": unknown check kind '" + kind
                        + "', expected one of not_null, unique, range, pattern, referential, "
                        + "row_count, allowed_values");
            }
            String column = null;
            if (kind.equals("row_count")) {
                if (item.hasNonNull("column")) {
                    throw new IllegalArgumentException(NODE + ": row_count check does not take a column");
                }
            } else {
                column = NodeSql.requiredText(item, "column");
                NodeSql.column(columns, column);
            }
            JsonNode min = bound(item, "min");
            JsonNode max = bound(item, "max");
            String regex = null;
            List<JsonNode> values = List.of();
            switch (kind) {
                case "range" -> {
                    if (min == null && max == null) {
                        throw new IllegalArgumentException(NODE + ": range check needs 'min' and/or 'max'");
                    }
                }
                case "pattern" -> regex = NodeSql.requiredText(item, "regex");
                case "allowed_values" -> values = values(item);
                case "referential" -> {
                    if (reference == null) {
                        throw new IllegalArgumentException(NODE
                                + ": referential check requires the Reference input (port 1)");
                    }
                    NodeSql.column(reference.columns(), column);
                }
                case "row_count" -> {
                    if (min == null && max == null) {
                        throw new IllegalArgumentException(NODE + ": row_count check needs 'min' and/or 'max'");
                    }
                    rowCountBound(min, "min");
                    rowCountBound(max, "max");
                }
                default -> {
                    // not_null and unique only need the column
                }
            }
            checks.add(new Check(kind, column, min, max, regex, values,
                    choice(item, "severity", "error", SEVERITIES)));
        }
        return checks;
    }

    private static JsonNode bound(JsonNode check, String field) {
        JsonNode value = check.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isValueNode()) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be a scalar value");
        }
        return value;
    }

    private static List<JsonNode> values(JsonNode check) {
        JsonNode node = check.get("values");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(NODE + ": allowed_values check needs a non-empty 'values' array");
        }
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isValueNode()) {
                throw new IllegalArgumentException(NODE + ": 'values' must contain scalar values");
            }
            values.add(item);
        }
        return values;
    }

    private static long rowCountBound(JsonNode value, String field) {
        if (value == null) {
            return -1;
        }
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(NODE + ": row_count '" + field + "' must be an integer");
        }
        long bound = value.longValue();
        if (bound < 0) {
            throw new IllegalArgumentException(NODE + ": row_count '" + field + "' must be >= 0");
        }
        return bound;
    }

    private static String choice(JsonNode node, String field, String fallback, Set<String> allowed) {
        String value = NodeSql.text(node, field, fallback);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be one of "
                    + String.join(", ", allowed.stream().sorted().toList()) + ", got '" + value + "'");
        }
        return value;
    }

    // ------------------------------------------------------------------ scan

    private record Scan(long totalRows, List<Long> failedRows) {
    }

    /**
     * Build one aggregate statement over the input: every data check is one SUM(CASE ...)
     * expression, so the input is scanned once instead of once per check.
     */
    private static Scan scan(NodeContext context, LazyPlan input, LazyPlan reference, List<Check> checks)
            throws Exception {
        StringJoiner selects = new StringJoiner(", ");
        selects.add("CAST(COUNT(*) AS BIGINT) AS \"total_rows\"");
        List<ColumnSpec> columns = new ArrayList<>(List.of(ColumnSpec.of("total_rows", "Int64")));
        StringBuilder windows = new StringBuilder();
        StringBuilder joins = new StringBuilder();
        List<Integer> aliases = new ArrayList<>();
        int ordinal = 0;
        int referential = 0;
        int unique = 0;
        for (Check check : checks) {
            if (check.kind().equals("row_count")) {
                aliases.add(-1);
                continue;
            }
            String expression;
            if (check.kind().equals("unique")) {
                // Window count over the same scan marks every row of a duplicated value, so
                // failed_rows is the number of rows that break uniqueness (NULL is one value).
                String alias = "dap_dup" + unique;
                windows.append(", COUNT(*) OVER (PARTITION BY src.").append(NodeSql.quote(check.column()))
                        .append(") AS \"").append(alias).append('"');
                expression = "COALESCE(SUM(CASE WHEN t.\"" + alias + "\" > 1 THEN 1 ELSE 0 END), 0)";
                unique++;
            } else if (check.kind().equals("referential")) {
                String alias = "ref" + referential;
                joins.append(" LEFT JOIN (SELECT DISTINCT ").append(NodeSql.quote(check.column()))
                        .append(" AS ").append(alias).append(" FROM ").append(reference.asTable())
                        .append(") AS r").append(referential).append(" ON t.")
                        .append(NodeSql.quote(check.column())).append(" = r").append(referential)
                        .append('.').append(alias);
                expression = "COALESCE(SUM(CASE WHEN t." + NodeSql.quote(check.column())
                        + " IS NOT NULL AND r" + referential + "." + alias
                        + " IS NULL THEN 1 ELSE 0 END), 0)";
                referential++;
            } else {
                expression = expression(check, input.columns());
            }
            selects.add("CAST(" + expression + " AS BIGINT) AS \"c" + ordinal + "\"");
            columns.add(ColumnSpec.of("c" + ordinal, "Int64"));
            aliases.add(ordinal);
            ordinal++;
        }
        String sql = "SELECT " + selects + " FROM (SELECT src.*" + windows + " FROM "
                + input.asTable() + " AS src) AS t" + joins;
        LazyPlan plan = new LazyPlan(context.db(), sql, columns);
        TableData data = plan.preview(1, context.workflowId(), context.nodeId());
        List<String> row = data.data().isEmpty() ? List.of() : data.data().get(0);
        long totalRows = value(row, 0);
        List<Long> failedRows = new ArrayList<>();
        for (int index = 0; index < checks.size(); index++) {
            int alias = aliases.get(index);
            Check check = checks.get(index);
            failedRows.add(alias < 0 ? rowCountFailure(check, totalRows) : value(row, alias + 1));
        }
        return new Scan(totalRows, failedRows);
    }

    private static String expression(Check check, List<ColumnSpec> columns) {
        ColumnSpec column = NodeSql.column(columns, check.column());
        String quoted = "t." + NodeSql.quote(check.column());
        return switch (check.kind()) {
            case "not_null" -> "COUNT(*) - COUNT(" + quoted + ")";
            case "range" -> "COALESCE(SUM(CASE WHEN " + quoted + " IS NOT NULL AND ("
                    + rangeCondition(quoted, check, column) + ") THEN 1 ELSE 0 END), 0)";
            case "pattern" -> "COALESCE(SUM(CASE WHEN " + quoted + " IS NOT NULL AND NOT regexp_matches("
                    + "CAST(" + quoted + " AS VARCHAR), " + NodeSql.literal(check.regex())
                    + ") THEN 1 ELSE 0 END), 0)";
            case "allowed_values" -> "COALESCE(SUM(CASE WHEN " + quoted
                    + " IS NOT NULL AND " + quoted + " NOT IN (" + literals(column, check.values())
                    + ") THEN 1 ELSE 0 END), 0)";
            default -> throw new IllegalArgumentException(NODE + ": unsupported check kind '" + check.kind() + "'");
        };
    }

    private static String rangeCondition(String quoted, Check check, ColumnSpec column) {
        if (check.min() != null && check.max() != null) {
            return quoted + " < " + NodeSql.literal(column, check.min()) + " OR " + quoted + " > "
                    + NodeSql.literal(column, check.max());
        }
        if (check.min() != null) {
            return quoted + " < " + NodeSql.literal(column, check.min());
        }
        return quoted + " > " + NodeSql.literal(column, check.max());
    }

    private static String literals(ColumnSpec column, List<JsonNode> values) {
        StringJoiner literals = new StringJoiner(", ");
        for (JsonNode value : values) {
            literals.add(NodeSql.literal(column, value));
        }
        return literals.toString();
    }

    private static long rowCountFailure(Check check, long totalRows) {
        boolean below = check.min() != null && totalRows < check.min().longValue();
        boolean above = check.max() != null && totalRows > check.max().longValue();
        return below || above ? totalRows : 0;
    }

    private static long value(List<String> row, int index) {
        if (index >= row.size() || row.get(index) == null) {
            return 0;
        }
        return Long.parseLong(row.get(index));
    }

    // ------------------------------------------------------------------ output

    private static void abort(List<Result> results, String failOn) {
        if (failOn.equals("never")) {
            return;
        }
        List<String> failing = new ArrayList<>();
        for (Result result : results) {
            if (result.passed()) {
                continue;
            }
            if (failOn.equals("warn") || result.check().severity().equals("error")) {
                failing.add(result.label());
            }
        }
        if (!failing.isEmpty()) {
            throw new IllegalStateException(NODE + ": " + failing.size() + " check"
                    + (failing.size() == 1 ? "" : "s") + " failed: " + String.join(", ", failing));
        }
    }

    private static LazyPlan output(NodeContext context, String output, List<Result> results) {
        List<String> rows = new ArrayList<>();
        if (output.equals("summary")) {
            long passed = results.stream().filter(Result::passed).count();
            long failed = results.size() - passed;
            long failedRows = results.stream().mapToLong(Result::failedRows).sum();
            long totalRows = results.isEmpty() ? 0 : results.get(0).totalRows();
            rows.add(valuesRow("summary", null, failed == 0 ? "PASS" : "FAIL",
                    results.size() + " checks: " + passed + " passed, " + failed + " failed",
                    failedRows, totalRows));
        } else {
            for (Result result : results) {
                rows.add(valuesRow(result.check().kind(), result.check().column(),
                        result.passed() ? "PASS" : "FAIL",
                        message(result), result.failedRows(), result.totalRows()));
            }
        }
        String sql = "SELECT * FROM (VALUES " + String.join(", ", rows)
                + ") AS dq(\"check\", \"column\", \"status\", \"message\", \"failed_rows\", \"total_rows\")";
        return new LazyPlan(context.db(), sql, COLUMNS);
    }

    private static String valuesRow(String check, String column, String status, String message,
                                    long failedRows, long totalRows) {
        return "(CAST(" + NodeSql.literal(check) + " AS VARCHAR), "
                + (column == null ? "CAST(NULL AS VARCHAR)" : "CAST(" + NodeSql.literal(column) + " AS VARCHAR)")
                + ", CAST(" + NodeSql.literal(status) + " AS VARCHAR), CAST("
                + NodeSql.literal(message) + " AS VARCHAR), " + failedRows + ", " + totalRows + ")";
    }

    private static String message(Result result) {
        if (result.passed()) {
            return "ok";
        }
        Check check = result.check();
        long failed = result.failedRows();
        return switch (check.kind()) {
            case "not_null" -> rows(failed) + " with a null value";
            case "unique" -> rows(failed) + " sharing a duplicated value";
            case "range" -> rows(failed) + " " + outside(check.min(), check.max());
            case "pattern" -> rows(failed) + " not matching the pattern";
            case "allowed_values" -> rows(failed) + " not in the allowed set";
            case "referential" -> rows(failed) + " missing from the reference table";
            default -> "row count " + result.totalRows() + " " + outside(check.min(), check.max());
        };
    }

    private static String rows(long count) {
        return count + (count == 1 ? " row" : " rows");
    }

    private static String outside(JsonNode min, JsonNode max) {
        if (min != null && max != null) {
            return "outside [" + min.asText() + ", " + max.asText() + "]";
        }
        if (min != null) {
            return "below " + min.asText();
        }
        return "above " + max.asText();
    }
}
