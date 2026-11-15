package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.DuckDbSession;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.util.List;

/** Everything an executor needs: its config, the session database, the job connection. */
public record NodeContext(String workflowId, String nodeId, String sessionId, ObjectNode config,
                          DuckDbSession db, Connection jobConnection, List<String> inputNodeIds) {
    public NodeContext {
        if (config == null) {
            config = com.dap.nativeengine.api.Json.object();
        }
        inputNodeIds = inputNodeIds == null ? List.of()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(inputNodeIds));
    }

    /** Context without input ids, for executors that never look at them. */
    public NodeContext(String workflowId, String nodeId, String sessionId, ObjectNode config,
                       DuckDbSession db, Connection jobConnection) {
        this(workflowId, nodeId, sessionId, config, db, jobConnection, List.of());
    }
}
