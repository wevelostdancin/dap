package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** dap.diff: difference against the previous row of the same partition. */
public final class DiffExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.diff";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        ColumnSpec column = TimeSeriesSupport.column(input.columns(), NodeSql.requiredText(config, "column"),
                "value", "diff");
        if (!column.numeric()) {
            throw new IllegalArgumentException("diff: column '" + column.name() + "' is " + column.arrowType()
                    + "; diff requires a numeric column");
        }
        String output = NodeSql.requiredText(config, "output");
        TransformSupport.requireNewName(input.columns(), output, "diff");
        List<ColumnSpec> partitionBy = TimeSeriesSupport.partitionColumns(config, input, "diff");
        List<TimeSeriesSupport.OrderKey> orderBy = TimeSeriesSupport.orderKeys(config, input, "diff");

        String over = TimeSeriesSupport.overClause(partitionBy, orderBy, null);
        String quoted = "dap_input." + NodeSql.quote(column.name());
        String expression = "CAST(" + quoted + " - LAG(" + quoted + ") OVER (" + over + ") AS "
                + column.duckDbType() + ")";

        List<ColumnSpec> columns = new ArrayList<>(input.columns());
        columns.add(ColumnSpec.of(output, column.arrowType()));
        String sql = "SELECT " + NodeSql.projection(input.columns()) + ", " + expression
                + " AS " + NodeSql.quote(output) + " FROM " + input.asTable() + " AS dap_input";
        return new LazyPlan(context.db(), sql, columns);
    }
}
