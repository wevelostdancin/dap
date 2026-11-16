package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;

import java.util.List;

/** Shared fakes for the exec scheduler tests. */
final class ExecTestSupport {
    private ExecTestSupport() {
    }

    @FunctionalInterface
    interface Body {
        LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception;
    }

    static NodeExecutor fake(String nodeId, Body body) {
        return new NodeExecutor() {
            @Override
            public String nodeId() {
                return nodeId;
            }

            @Override
            public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
                return body.execute(context, inputs);
            }
        };
    }

    static LazyPlan plan(DuckDbSession db, String sql, String column) {
        return new LazyPlan(db, sql, List.of(ColumnSpec.of(column, "Int64")));
    }
}
