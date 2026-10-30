package com.dap.nativeengine.api;

import com.dap.nativeengine.catalog.NodeSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.ProfileColumn;
import com.dap.nativeengine.engine.ProfileData;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.Workflow;
import com.dap.nativeengine.exec.WorkflowNode;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Table previews and column profiles of executed workflow nodes. */
public final class DataHandlers {
    private static final int DEFAULT_LIMIT = 50;

    private final ServiceState state;

    public DataHandlers(ServiceState state) {
        this.state = state;
    }

    public void table(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        String nodeId = ctx.pathParam("workflowNodeId");
        WorkflowNode node = workflow.node(nodeId);
        if (node == null) {
            throw ApiException.badRequest("unknown node: " + nodeId);
        }
        NodeSpec spec = spec(node);
        int port = Http.queryInt(ctx, "port", 0);
        int limit = Math.max(0, Http.queryInt(ctx, "limit", DEFAULT_LIMIT));
        LazyPlan plan = node.result();
        if (plan == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "node has no table output");
            body.put("ports", spec.outPorts().stream().map(NodeSpec.Port::toMap).toList());
            Http.json(ctx, 400, body);
            return;
        }
        int outPorts = spec.outPorts().size();
        if (port < 0 || port >= outPorts) {
            throw ApiException.badRequest("node has " + outPorts + " out ports");
        }
        TableData table = plan.preview(limit, workflow.id(), nodeId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", table.rows());
        body.put("columns", columns(table.columns()));
        body.put("data", table.data());
        body.put("preview", table.preview());
        Http.ok(ctx, body);
    }

    public void profile(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        String nodeId = ctx.pathParam("workflowNodeId");
        WorkflowNode node = workflow.node(nodeId);
        if (node == null) {
            throw ApiException.badRequest("unknown node: " + nodeId);
        }
        spec(node);
        LazyPlan plan = node.result();
        if (plan == null) {
            throw ApiException.badRequest("node has no table output");
        }
        ProfileData profile = plan.profile(workflow.id(), nodeId);
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ProfileColumn column : profile.columns()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", column.name());
            view.put("type", column.type());
            view.put("missing", column.missing());
            view.put("distinct", column.distinct());
            // Numeric-only statistics; a null aggregate (all-missing column) is omitted.
            if (column.min() != null) {
                view.put("min", column.min());
            }
            if (column.max() != null) {
                view.put("max", column.max());
            }
            if (column.mean() != null) {
                view.put("mean", column.mean());
            }
            columns.add(view);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", profile.rows());
        body.put("columns", columns);
        Http.ok(ctx, body);
    }

    private NodeSpec spec(WorkflowNode node) {
        NodeSpec spec = state.registry().find(node.factory());
        if (spec == null) {
            throw ApiException.badRequest("unknown node: " + node.factory());
        }
        return spec;
    }

    private static List<Map<String, Object>> columns(List<com.dap.nativeengine.engine.ColumnSpec> specs) {
        List<Map<String, Object>> columns = new ArrayList<>(specs.size());
        for (int index = 0; index < specs.size(); index++) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("index", index);
            column.put("name", specs.get(index).name());
            column.put("type", specs.get(index).knimeType());
            columns.add(column);
        }
        return columns;
    }
}
