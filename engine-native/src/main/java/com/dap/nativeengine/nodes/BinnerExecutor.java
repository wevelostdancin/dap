package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** dap.binner: maps a numeric column to integer bins by equal width, custom edges or frequency. */
public final class BinnerExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.binner";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        ColumnSpec column = NodeSql.column(input.columns(), NodeSql.requiredText(config, "column"));
        if (!column.numeric()) {
            throw new IllegalArgumentException("binner: column '" + column.name()
                    + "' must be numeric, got " + column.arrowType());
        }
        String output = NodeSql.requiredText(config, "output");
        TransformSupport.requireNewName(input.columns(), output, "binner");
        String method = NodeSql.requiredText(config, "method").toLowerCase(Locale.ROOT);
        String quoted = NodeSql.quote(column.name());

        if ("frequency".equals(method)) {
            return frequency(input, quoted, bins(config), output);
        }
        String expression = switch (method) {
            case "width" -> width(quoted, input.asTable(), bins(config));
            case "custom" -> custom(quoted, config);
            default -> throw new IllegalArgumentException("binner: unknown method '" + method
                    + "' (expected width, custom, frequency)");
        };
        return TransformSupport.append(input,
                List.of(expression + " AS " + NodeSql.quote(output)),
                List.of(ColumnSpec.of(output, "Int64")));
    }

    /**
     * Frequency bins over the non-NULL values only. Ranking every row would let the
     * trailing NULLs occupy tiles and shift the boundaries of the observed values.
     */
    private static LazyPlan frequency(LazyPlan input, String quoted, int bins, String output) {
        String row = internalName("dap_bin_row", input);
        String bin = internalName("dap_bin", input);
        String sql = "WITH dap_frequency AS ("
                + "SELECT " + NodeSql.projection(input.columns()) + ", ROW_NUMBER() OVER () AS "
                + NodeSql.quote(row) + " FROM " + input.asTable() + " AS dap_source), "
                + "dap_ranked AS ("
                + "SELECT " + NodeSql.quote(row) + ", NTILE(" + bins + ") OVER (ORDER BY " + quoted
                + ") AS " + NodeSql.quote(bin)
                + " FROM dap_frequency WHERE " + quoted + " IS NOT NULL) "
                + "SELECT " + NodeSql.projection(input.columns()) + ", CAST(r." + NodeSql.quote(bin)
                + " AS BIGINT) AS " + NodeSql.quote(output)
                + " FROM dap_frequency AS f LEFT JOIN dap_ranked AS r USING (" + NodeSql.quote(row) + ")"
                + " ORDER BY f." + NodeSql.quote(row);
        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        columns.add(ColumnSpec.of(output, "Int64"));
        return new LazyPlan(input.session(), sql, columns);
    }

    /** Internal column name that cannot shadow an input column. */
    private static String internalName(String base, LazyPlan input) {
        String name = base;
        int suffix = 1;
        outer:
        while (true) {
            for (ColumnSpec column : input.columns()) {
                if (column.name().equals(name)) {
                    name = base + "_" + suffix++;
                    continue outer;
                }
            }
            return name;
        }
    }

    /** Equal-width bins over the observed min/max; the maximum value lands in the last bin. */
    private static String width(String quoted, String table, int bins) {
        String min = "(SELECT MIN(" + quoted + ") FROM " + table + " AS dap_min)";
        String max = "(SELECT MAX(" + quoted + ") FROM " + table + " AS dap_max)";
        String width = "((" + max + " - " + min + ") / " + bins + ")";
        String index = "FLOOR((" + quoted + " - " + min + ") / " + width + ")";
        return "CAST(CASE WHEN " + quoted + " IS NULL THEN NULL"
                + " WHEN " + max + " = " + min + " THEN 0"
                + " ELSE LEAST(" + index + ", " + (bins - 1) + ") END AS BIGINT)";
    }

    /** n+1 ascending edges define n half-open bins [edge[i], edge[i+1]); outside values use the edge bins. */
    private static String custom(String quoted, JsonNode config) {
        JsonNode edges = config.get("edges");
        if (edges == null || edges.isNull() || !edges.isArray() || edges.size() < 2) {
            throw new IllegalArgumentException("binner: method 'custom' requires 'edges' with at least two boundaries");
        }
        List<String> literals = new ArrayList<>(edges.size());
        BigDecimal previous = null;
        for (int i = 0; i < edges.size(); i++) {
            String literal = TransformSupport.number(edges.get(i), "binner", "edges");
            BigDecimal value = edges.get(i).decimalValue();
            if (previous != null && value.compareTo(previous) <= 0) {
                throw new IllegalArgumentException("binner: 'edges' must be strictly ascending, but entry "
                        + (i + 1) + " (" + literal + ") is not greater than the previous one");
            }
            previous = value;
            literals.add(literal);
        }
        int bins = literals.size() - 1;
        StringBuilder sql = new StringBuilder("CAST(CASE WHEN ").append(quoted).append(" IS NULL THEN NULL");
        for (int i = 0; i < bins; i++) {
            sql.append(" WHEN ").append(quoted).append(" < ").append(literals.get(i + 1))
                    .append(" THEN ").append(i);
        }
        return sql.append(" ELSE ").append(bins - 1).append(" END AS BIGINT)").toString();
    }

    private static int bins(JsonNode config) {
        JsonNode value = config.get("bins");
        if (value == null || value.isNull() || !value.isIntegralNumber() || value.longValue() <= 0) {
            throw new IllegalArgumentException("binner: config field 'bins' must be a positive integer");
        }
        long bins = value.longValue();
        if (bins > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("binner: config field 'bins' is too large: " + bins);
        }
        return (int) bins;
    }
}
