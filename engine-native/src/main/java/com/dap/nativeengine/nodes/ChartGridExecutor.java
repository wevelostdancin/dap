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
import java.util.ArrayList;
import java.util.List;

/**
 * dap.chart_grid: renders up to eight charts into one grid file and passes input port 0 through
 * unchanged. Each entry of {@code charts} selects its input port and carries the same settings as
 * dap.chart; the grid owns panel sizes, the shared title and the output format (SVG, PNG or a
 * self-contained HTML page with one inline SVG per chart).
 */
public final class ChartGridExecutor implements NodeExecutor {
    private static final String NODE = "chart_grid";
    private static final int MAX_INPUTS = 8;
    private static final int MAX_CHARTS = 8;
    private static final int DEFAULT_WIDTH = 1200;
    private static final int DEFAULT_HEIGHT = 800;
    private static final int DEFAULT_COLUMNS = 2;
    private static final int HEADER = 40;

    @Override
    public String nodeId() {
        return "dap.chart_grid";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        String format = NodeSql.text(config, "format", "svg");
        if (!List.of("svg", "png", "html").contains(format)) {
            throw new IllegalArgumentException(NODE + ": 'format' must be svg, png or html, got '"
                    + format + "'");
        }
        String title = NodeSql.text(config, "title", null);
        int columns = ChartConfig.integer(config, "columns", DEFAULT_COLUMNS, 1, MAX_INPUTS, NODE);
        int width = ChartConfig.integer(config, "width", DEFAULT_WIDTH, ChartConfig.MIN_SIZE,
                ChartConfig.MAX_SIZE, NODE);
        int height = ChartConfig.integer(config, "height", DEFAULT_HEIGHT, ChartConfig.MIN_SIZE,
                ChartConfig.MAX_SIZE, NODE);
        JsonNode charts = config.get("charts");
        if (charts == null || charts.isNull() || !charts.isArray() || charts.isEmpty()) {
            throw new IllegalArgumentException(NODE + ": 'charts' must be a non-empty array of chart "
                    + "objects");
        }
        if (charts.size() > MAX_CHARTS) {
            throw new IllegalArgumentException(NODE + ": 'charts' accepts at most " + MAX_CHARTS
                    + " entries, got " + charts.size());
        }
        int rows = (charts.size() + columns - 1) / columns;
        int header = title == null || title.isBlank() ? 0 : HEADER;
        int cellWidth = width / columns;
        int cellHeight = (height - header) / rows;
        List<Scene> panels = new ArrayList<>();
        for (int index = 0; index < charts.size(); index++) {
            JsonNode entry = charts.get(index);
            if (!entry.isObject()) {
                throw new IllegalArgumentException(NODE + ": charts[" + index + "] must be an object");
            }
            String prefix = NODE + " charts[" + index + "]";
            int port = ChartConfig.integer(entry, "input", 0, 0, MAX_INPUTS - 1, prefix);
            LazyPlan source = port < inputs.size() ? inputs.get(port) : null;
            if (source == null) {
                throw new IllegalArgumentException(NODE + ": charts[" + index + "] references input "
                        + port + ", which is not connected");
            }
            ChartSpec spec = ChartConfig.parse(entry, source.columns(), prefix, cellWidth, cellHeight);
            TableData data = source.preview(spec.limit(), context.workflowId(), context.nodeId());
            panels.add(ChartRenderer.render(spec, source.columns(), data));
        }
        byte[] content;
        if (format.equals("html")) {
            content = html(title, panels).getBytes(StandardCharsets.UTF_8);
        } else {
            Scene scene = ChartRenderer.grid(panels, columns, width, height, header, title);
            content = format.equals("svg")
                    ? ChartRenderer.svg(scene).getBytes(StandardCharsets.UTF_8)
                    : ChartRenderer.png(scene);
        }
        IoNodeSupport.writeAtomically(IoNodeSupport.targetFile(file, NODE), content, NODE);
        return input;
    }

    /** Self-contained HTML: inline SVG per panel, no external styles, fonts or scripts. */
    private static String html(String title, List<Scene> panels) {
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        html.append("<title>").append(escape(title == null || title.isBlank() ? "charts" : title))
                .append("</title>\n");
        html.append("<style>body{font-family:sans-serif;margin:16px}")
                .append("figure{display:inline-block;margin:8px;vertical-align:top}</style>\n");
        html.append("</head>\n<body>\n");
        if (title != null && !title.isBlank()) {
            html.append("<h1>").append(escape(title)).append("</h1>\n");
        }
        for (Scene panel : panels) {
            String svg = ChartRenderer.svg(panel);
            int start = svg.indexOf("<svg ");
            html.append("<figure>").append(svg.substring(Math.max(0, start)))
                    .append("</figure>\n");
        }
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
