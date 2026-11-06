package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/** dap.correlation: long-format pairwise Pearson or Spearman correlation. */
public final class CorrelationExecutor implements NodeExecutor {
    private static final List<String> METHODS = List.of("pearson", "spearman");
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("column_a", "Utf8"),
            ColumnSpec.of("column_b", "Utf8"),
            ColumnSpec.of("correlation", "Float64"));

    @Override
    public String nodeId() {
        return "dap.correlation";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        List<ColumnSpec> columns = columns(input.columns(), context.config());
        String method = NodeSql.text(context.config(), "method", "pearson").trim().toLowerCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("correlation: unknown method '" + method
                    + "' (expected pearson or spearman)");
        }
        String table = input.asTable();
        StringJoiner union = new StringJoiner("\nUNION ALL\n");
        int ordinal = 0;
        for (int first = 0; first < columns.size(); first++) {
            for (int second = first + 1; second < columns.size(); second++) {
                ColumnSpec a = columns.get(first);
                ColumnSpec b = columns.get(second);
                union.add("pearson".equals(method)
                        ? pearson(ordinal++, a, b, table)
                        : spearman(ordinal++, a, b, table));
            }
        }
        String sql = "SELECT " + NodeSql.projection(OUTPUT) + " FROM (" + union + ") AS dap_correlation"
                + " ORDER BY \"dap_ordinal\"";
        return new LazyPlan(context.db(), sql, OUTPUT);
    }

    private static String pearson(int ordinal, ColumnSpec a, ColumnSpec b, String table) {
        String qa = NodeSql.quote(a.name());
        String qb = NodeSql.quote(b.name());
        return "SELECT " + ordinal + " AS \"dap_ordinal\", "
                + NodeSql.literal(a.name()) + " AS \"column_a\", "
                + NodeSql.literal(b.name()) + " AS \"column_b\", "
                + "CAST(CORR(" + qa + ", " + qb + ") AS DOUBLE) AS \"correlation\""
                + " FROM " + table + " AS dap_input";
    }

    private static String spearman(int ordinal, ColumnSpec a, ColumnSpec b, String table) {
        String qa = NodeSql.quote(a.name());
        String qb = NodeSql.quote(b.name());
        return "SELECT " + ordinal + " AS \"dap_ordinal\", "
                + NodeSql.literal(a.name()) + " AS \"column_a\", "
                + NodeSql.literal(b.name()) + " AS \"column_b\", "
                + "CAST(CORR(\"dap_rank_a\", \"dap_rank_b\") AS DOUBLE) AS \"correlation\""
                + " FROM (SELECT RANK() OVER (ORDER BY " + qa + ") AS \"dap_rank_a\", "
                + "RANK() OVER (ORDER BY " + qb + ") AS \"dap_rank_b\""
                + " FROM " + table + " AS dap_input"
                + " WHERE " + qa + " IS NOT NULL AND " + qb + " IS NOT NULL) AS dap_ranked";
    }

    private static List<ColumnSpec> columns(List<ColumnSpec> available, JsonNode config) {
        JsonNode node = config.get("columns");
        if (node == null || node.isNull() || !node.isArray()) {
            throw new IllegalArgumentException("correlation: 'columns' must be an array of at least two numeric column names");
        }
        if (node.size() < 2) {
            throw new IllegalArgumentException("correlation: 'columns' must list at least two numeric columns");
        }
        List<ColumnSpec> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("correlation: 'columns' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("correlation: duplicate column '" + name + "'");
            }
            ColumnSpec column;
            try {
                column = NodeSql.column(available, name);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("correlation: unknown column '" + name + "'");
            }
            if (!column.numeric()) {
                throw new IllegalArgumentException("correlation: column '" + name + "' is not numeric ("
                        + column.arrowType() + ")");
            }
            columns.add(column);
        }
        return columns;
    }
}
