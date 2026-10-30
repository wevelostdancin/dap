package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TypeMap;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** dap.math_formula: Adds columns computed by SQL expressions over the input columns. */
public final class MathFormulaExecutor implements NodeExecutor {
    private static final String INFERENCE_ALIAS = "dap_output_value";

    @Override
    public String nodeId() {
        return "dap.math_formula";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        List<Output> outputs = outputs(context.config());
        for (Output output : outputs) {
            for (ColumnSpec column : input.columns()) {
                if (column.name().equals(output.name())) {
                    throw new IllegalArgumentException("math_formula: output '" + output.name()
                            + "' duplicates input column '" + column.name() + "'");
                }
            }
        }
        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        for (int i = 0; i < outputs.size(); i++) {
            Output output = outputs.get(i);
            columns.add(ColumnSpec.of(output.name(), inferType(context, input, output, i)));
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(NodeSql.projection(input.columns()));
        for (Output output : outputs) {
            sql.append(", (").append(output.expression()).append(") AS ").append(NodeSql.quote(output.name()));
        }
        sql.append(" FROM ").append(input.asTable()).append(" AS dap_input");
        return new LazyPlan(context.db(), sql.toString(), columns);
    }

    private static List<Output> outputs(JsonNode config) {
        JsonNode node = config == null ? null : config.get("outputs");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("math_formula: 'outputs' must be a non-empty array of {name,expression}");
        }
        List<Output> outputs = new ArrayList<>(node.size());
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode item = node.get(i);
            int position = i + 1;
            if (!item.isObject()) {
                throw new IllegalArgumentException("math_formula: output " + position
                        + " must be an object with 'name' and 'expression'");
            }
            String name = text(item, "name", position, null);
            String expression = text(item, "expression", position, name);
            if (!names.add(name)) {
                throw new IllegalArgumentException("math_formula: duplicate output name '" + name + "'");
            }
            outputs.add(new Output(name, expression));
        }
        return outputs;
    }

    private static String text(JsonNode item, String field, int position, String name) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.textValue().isBlank()) {
            String label = name == null ? "output " + position : "output '" + name + "'";
            throw new IllegalArgumentException("math_formula: " + label + " requires a non-empty '" + field + "' string");
        }
        return value.textValue();
    }

    /**
     * Infer the Arrow type from DuckDB metadata only: DESCRIBE binds the
     * expression against the input relation without reading rows.
     */
    private static String inferType(NodeContext context, LazyPlan input, Output output, int position) {
        String sql = "DESCRIBE SELECT (" + output.expression() + ") AS " + INFERENCE_ALIAS
                + " FROM " + input.asTable() + " AS dap_input LIMIT 0";
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new IllegalArgumentException("math_formula: cannot infer a type for output " + position
                        + " ('" + output.name() + "')");
            }
            String duckDbType = result.getString("column_type");
            try {
                return TypeMap.fromDuckDb(duckDbType);
            } catch (IllegalArgumentException unsupported) {
                throw new IllegalArgumentException("math_formula: output '" + output.name()
                        + "' has unsupported DuckDB type '" + duckDbType + "'", unsupported);
            }
        } catch (SQLException failure) {
            throw new IllegalArgumentException("math_formula: output '" + output.name()
                    + "' has an invalid expression (" + output.expression() + "): " + failure.getMessage(), failure);
        }
    }

    private record Output(String name, String expression) {
    }
}

