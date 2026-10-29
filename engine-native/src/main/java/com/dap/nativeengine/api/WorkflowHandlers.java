package com.dap.nativeengine.api;

import com.dap.nativeengine.catalog.NodeSpec;
import com.dap.nativeengine.exec.Connection;
import com.dap.nativeengine.exec.Job;
import com.dap.nativeengine.exec.Workflow;
import com.dap.nativeengine.exec.WorkflowNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workflow lifecycle, workflow nodes and connections. */
public final class WorkflowHandlers {
    private final ServiceState state;

    public WorkflowHandlers(ServiceState state) {
        this.state = state;
    }

    public void list(Context ctx) {
        String session = Http.session(ctx);
        List<Map<String, Object>> workflows = new ArrayList<>();
        for (Workflow workflow : state.workflows().list(session)) {
            workflows.add(view(workflow));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflows", workflows);
        Http.ok(ctx, body);
    }

    public void create(Context ctx) throws Exception {
        String session = Http.session(ctx);
        JsonNode request = Json.parseBody(ctx.body());
        JsonNode nameNode = request.get("name");
        String name = nameNode == null || nameNode.isNull() ? null : nameNode.asText(null);
        Workflow workflow = state.workflows().create(session, name);
        Http.ok(ctx, view(workflow));
    }

    public void delete(Context ctx) {
        String session = Http.session(ctx);
        String workflowId = ctx.pathParam("workflowId");
        Workflow workflow = state.requireWorkflow(session, workflowId);
        for (Job job : state.jobs().activeForWorkflow(workflow.id())) {
            state.jobs().cancel(job);
        }
        state.workflows().delete(session, workflowId);
        Http.ok(ctx, Map.of("ok", true));
    }

    public void nodes(Context ctx) {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (WorkflowNode node : workflow.nodes()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("node", node.id());
            view.put("name", node.name());
            view.put("state", node.state());
            if (node.message() != null) {
                view.put("message", node.message());
            }
            nodes.add(view);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodes", nodes);
        Http.ok(ctx, body);
    }

    public void addNode(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        JsonNode request = Json.parseBody(ctx.body());
        JsonNode factoryNode = request.get("factory");
        if (factoryNode == null || !factoryNode.isTextual() || factoryNode.asText().isBlank()) {
            throw ApiException.badRequest("'factory' is required");
        }
        String factory = factoryNode.asText();
        NodeSpec spec = state.registry().find(factory);
        if (spec == null) {
            throw ApiException.badRequest("unknown node: " + factory);
        }
        if (!spec.available()) {
            throw ApiException.badRequest("node not available in native engine: " + factory);
        }
        // Frozen contract quirk: the node exists before settings are validated.
        WorkflowNode node = workflow.addNode(factory, spec.name(), Json.object());
        applySettings(workflow, node, request.get("settings"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node", node.id());
        body.put("name", node.name());
        Http.ok(ctx, body);
    }

    public void configureNode(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        String nodeId = ctx.pathParam("workflowNodeId");
        WorkflowNode node = workflow.node(nodeId);
        if (node == null) {
            throw ApiException.notFound("unknown node: " + nodeId);
        }
        JsonNode request = Json.parseBody(ctx.body());
        applySettings(workflow, node, request.get("settings"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("node", node.id());
        Http.ok(ctx, body);
    }

    public void removeNode(Context ctx) {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        String nodeId = ctx.pathParam("workflowNodeId");
        if (workflow.node(nodeId) == null) {
            throw ApiException.notFound("unknown node: " + nodeId);
        }
        workflow.removeNode(nodeId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("node", nodeId);
        Http.ok(ctx, body);
    }

    public void connect(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        Connection connection = connection(Json.parseBody(ctx.body()));
        NodeSpec source = spec(workflow, connection.from());
        NodeSpec target = spec(workflow, connection.to());
        if (connection.fromPort() >= source.outPorts().size()) {
            throw ApiException.badRequest("node has " + source.outPorts().size() + " out ports");
        }
        if (connection.toPort() >= target.inPorts().size()) {
            throw ApiException.badRequest("node has " + target.inPorts().size() + " in ports");
        }
        workflow.connect(connection);
        Http.ok(ctx, Map.of("ok", true));
    }

    public void disconnect(Context ctx) throws Exception {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        Connection connection = connection(Json.parseBody(ctx.body()));
        if (!workflow.disconnect(connection)) {
            throw ApiException.notFound("connection not found");
        }
        Http.ok(ctx, Map.of("ok", true));
    }

    public void nodeDialog(Context ctx) {
        String session = Http.session(ctx);
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        String nodeId = ctx.pathParam("workflowNodeId");
        WorkflowNode node = workflow.node(nodeId);
        if (node == null) {
            throw ApiException.notFound("unknown node: " + nodeId);
        }
        NodeSpec spec = state.registry().find(node.factory());
        if (spec == null) {
            throw ApiException.notFound("unknown node: " + node.factory());
        }
        Map<String, Object> body = new LinkedHashMap<>(spec.dialog());
        body.put("node", node.id());
        Http.ok(ctx, body);
    }

    private static Map<String, Object> view(Workflow workflow) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", workflow.id());
        view.put("name", workflow.name());
        view.put("session", workflow.session());
        view.put("nodes", workflow.nodeCount());
        return view;
    }

    private void applySettings(Workflow workflow, WorkflowNode node, JsonNode settings) {
        if (settings == null || settings.isNull()) {
            return;
        }
        if (!settings.isObject()) {
            throw ApiException.badRequest("settings must be an object");
        }
        NodeSpec spec = state.registry().find(node.factory());
        if (spec == null) {
            throw ApiException.badRequest("unknown node: " + node.factory());
        }
        Iterator<String> keys = settings.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!spec.hasConfigKey(key)) {
                throw ApiException.badRequest("unknown setting '" + key
                        + "' in 'settings'; available: " + spec.settingKeys());
            }
        }
        workflow.setConfig(node.id(), ((ObjectNode) settings).deepCopy());
    }

    private NodeSpec spec(Workflow workflow, String nodeId) {
        WorkflowNode node = workflow.node(nodeId);
        if (node == null) {
            throw ApiException.badRequest("unknown node: " + nodeId);
        }
        NodeSpec spec = state.registry().find(node.factory());
        if (spec == null) {
            throw ApiException.badRequest("unknown node: " + node.factory());
        }
        return spec;
    }

    private static Connection connection(JsonNode request) {
        JsonNode from = request.get("from");
        JsonNode to = request.get("to");
        if (from == null || !from.isTextual() || from.asText().isBlank()
                || to == null || !to.isTextual() || to.asText().isBlank()) {
            throw ApiException.badRequest("'from' and 'to' are required");
        }
        return new Connection(from.asText(), port(request, "fromPort"), to.asText(), port(request, "toPort"));
    }

    private static int port(JsonNode request, String field) {
        JsonNode value = request.get(field);
        int port = value == null || value.isNull() ? 0 : value.asInt(0);
        if (port < 0) {
            throw ApiException.badRequest("port indices are 0-based");
        }
        return port;
    }
}
