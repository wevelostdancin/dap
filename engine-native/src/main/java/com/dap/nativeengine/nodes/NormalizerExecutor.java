package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** dap.normalizer: min-max or z-score normalisation of numeric columns into appended Float64 columns. */
public final class NormalizerExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.normalizer";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        JsonNode columns = config.get("columns");
        if (columns == null || columns.isNull() || !columns.isArray() || columns.isEmpty()) {
            throw new IllegalArgumentException("normalizer: 'columns' must be a non-empty array of {column,output}");
        }
        String method = NodeSql.requiredText(config, "method").toLowerCase(Locale.ROOT);
        if (!"minmax".equals(method) && !"zscore".equals(method)) {
            throw new IllegalArgumentException("normalizer: unknown method '" + method
                    + "' (expected minmax, zscore)");
        }

        Set<String> taken = new HashSet<>();
        for (ColumnSpec existing : input.columns()) {
            taken.add(existing.name());
        }
        String table = input.asTable();
        List<String> expressions = new ArrayList<>(columns.size());
        List<ColumnSpec> added = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            JsonNode item = columns.get(i);
            int position = i + 1;
            if (!item.isObject()) {
                throw new IllegalArgumentException("normalizer: column " + position
                        + " must be an object with 'column' and optional 'output'");
            }
            String name = text(item, "column", position);
            ColumnSpec column = NodeSql.column(input.columns(), name);
            if (!column.numeric()) {
                throw new IllegalArgumentException("normalizer: column '" + name
                        + "' must be numeric, got " + column.arrowType());
            }
            String output = NodeSql.text(item, "output", name + "_norm");
            if (output.isBlank()) {
                throw new IllegalArgumentException("normalizer: column " + position
                        + " has a blank 'output' name");
            }
            if (!taken.add(output)) {
                throw new IllegalArgumentException("normalizer: output '" + output
                        + "' duplicates an existing column");
            }
            String quoted = NodeSql.quote(name);
            String expression = "minmax".equals(method)
                    ? "((" + quoted + " - (SELECT MIN(" + quoted + ") FROM " + table + " AS dap_min))"
                        + " / NULLIF((SELECT MAX(" + quoted + ") FROM " + table + " AS dap_max)"
                        + " - (SELECT MIN(" + quoted + ") FROM " + table + " AS dap_min_2), 0))"
                    : "((" + quoted + " - (SELECT AVG(" + quoted + ") FROM " + table + " AS dap_mean))"
                        + " / NULLIF((SELECT STDDEV(" + quoted + ") FROM " + table + " AS dap_stddev), 0))";
            expressions.add("CAST(" + expression + " AS DOUBLE) AS " + NodeSql.quote(output));
            added.add(ColumnSpec.of(output, "Float64"));
        }
        return TransformSupport.append(input, expressions, added);
    }

    private static String text(JsonNode item, String field, int position) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("normalizer: column " + position
                    + " requires a non-empty '" + field + "' string");
        }
        return value.textValue();
    }
}
