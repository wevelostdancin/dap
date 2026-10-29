package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;

import java.sql.Connection;
import java.util.Map;

/** Executes a workflow graph. Implemented by the Phase 1 scheduler task. */
public final class Dag {
    private final Executors executors;

    public Dag(Executors executors) {
        this.executors = executors;
    }

    public Map<String, LazyPlan> execute(Workflow workflow, DuckDbSession db, Connection jobConnection) throws Exception {
        throw new UnsupportedOperationException("Dag.execute: Phase 1 scheduler task");
    }
}
