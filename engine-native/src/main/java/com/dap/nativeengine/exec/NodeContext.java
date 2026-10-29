package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.DuckDbSession;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;

/** Everything an executor needs: its config, the session database, the job connection. */
public record NodeContext(String workflowId, String nodeId, String sessionId, ObjectNode config,
                          DuckDbSession db, Connection jobConnection) {
    public NodeContext {
        if (config == null) {
            config = com.dap.nativeengine.api.Json.object();
        }
    }
}
