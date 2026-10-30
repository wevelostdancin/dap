package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;

import java.util.List;

/** dap.group_by: Groups rows by key columns and aggregates per group, named like KNIME (Mean(x)). */
public final class GroupByExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.group_by";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        throw new UnsupportedOperationException("dap.group_by is not implemented yet");
    }
}
