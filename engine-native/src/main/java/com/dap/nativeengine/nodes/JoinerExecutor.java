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

/** dap.joiner: Joins two inputs on key columns (inner/left/right) with optional merged keys. */
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
        String join = switch (how) {
            case "inner" -> "INNER JOIN";
            case "left" -> "LEFT JOIN";
            case "right" -> "RIGHT JOIN";
            default -> throw new IllegalArgumentException(
                    "joiner: unsupported how '" + how + "' (expected inner, left, right)");
        };
        List<String> leftKeys = keys(config, "left_keys", "left", left.columns());
        List<String> rightKeys = keys(config, "right_keys", "right", right.columns());
        if (leftKeys.size() != rightKeys.size()) {
            throw new IllegalArgumentException("joiner: 'left_keys' and 'right_keys' must have the same length, got "
                    + leftKeys.size() + " and " + rightKeys.size());
        }
        boolean mergeKeys = NodeSql.bool(config, "merge_keys", true);

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

        StringJoiner on = new StringJoiner(" AND ");
        for (int i = 0; i < leftKeys.size(); i++) {
            on.add("l." + NodeSql.quote(leftKeys.get(i)) + " = r." + NodeSql.quote(rightKeys.get(i)));
        }
        String sql = "SELECT " + projection
                + " FROM " + left.asTable() + " AS l"
                + " " + join + " " + right.asTable() + " AS r"
                + " ON " + on;
        return new LazyPlan(context.db(), sql, output);
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
