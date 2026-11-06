package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/** dap.unpivot: stacks the selected columns into name/value pairs via DuckDB UNPIVOT. */
public final class UnpivotExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.unpivot";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        List<ColumnSpec> selected = selectedColumns(input, config);
        String nameColumn = NodeSql.text(config, "nameColumn", "variable");
        String valueColumn = NodeSql.text(config, "valueColumn", "value");
        if (nameColumn.isBlank()) {
            throw new IllegalArgumentException("unpivot: 'nameColumn' must be a non-empty string");
        }
        if (valueColumn.isBlank()) {
            throw new IllegalArgumentException("unpivot: 'valueColumn' must be a non-empty string");
        }
        if (nameColumn.equals(valueColumn)) {
            throw new IllegalArgumentException("unpivot: 'nameColumn' and 'valueColumn' must differ, both are '"
                    + nameColumn + "'");
        }

        Set<String> selectedNames = new LinkedHashSet<>();
        for (ColumnSpec column : selected) {
            selectedNames.add(column.name());
        }
        List<ColumnSpec> kept = new ArrayList<>();
        for (ColumnSpec column : input.columns()) {
            if (!selectedNames.contains(column.name())) {
                kept.add(column);
            }
        }
        for (ColumnSpec column : kept) {
            if (column.name().equals(nameColumn) || column.name().equals(valueColumn)) {
                throw new IllegalArgumentException("unpivot: output column '" + column.name()
                        + "' collides with an existing column");
            }
        }

        String type = selected.get(0).arrowType();
        for (ColumnSpec column : selected) {
            if (!column.arrowType().equals(type)) {
                throw new IllegalArgumentException("unpivot: selected columns must share one type, got '"
                        + type + "' and '" + column.arrowType() + "' for '" + column.name() + "'");
            }
        }

        StringJoiner on = new StringJoiner(", ");
        for (ColumnSpec column : selected) {
            on.add(NodeSql.quote(column.name()));
        }
        String unpivot = "UNPIVOT (SELECT * FROM " + input.asTable() + ") ON " + on
                + " INTO NAME " + NodeSql.quote(nameColumn) + " VALUE " + NodeSql.quote(valueColumn);

        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : kept) {
            projection.add(NodeSql.quote(column.name()));
        }
        projection.add(NodeSql.quote(nameColumn));
        projection.add(NodeSql.quote(valueColumn));

        String sql = "SELECT " + projection + " FROM (" + unpivot + ") AS dap_unpivot";
        List<ColumnSpec> columns = new ArrayList<>(kept);
        columns.add(ColumnSpec.of(nameColumn, "Utf8"));
        columns.add(ColumnSpec.of(valueColumn, type));
        return new LazyPlan(context.db(), sql, columns);
    }

    private static List<ColumnSpec> selectedColumns(LazyPlan input, JsonNode config) {
        JsonNode node = config.get("columns");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("unpivot: 'columns' must be a non-empty array of column names");
        }
        List<ColumnSpec> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("unpivot: 'columns' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException("unpivot: duplicate column '" + name + "' in 'columns'");
            }
            try {
                selected.add(NodeSql.column(input.columns(), name));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("unpivot: unknown column '" + name + "'");
            }
        }
        return selected;
    }
}
