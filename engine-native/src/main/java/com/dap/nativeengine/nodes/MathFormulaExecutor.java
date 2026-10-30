package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;

import java.util.List;

/** dap.math_formula: Adds columns computed by SQL expressions over the input columns. */
public final class MathFormulaExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.math_formula";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        throw new UnsupportedOperationException("dap.math_formula is not implemented yet");
    }
}
