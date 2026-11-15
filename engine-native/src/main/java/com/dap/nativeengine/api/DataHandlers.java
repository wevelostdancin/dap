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
        WorkflowNode node = node(workflow, nodeId);
        NodeSpec spec = spec(node);
        int port = Http.queryInt(ctx, "port", 0);
        int limit = Math.max(0, Http.queryInt(ctx, "limit", DEFAULT_LIMIT));
        LazyPlan plan = materialized(workflow, node,
                spec.outPorts().stream().map(NodeSpec.Port::toMap).toList());
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
        WorkflowNode node = node(workflow, nodeId);
        spec(node);
        LazyPlan plan = materialized(workflow, node, null);
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

    /** Unknown workflow node: 404 unknown_node on every data route. */
    private static WorkflowNode node(Workflow workflow, String nodeId) {
        WorkflowNode node = workflow.node(nodeId);
        if (node == null) {
            throw ApiException.notFound("unknown_node", "unknown node: " + nodeId,
                    "GET /v1/workflows/" + workflow.id() + "/nodes lists the node ids");
        }
        return node;
    }

    /**
     * The node's materialized plan: 409 node_failed when the node failed (with the node
     * message) and 409 not_materialized before a run. Ports are only reported by /table,
     * which had them in its 400 body before the code was pinned.
     */
    private static LazyPlan materialized(Workflow workflow, WorkflowNode node,
                                         List<Map<String, Object>> ports) {
        LazyPlan plan = node.result();
        if (plan != null) {
            return plan;
        }
        if ("ERROR".equals(node.state())) {
            throw ApiException.conflict("node_failed",
                    node.message() == null ? "node failed: " + node.id() : node.message(),
                    "fix the node configuration and run the workflow again");
        }
        String hint = "run the workflow first: POST /v1/workflows/" + workflow.id() + "/run";
        if (ports == null) {
            throw new ApiException(409, "not_materialized", "node has no table output", hint);
        }
        throw new ApiException(409, "not_materialized", "node has no table output", hint,
                Map.of("ports", ports));
    }

    private NodeSpec spec(WorkflowNode node) {
        NodeSpec spec = state.registry().find(node.factory());
        if (spec == null) {
            throw ApiException.notFound("unknown_node", "unknown node: " + node.factory(),
                    "GET /v1/nodes lists the available node types");
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
