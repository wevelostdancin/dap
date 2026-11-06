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
import java.util.List;

/** dap.rule_engine: CASE WHEN conditions mapped to literal values, with an optional else value. */
public final class RuleEngineExecutor implements NodeExecutor {
    private static final String INFERENCE_ALIAS = "dap_output_value";

    @Override
    public String nodeId() {
        return "dap.rule_engine";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String output = NodeSql.requiredText(config, "output");
        TransformSupport.requireNewName(input.columns(), output, "rule_engine");
        List<Rule> rules = rules(config);
        validateConditions(context, input, rules);
        String caseExpression = caseExpression(rules, config.get("elseValue"));
        String arrowType = inferType(context, input, caseExpression, output);

        String sql = "SELECT " + NodeSql.projection(input.columns())
                + ", (" + caseExpression + ") AS " + NodeSql.quote(output)
                + " FROM " + input.asTable() + " AS dap_input";
        validateProjection(context, sql);
        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        columns.add(ColumnSpec.of(output, arrowType));
        return new LazyPlan(context.db(), sql, columns);
    }

    /** DESCRIBE the final SELECT: per-condition checks miss aggregate conditions. */
    private static void validateProjection(NodeContext context, String sql) {
        try (java.sql.Connection connection = context.db().newConnection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet result = statement.executeQuery("DESCRIBE " + sql)) {
            result.next();
        } catch (java.sql.SQLException failure) {
            throw new IllegalArgumentException("rule_engine: invalid projection: " + failure.getMessage(), failure);
        }
    }

    private static List<Rule> rules(JsonNode config) {
        JsonNode rules = config.get("rules");
        if (rules == null || rules.isNull() || !rules.isArray() || rules.isEmpty()) {
            throw new IllegalArgumentException("rule_engine: 'rules' must be a non-empty array of {condition,value}");
        }
        List<Rule> parsed = new ArrayList<>(rules.size());
        for (int i = 0; i < rules.size(); i++) {
            JsonNode rule = rules.get(i);
            int position = i + 1;
            if (!rule.isObject()) {
                throw new IllegalArgumentException("rule_engine: rule " + position
                        + " must be an object with 'condition' and 'value'");
            }
            JsonNode condition = rule.get("condition");
            if (condition == null || condition.isNull() || !condition.isTextual()
                    || condition.textValue().isBlank()) {
                throw new IllegalArgumentException("rule_engine: rule " + position
                        + " requires a non-empty 'condition' string");
            }
            if (!rule.has("value")) {
                throw new IllegalArgumentException("rule_engine: rule " + position + " requires 'value'");
            }
            parsed.add(new Rule(condition.textValue(), rule.get("value")));
        }
        return parsed;
    }

    private static String caseExpression(List<Rule> rules, JsonNode elseValue) {
        StringBuilder sql = new StringBuilder("CASE");
        for (int i = 0; i < rules.size(); i++) {
            sql.append(" WHEN (").append(rules.get(i).condition()).append(") THEN ")
                    .append(literal(rules.get(i).value(), i + 1));
        }
        sql.append(" ELSE ");
        sql.append(elseValue == null || elseValue.isNull() ? "NULL" : literal(elseValue, 0));
        return sql.append(" END").toString();
    }

    /** Conditions are raw SQL: bind them once and require a boolean result type. */
    private static void validateConditions(NodeContext context, LazyPlan input, List<Rule> rules) {
        StringBuilder sql = new StringBuilder("DESCRIBE SELECT ");
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(").append(rules.get(i).condition()).append(") AS dap_condition_").append(i);
        }
        sql.append(" FROM ").append(input.asTable()).append(" AS dap_input LIMIT 0");
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql.toString())) {
            int index = 0;
            while (result.next()) {
                String type = result.getString("column_type");
                if (!"BOOLEAN".equalsIgnoreCase(type)) {
                    throw new IllegalArgumentException("rule_engine: rule " + (index + 1)
                            + " condition must be boolean, got " + type);
                }
                index++;
            }
            if (index != rules.size()) {
                throw new IllegalArgumentException("rule_engine: cannot bind rule conditions");
            }
        } catch (SQLException failure) {
            throw new IllegalArgumentException("rule_engine: invalid rule expression: "
                    + failure.getMessage(), failure);
        }
    }

    private record Rule(String condition, JsonNode value) {
    }

    /** SQL literal for a JSON scalar; the JSON type decides the SQL type. */
    private static String literal(JsonNode value, int position) {
        if (value == null || value.isNull()) {
            return "NULL";
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "TRUE" : "FALSE";
        }
        if (value.isNumber()) {
            return value.isIntegralNumber()
                    ? value.bigIntegerValue().toString()
                    : "CAST(" + value.decimalValue().toPlainString() + " AS DOUBLE)";
        }
        if (value.isTextual()) {
            return NodeSql.literal(value.textValue());
        }
        String label = position > 0 ? "rule " + position : "elseValue";
        throw new IllegalArgumentException("rule_engine: " + label + " 'value' must be a SQL scalar");
    }

    /**
     * Infer the Arrow type from DuckDB metadata only: DESCRIBE binds the CASE
     * against the input relation without reading rows.
     */
    private static String inferType(NodeContext context, LazyPlan input, String caseExpression, String output) {
        String sql = "DESCRIBE SELECT (" + caseExpression + ") AS " + INFERENCE_ALIAS
                + " FROM " + input.asTable() + " AS dap_input LIMIT 0";
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new IllegalArgumentException("rule_engine: cannot infer a type for output '" + output + "'");
            }
            String duckDbType = result.getString("column_type");
            try {
                return TypeMap.fromDuckDb(duckDbType);
            } catch (IllegalArgumentException unsupported) {
                throw new IllegalArgumentException("rule_engine: output '" + output
                        + "' has unsupported DuckDB type '" + duckDbType + "'", unsupported);
            }
        } catch (SQLException failure) {
            throw new IllegalArgumentException("rule_engine: invalid rule expression: "
                    + failure.getMessage(), failure);
        }
    }
}