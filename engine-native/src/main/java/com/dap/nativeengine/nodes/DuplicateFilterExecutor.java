package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/** dap.duplicate_filter: keep distinct rows, or one arbitrary row per key. */
public final class DuplicateFilterExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.duplicate_filter";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode keys = context.config().get("keys");
        List<String> partition = new ArrayList<>();
        if (keys != null && !keys.isNull()) {
            if (!keys.isArray()) {
                throw new IllegalArgumentException("duplicate_filter: 'keys' must be an array of column names");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode key : keys) {
                if (!key.isTextual() || key.textValue().isBlank()) {
                    throw new IllegalArgumentException(
                            "duplicate_filter: 'keys' must contain non-empty column names");
                }
                String name = key.textValue();
                NodeSql.column(input.columns(), name);
                if (seen.add(name)) {
                    partition.add(name);
                }
            }
        }

        if (partition.isEmpty()) {
            String sql = "SELECT DISTINCT * FROM " + input.asTable() + " AS dap_input";
            return new LazyPlan(context.db(), sql, input.columns());
        }

        StringJoiner keysSql = new StringJoiner(", ");
        for (String name : partition) {
            keysSql.add(NodeSql.quote(name));
        }
        String sql = "SELECT * FROM " + input.asTable() + " AS dap_input QUALIFY ROW_NUMBER() OVER (PARTITION BY "
                + keysSql + ") = 1";
        return new LazyPlan(context.db(), sql, input.columns());
    }
}
