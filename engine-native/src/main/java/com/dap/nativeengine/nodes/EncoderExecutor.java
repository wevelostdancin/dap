package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** dap.encoder: label (dense rank) or one-hot encoding of a categorical column. */
public final class EncoderExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.encoder";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        ColumnSpec column = NodeSql.column(input.columns(), NodeSql.requiredText(config, "column"));
        String output = NodeSql.requiredText(config, "output");
        String method = NodeSql.requiredText(config, "method").toLowerCase(Locale.ROOT);
        return switch (method) {
            case "label" -> {
                TransformSupport.requireNewName(input.columns(), output, "encoder");
                String expression = "CAST(DENSE_RANK() OVER (ORDER BY " + NodeSql.quote(column.name())
                        + ") AS BIGINT)";
                yield TransformSupport.append(input,
                        List.of(expression + " AS " + NodeSql.quote(output)),
                        List.of(ColumnSpec.of(output, "Int64")));
            }
            case "onehot" -> onehot(input, config, column, output);
            default -> throw new IllegalArgumentException("encoder: unknown method '" + method
                    + "' (expected label, onehot)");
        };
    }

    /** One Int64 0/1 column per configured category; the schema comes from config, not the data. */
    private static LazyPlan onehot(LazyPlan input, JsonNode config, ColumnSpec column, String output) {
        JsonNode categories = config.get("categories");
        if (categories == null || categories.isNull() || !categories.isArray() || categories.isEmpty()) {
            throw new IllegalArgumentException("encoder: method 'onehot' requires a non-empty 'categories' array");
        }
        Set<String> taken = new LinkedHashSet<>();
        for (ColumnSpec existing : input.columns()) {
            taken.add(existing.name());
        }
        Set<String> seen = new HashSet<>();
        String quoted = NodeSql.quote(column.name());
        List<String> expressions = new ArrayList<>(categories.size());
        List<ColumnSpec> added = new ArrayList<>(categories.size());
        for (int i = 0; i < categories.size(); i++) {
            JsonNode category = categories.get(i);
            int position = i + 1;
            if (category == null || category.isNull() || category.isContainerNode()) {
                throw new IllegalArgumentException("encoder: category " + position + " must be a non-null scalar");
            }
            String key = category.asText();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("encoder: duplicate category '" + key + "'");
            }
            String name = output + "_" + key;
            if (!taken.add(name)) {
                throw new IllegalArgumentException("encoder: output '" + name
                        + "' duplicates an existing column");
            }
            String literal;
            try {
                literal = NodeSql.literal(column, category);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("encoder: invalid category '" + key + "' for column '"
                        + column.name() + "': " + failure.getMessage(), failure);
            }
            String expression = "CAST(CASE WHEN " + quoted + " = " + literal
                    + " THEN 1 ELSE 0 END AS BIGINT)";
            expressions.add(expression + " AS " + NodeSql.quote(name));
            added.add(ColumnSpec.of(name, "Int64"));
        }
        return TransformSupport.append(input, expressions, added);
    }
}
