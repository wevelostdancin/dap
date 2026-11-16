package com.dap.nativeengine.exec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Registry of node executors, keyed by native node id (dap.<type>). */
public final class Executors {
    private final Map<String, NodeExecutor> byId = new LinkedHashMap<>();

    public Executors register(NodeExecutor executor) {
        byId.put(executor.nodeId(), executor);
        return this;
    }

    public NodeExecutor get(String nodeId) {
        return byId.get(nodeId);
    }

    public Set<String> nodeIds() {
        return Set.copyOf(byId.keySet());
    }
}
