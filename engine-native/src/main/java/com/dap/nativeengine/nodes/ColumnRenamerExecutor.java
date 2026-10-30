package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** dap.column_renamer: rename columns in place with a lazy projection; no data is copied. */
public final class ColumnRenamerExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.column_renamer";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode renames = context.config().get("renames");
        if (renames == null || renames.isNull()) {
            throw new IllegalArgumentException("column_renamer: config field 'renames' is required");
        }
        if (!renames.isArray()) {
            throw new IllegalArgumentException("column_renamer: 'renames' must be an array");
        }
        if (renames.isEmpty()) {
            return input;
        }

        Map<String, String> bySource = new LinkedHashMap<>();
        for (JsonNode rename : renames) {
            if (!rename.isObject()) {
                throw new IllegalArgumentException("column_renamer: each rename must be an object with 'from' and 'to'");
            }
            String from = name(rename, "from");
            String to = name(rename, "to");
            if (bySource.putIfAbsent(from, to) != null) {
                throw new IllegalArgumentException("column_renamer: duplicate source column '" + from + "'");
            }
        }

        Set<String> inputNames = new LinkedHashSet<>();
        for (ColumnSpec column : input.columns()) {
            inputNames.add(column.name());
        }
        for (String from : bySource.keySet()) {
            NodeSql.column(input.columns(), from);
        }
        Set<String> seenTargets = new LinkedHashSet<>();
        for (String to : bySource.values()) {
            if (!seenTargets.add(to)) {
                throw new IllegalArgumentException("column_renamer: duplicate target column '" + to + "'");
            }
            if (inputNames.contains(to) && !bySource.containsKey(to)) {
                throw new IllegalArgumentException("column_renamer: target column '" + to
                        + "' collides with an existing column");
            }
        }

        List<ColumnSpec> output = new ArrayList<>();
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : input.columns()) {
            String target = bySource.get(column.name());
            output.add(target == null ? column : new ColumnSpec(target, column.arrowType(), column.knimeType()));
            projection.add(target == null ? NodeSql.quote(column.name())
                    : NodeSql.quote(column.name()) + " AS " + NodeSql.quote(target));
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, output);
    }

    private static String name(JsonNode rename, String field) {
        JsonNode value = rename.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("column_renamer: '" + field + "' must be a non-empty string");
        }
        return value.textValue();
    }
}
