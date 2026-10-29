package com.dap.nativeengine.exec;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.LazyPlan;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory workflow graph. All structural operations are synchronized. */
public final class Workflow {
    private final String id;
    private final String name;
    private final String session;
    private final LinkedHashMap<String, WorkflowNode> nodes = new LinkedHashMap<>();
    private final List<Connection> connections = new ArrayList<>();
    private int nextIndex = 1;

    public Workflow(String id, String name, String session) {
        this.id = id;
        this.name = name;
        this.session = session;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String session() {
        return session;
    }

    public synchronized int nodeCount() {
        return nodes.size();
    }

    public synchronized List<WorkflowNode> nodes() {
        return new ArrayList<>(nodes.values());
    }

    public synchronized WorkflowNode node(String nodeId) {
        return nodes.get(nodeId);
    }

    public synchronized List<Connection> connections() {
        return List.copyOf(connections);
    }

    /** One run-scoped view of the graph. Configs are deep copies taken under the workflow lock. */
    public record Graph(List<WorkflowNode> nodes, List<Connection> connections, Map<String, ObjectNode> configs) {
    }

    /**
     * Capture nodes, connections and configs under one lock. Structural edits made
     * after this call (add/remove node, connect, setConfig) do not affect the run
     * that uses the returned snapshot.
     */
    public synchronized Graph snapshot() {
        List<WorkflowNode> nodeSnapshot = new ArrayList<>(nodes.values());
        Map<String, ObjectNode> configs = new LinkedHashMap<>();
        for (WorkflowNode node : nodeSnapshot) {
            configs.put(node.id(), node.config().deepCopy());
        }
        return new Graph(List.copyOf(nodeSnapshot), List.copyOf(connections), Map.copyOf(configs));
    }

    public synchronized WorkflowNode addNode(String factory, String displayName, ObjectNode config) {
        int numeric = Integer.parseInt(id.substring(3));
        String nodeId = numeric + ":" + nextIndex++;
        WorkflowNode node = new WorkflowNode(nodeId, factory, displayName, config);
        nodes.put(nodeId, node);
        return node;
    }

    public synchronized void setConfig(String nodeId, ObjectNode config) {
        WorkflowNode node = requireNode(nodeId);
        ObjectNode merged = node.config().deepCopy();
        config.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        node.config(merged);
    }

    public void removeNode(String nodeId) {
        WorkflowNode node;
        synchronized (this) {
            node = nodes.get(nodeId);
            if (node == null) {
                throw ApiException.badRequest("node not found: " + nodeId);
            }
            for (Connection connection : connections) {
                if (connection.from().equals(nodeId) || connection.to().equals(nodeId)) {
                    throw ApiException.badRequest("node has connections and cannot be removed");
                }
            }
            nodes.remove(nodeId);
        }
    }

    public synchronized void connect(Connection connection) {
        requireNode(connection.from());
        requireNode(connection.to());
        if (connection.from().equals(connection.to())) {
            throw ApiException.badRequest("cannot connect a node to itself");
        }
        for (Connection existing : connections) {
            if (existing.to().equals(connection.to()) && existing.toPort() == connection.toPort()) {
                throw ApiException.badRequest("input port is already connected");
            }
            if (existing.from().equals(connection.from()) && existing.fromPort() == connection.fromPort()
                    && existing.to().equals(connection.to()) && existing.toPort() == connection.toPort()) {
                throw ApiException.badRequest("connection already exists");
            }
        }
        connections.add(connection);
        if (hasCycle()) {
            connections.remove(connection);
            throw ApiException.badRequest("connection would create a cycle");
        }
    }

    public synchronized boolean disconnect(Connection connection) {
        return connections.remove(connection);
    }

    public synchronized void resetResults() {
        for (WorkflowNode node : nodes.values()) {
            node.reset();
        }
    }

    public synchronized void executed(String nodeId, LazyPlan plan) {
        requireNode(nodeId).executed(plan);
    }

    public synchronized void failed(String nodeId, String error) {
        WorkflowNode node = nodes.get(nodeId);
        if (node != null) {
            node.failed(error);
        }
    }

    public synchronized Map<String, LazyPlan> results() {
        Map<String, LazyPlan> results = new LinkedHashMap<>();
        for (WorkflowNode node : nodes.values()) {
            if (node.result() != null) {
                results.put(node.id(), node.result());
            }
        }
        return results;
    }

    public synchronized Set<String> inputPorts(String nodeId) {
        Set<String> ports = new LinkedHashSet<>();
        for (Connection connection : connections) {
            if (connection.to().equals(nodeId)) {
                ports.add(connection.toPort() + "<-" + connection.from() + ":" + connection.fromPort());
            }
        }
        return ports;
    }

    private WorkflowNode requireNode(String nodeId) {
        WorkflowNode node = nodes.get(nodeId);
        if (node == null) {
            throw ApiException.notFound("unknown node: " + nodeId);
        }
        return node;
    }

    private boolean hasCycle() {
        Map<String, Integer> state = new LinkedHashMap<>();
        for (String nodeId : nodes.keySet()) {
            if (visit(nodeId, state)) {
                return true;
            }
        }
        return false;
    }

    private boolean visit(String nodeId, Map<String, Integer> state) {
        Integer mark = state.get(nodeId);
        if (mark != null) {
            return mark == 1;
        }
        state.put(nodeId, 1);
        for (Connection connection : connections) {
            if (connection.from().equals(nodeId) && visit(connection.to(), state)) {
                return true;
            }
        }
        state.put(nodeId, 2);
        return false;
    }

    /** Diagnostic view used by /debug; not a stable contract. */
    public synchronized Map<String, Object> debug() {
        List<Map<String, Object>> nodeViews = new ArrayList<>();
        for (WorkflowNode node : nodes.values()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("node", node.id());
            view.put("name", node.name());
            view.put("factory", node.factory());
            view.put("state", node.state());
            view.put("config", node.config());
            nodeViews.add(view);
        }
        List<String> edges = new ArrayList<>();
        for (Connection connection : connections) {
            edges.add(connection.from() + ":" + connection.fromPort() + " -> " + connection.to() + ":" + connection.toPort());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflow", id);
        result.put("name", name);
        result.put("nodes", nodeViews);
        result.put("connections", edges);
        return result;
    }

    /** JSON document written by POST /save. */
    public synchronized ObjectNode toJson() {
        ObjectNode document = Json.object();
        document.put("format", "dap-native-workflow/1");
        document.put("id", id);
        document.put("name", name);
        var nodesArray = document.putArray("nodes");
        for (WorkflowNode node : nodes.values()) {
            ObjectNode view = nodesArray.addObject();
            view.put("id", node.id());
            view.put("factory", node.factory());
            view.put("name", node.name());
            view.set("settings", node.config());
        }
        var edgesArray = document.putArray("connections");
        for (Connection connection : connections) {
            ObjectNode view = edgesArray.addObject();
            view.put("from", connection.from());
            view.put("fromPort", connection.fromPort());
            view.put("to", connection.to());
            view.put("toPort", connection.toPort());
        }
        return document;
    }
}
