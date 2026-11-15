package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.joiner: joins two inputs on key columns (inner/left/right/full/semi/anti) with optional
 * merged keys, as an asof join on a time column, or as a cross join.
 */
public final class JoinerExecutor implements NodeExecutor {
    /** KNIME Joiner3 duplicateHandling=APPEND_SUFFIX with suffix " (Right)". */
    private static final String RIGHT_SUFFIX = " (Right)";

    @Override
    public String nodeId() {
        return "dap.joiner";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.get(0) == null) {
            throw new IllegalArgumentException("joiner: requires a connected left input");
        }
        if (inputs.size() < 2 || inputs.get(1) == null) {
            throw new IllegalArgumentException("joiner: requires a connected right input");
        }
        LazyPlan left = inputs.get(0);
        LazyPlan right = inputs.get(1);
        JsonNode config = context.config();
        String how = NodeSql.text(config, "how", "inner");
        if ("asof".equals(how)) {
            return asof(context, left, right, config);
        }
        if ("cross".equals(how)) {
            return cross(context, left, right);
        }
        String join = switch (how) {
            case "inner" -> "INNER JOIN";
            case "left" -> "LEFT JOIN";
            case "right" -> "RIGHT JOIN";
            case "full" -> "FULL OUTER JOIN";
            case "semi" -> "SEMI JOIN";
            case "anti" -> "ANTI JOIN";
            default -> throw new IllegalArgumentException("joiner: unsupported how '" + how
                    + "' (expected inner, left, right, full, semi, anti, asof, cross)");
        };
        // Semi and anti joins only decide which left rows survive: the output is the left schema.
        boolean leftOnly = "semi".equals(how) || "anti".equals(how);
        List<String> leftKeys = keys(config, "left_keys", "left", left.columns());
        List<String> rightKeys = keys(config, "right_keys", "right", right.columns());
        if (leftKeys.size() != rightKeys.size()) {
            throw new IllegalArgumentException("joiner: 'left_keys' and 'right_keys' must have the same length, got "
                    + leftKeys.size() + " and " + rightKeys.size());
        }
        boolean mergeKeys = NodeSql.bool(config, "merge_keys", true);

        StringJoiner on = new StringJoiner(" AND ");
        for (int i = 0; i < leftKeys.size(); i++) {
            on.add("l." + NodeSql.quote(leftKeys.get(i)) + " = r." + NodeSql.quote(rightKeys.get(i)));
        }
        if (leftOnly) {
            StringJoiner projection = new StringJoiner(", ");
            for (ColumnSpec column : left.columns()) {
                projection.add("l." + NodeSql.quote(column.name()) + " AS " + NodeSql.quote(column.name()));
            }
            String sql = "SELECT " + projection
                    + " FROM " + left.asTable() + " AS l"
                    + " " + join + " " + right.asTable() + " AS r"
                    + " ON " + on;
            return new LazyPlan(context.db(), sql, new ArrayList<>(left.columns()));
        }

