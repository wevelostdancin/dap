package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.LazyPlan;

import java.util.List;

/** Native executor for one node type. Inputs are indexed by data port (null if unconnected). */
public interface NodeExecutor {
    String nodeId();

    LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception;
}
