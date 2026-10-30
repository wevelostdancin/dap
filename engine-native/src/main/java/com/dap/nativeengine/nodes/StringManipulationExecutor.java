package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;

import java.util.List;

/** dap.string_manipulation: Transforms one string column (upper/lower/trim/replace/concat) in place or as a new column. */
public final class StringManipulationExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.string_manipulation";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        throw new UnsupportedOperationException("dap.string_manipulation is not implemented yet");
    }
}
