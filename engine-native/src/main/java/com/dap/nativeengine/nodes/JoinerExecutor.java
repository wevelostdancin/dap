package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;

import java.util.List;

/** dap.joiner: Joins two inputs on key columns (inner/left/right) with optional merged keys. */
public final class JoinerExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.joiner";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        throw new UnsupportedOperationException("dap.joiner is not implemented yet");
    }
}