        List<ColumnSpec> output = new ArrayList<>(left.columns());
        Set<String> outputNames = new HashSet<>();
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : left.columns()) {
            // A merged key is one output column: for a right-only row the left key is NULL,
            // so take the right value instead of dropping it with the right key column.
            if (mergeKeys && leftKeys.contains(column.name())) {
                int index = leftKeys.indexOf(column.name());
                projection.add("COALESCE(l." + NodeSql.quote(column.name()) + ", r."
                        + NodeSql.quote(rightKeys.get(index)) + ") AS " + NodeSql.quote(column.name()));
            } else {
                projection.add("l." + NodeSql.quote(column.name()));
            }
            outputNames.add(column.name());
        }
        Set<String> rightKeyNames = new HashSet<>(rightKeys);
        for (ColumnSpec column : right.columns()) {
            if (mergeKeys && rightKeyNames.contains(column.name())) {
                continue;
            }
            String name = outputNames.contains(column.name())
                    ? column.name() + RIGHT_SUFFIX
                    : column.name();
            projection.add("r." + NodeSql.quote(column.name()) + " AS " + NodeSql.quote(name));
            outputNames.add(name);
            output.add(new ColumnSpec(name, column.arrowType(), column.knimeType()));
        }

        String sql = "SELECT " + projection
                + " FROM " + left.asTable() + " AS l"
                + " " + join + " " + right.asTable() + " AS r"
                + " ON " + on;
        return new LazyPlan(context.db(), sql, output);
    }

    /**
     * ASOF LEFT JOIN: every left row gets the nearest right row whose time is before (>=)
     * or after (<=) its own time; optional equality keys restrict the match to one group.
     */
    private static LazyPlan asof(NodeContext context, LazyPlan left, LazyPlan right, JsonNode config) {
        String leftTime = timeColumn(config, "left_time", left.columns(), "left");
        String rightTime = timeColumn(config, "right_time", right.columns(), "right");
        String inequality = NodeSql.text(config, "asof_inequality", ">=").strip();
        if (!">=".equals(inequality) && !"<=".equals(inequality)) {
            throw new IllegalArgumentException("joiner: 'asof_inequality' must be >= or <=, got '"
                    + inequality + "'");
        }
        StringJoiner on = new StringJoiner(" AND ");
        List<String> leftKeys = optionalKeys(config, "left_keys");
        if (!leftKeys.isEmpty()) {
            List<String> rightKeys = keys(config, "right_keys", "right", right.columns());
            List<String> checkedLeft = keys(config, "left_keys", "left", left.columns());
            if (checkedLeft.size() != rightKeys.size()) {
                throw new IllegalArgumentException("joiner: 'left_keys' and 'right_keys' must have "
                        + "the same length, got " + checkedLeft.size() + " and " + rightKeys.size());
            }
            for (int index = 0; index < checkedLeft.size(); index++) {
                on.add("l." + NodeSql.quote(checkedLeft.get(index)) + " = r."
                        + NodeSql.quote(rightKeys.get(index)));
            }
        }
        // DuckDB requires the inequality to be the last ASOF condition.
        on.add("l." + NodeSql.quote(leftTime) + " " + inequality + " r." + NodeSql.quote(rightTime));

        List<ColumnSpec> output = new ArrayList<>();
        Set<String> outputNames = new HashSet<>();
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : left.columns()) {
            projection.add("l." + NodeSql.quote(column.name()));
            output.add(column);
            outputNames.add(column.name());
        }
        appendRight(projection, output, outputNames, right, List.of());
        String sql = "SELECT " + projection
                + " FROM " + left.asTable() + " AS l"
                + " ASOF LEFT JOIN " + right.asTable() + " AS r"
                + " ON " + on;
        return new LazyPlan(context.db(), sql, output);
    }

    /** CROSS JOIN: every combination of the two inputs; keys are not used. */
    private static LazyPlan cross(NodeContext context, LazyPlan left, LazyPlan right) {
        List<ColumnSpec> output = new ArrayList<>();
        Set<String> outputNames = new HashSet<>();
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : left.columns()) {
            projection.add("l." + NodeSql.quote(column.name()));
            output.add(column);
            outputNames.add(column.name());
        }
        appendRight(projection, output, outputNames, right, List.of());
        String sql = "SELECT " + projection
                + " FROM " + left.asTable() + " AS l"
                + " CROSS JOIN " + right.asTable() + " AS r";
        return new LazyPlan(context.db(), sql, output);
    }

    /** Right columns with the " (Right)" suffix on a name clash; names in skipNames are merged. */
    private static void appendRight(StringJoiner projection, List<ColumnSpec> output,
                                    Set<String> outputNames, LazyPlan right, List<String> skipNames) {
        for (ColumnSpec column : right.columns()) {
            if (skipNames.contains(column.name())) {
                continue;
            }
            String name = outputNames.contains(column.name())
                    ? column.name() + RIGHT_SUFFIX
                    : column.name();
            projection.add("r." + NodeSql.quote(column.name()) + " AS " + NodeSql.quote(name));
            outputNames.add(name);
            output.add(new ColumnSpec(name, column.arrowType(), column.knimeType()));
        }
    }

    private static String timeColumn(JsonNode config, String field, List<ColumnSpec> columns,
                                     String side) {
        String name = NodeSql.requiredText(config, field);
        for (ColumnSpec column : columns) {
            if (column.name().equals(name)) {
                return name;
            }
        }
        throw new IllegalArgumentException("joiner: unknown " + side + " time column '" + name + "'");
    }

    /** The optional equality keys of an asof join; absent or empty means "no equality keys". */
    private static List<String> optionalKeys(JsonNode config, String field) {
        JsonNode node = config == null ? null : config.get(field);
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.textValue().isBlank()) {
                names.add(item.textValue());
            }
        }
        return names;
    }

    private static List<String> keys(JsonNode config, String field, String side, List<ColumnSpec> columns) {
        JsonNode node = config == null ? null : config.get(field);
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("joiner: '" + field + "' must be a non-empty array of column names");
        }
        List<String> keys = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("joiner: '" + field + "' must contain non-empty strings");
            }
            String key = item.textValue();
            boolean known = false;
            for (ColumnSpec column : columns) {
                if (column.name().equals(key)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new IllegalArgumentException("joiner: unknown " + side + " key column '" + key + "'");
            }
            keys.add(key);
        }
        return keys;
    }
}
