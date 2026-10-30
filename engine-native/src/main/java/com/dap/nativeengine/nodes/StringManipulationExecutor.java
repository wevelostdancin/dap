package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** dap.string_manipulation: Transforms one string column (upper/lower/trim/replace/concat) in place or as a new column. */
public final class StringManipulationExecutor implements NodeExecutor {
    /** KNIME's default name for a column appended by String Manipulation. */
    private static final String APPENDED_COLUMN = "new column";

    @Override
    public String nodeId() {
        return "dap.string_manipulation";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan plan = NodeSql.input(inputs);
        JsonNode config = context.config();
        String columnName = NodeSql.requiredText(config, "column");
        ColumnSpec column = NodeSql.column(plan.columns(), columnName);
        if (!"Utf8".equals(column.arrowType())) {
            throw new IllegalArgumentException("string_manipulation: column '" + columnName + "' has type "
                    + column.arrowType() + ", expected Utf8");
        }
        String op = NodeSql.requiredText(config, "op").toLowerCase(Locale.ROOT);
        String mode = NodeSql.text(config, "mode", "replace").toLowerCase(Locale.ROOT);
        if (!"replace".equals(mode) && !"append".equals(mode)) {
            throw new IllegalArgumentException("string_manipulation: unknown mode '" + mode
                    + "' (expected replace, append)");
        }

        String quoted = NodeSql.quote(columnName);
        String expression = switch (op) {
            case "upper" -> "upper(" + quoted + ")";
            case "lower" -> "lower(" + quoted + ")";
            // KNIME's trim() strips every ASCII whitespace character (Java String.trim).
            case "trim" -> "trim(" + quoted + ", ' ' || chr(9) || chr(10) || chr(11) || chr(12) || chr(13))";
            case "replace" -> "replace(" + quoted + ", "
                    + NodeSql.literal(NodeSql.requiredText(config, "search")) + ", "
                    + NodeSql.literal(NodeSql.requiredText(config, "value")) + ")";
            case "concat" -> "(" + quoted + " || "
                    + NodeSql.literal(NodeSql.requiredText(config, "value")) + ")";
            default -> throw new IllegalArgumentException("string_manipulation: unknown op '" + op
                    + "' (expected upper, lower, trim, replace, concat)");
        };
        return "append".equals(mode) ? append(plan, expression) : replace(plan, column, expression);
    }

    /** One nested SELECT that rewrites only the target column, keeping schema order and type. */
    private static LazyPlan replace(LazyPlan plan, ColumnSpec target, String expression) {
        List<String> projection = new ArrayList<>(plan.columns().size());
        for (ColumnSpec column : plan.columns()) {
            String quoted = NodeSql.quote(column.name());
            projection.add(column.name().equals(target.name()) ? expression + " AS " + quoted : quoted);
        }
        String sql = "SELECT " + String.join(", ", projection) + " FROM " + plan.asTable() + " AS dap_input";
        return new LazyPlan(plan.session(), sql, plan.columns());
    }

    /** Original columns untouched, transformed value appended last as KNIME's "new column". */
    private static LazyPlan append(LazyPlan plan, String expression) {
        for (ColumnSpec existing : plan.columns()) {
            if (APPENDED_COLUMN.equals(existing.name())) {
                throw new IllegalArgumentException("string_manipulation: append would duplicate the existing column '"
                        + APPENDED_COLUMN + "'");
            }
        }
        String projection = NodeSql.projection(plan.columns()) + ", " + expression
                + " AS " + NodeSql.quote(APPENDED_COLUMN);
        List<ColumnSpec> columns = new ArrayList<>(plan.columns());
        columns.add(ColumnSpec.of(APPENDED_COLUMN, "Utf8"));
        String sql = "SELECT " + projection + " FROM " + plan.asTable() + " AS dap_input";
        return new LazyPlan(plan.session(), sql, columns);
    }
}
