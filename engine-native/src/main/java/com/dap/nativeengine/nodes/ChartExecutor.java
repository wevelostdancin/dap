package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.viz.ChartRenderer;
import com.dap.nativeengine.viz.ChartSpec;
import com.dap.nativeengine.viz.Scene;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * dap.chart: reads a table preview, asks {@link ChartRenderer} for a scene and writes it as SVG
 * or PNG, then passes the input table through unchanged. All chart semantics (labels, sorting,
 * headroom, log axes, facets, annotations) live in the renderer; this executor only parses
 * settings and touches the file system.
 */
public final class ChartExecutor implements NodeExecutor {
    private static final String NODE = "chart";

    @Override
    public String nodeId() {
        return "dap.chart";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        String format = NodeSql.text(config, "format", "svg");
        if (!format.equals("svg") && !format.equals("png")) {
            throw new IllegalArgumentException(NODE + ": 'format' must be svg or png, got '"
                    + format + "'");
        }
        ChartSpec spec = ChartConfig.parse(config, input.columns(), NODE);
        TableData data = input.preview(spec.limit(), context.workflowId(), context.nodeId());
        Scene scene = ChartRenderer.render(spec, input.columns(), data);
        byte[] content = format.equals("svg")
                ? ChartRenderer.svg(scene).getBytes(StandardCharsets.UTF_8)
                : ChartRenderer.png(scene);
        IoNodeSupport.writeAtomically(IoNodeSupport.targetFile(file, NODE), content, NODE);
        return input;
    }
}
