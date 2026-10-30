package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;

import java.util.List;

/** dap.excel_reader: Reads one worksheet of an .xlsx workbook into an Arrow table. */
public final class ExcelReaderExecutor implements NodeExecutor {
    @Override
    public String nodeId() {
        return "dap.excel_reader";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        throw new UnsupportedOperationException("dap.excel_reader is not implemented yet");
    }
}
