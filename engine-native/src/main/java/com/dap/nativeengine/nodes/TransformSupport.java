package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Shared plumbing for transform nodes that append columns to the input schema. */
final class TransformSupport {
    private TransformSupport() {
    }

    /** Reject an output name that would duplicate an existing input column. */
    static void requireNewName(List<ColumnSpec> columns, String name, String node) {
        for (ColumnSpec column : columns) {
            if (column.name().equals(name)) {
                throw new IllegalArgumentException(node + ": output '" + name
                        + "' duplicates an existing column");
            }
        }
    }

    /** Keep every input column and append the aliased expressions, in order. */
    static LazyPlan append(LazyPlan input, List<String> aliasedExpressions, List<ColumnSpec> added) {
        if (aliasedExpressions.size() != added.size()) {
            throw new IllegalStateException("expression/column count mismatch");
        }
        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        columns.addAll(added);
        StringBuilder sql = new StringBuilder("SELECT ").append(NodeSql.projection(input.columns()));
        for (String expression : aliasedExpressions) {
            sql.append(", ").append(expression);
        }
        sql.append(" FROM ").append(input.asTable()).append(" AS dap_input");
        return new LazyPlan(input.session(), sql.toString(), columns);
    }

    /** SQL literal for a JSON number used as a bin edge. */
    static String number(JsonNode value, String node, String field) {
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new IllegalArgumentException(node + ": every '" + field + "' entry must be a number");
        }
        BigDecimal decimal = value.decimalValue();
        return decimal.toPlainString();
    }
}
