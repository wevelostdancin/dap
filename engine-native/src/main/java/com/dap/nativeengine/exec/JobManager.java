package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.DuckDbSession;

import java.util.List;

/** Schedules workflow runs, enforces timeouts and supports cancellation. */
public final class JobManager implements AutoCloseable {
    private final WorkflowStore workflows;
    private final Executors executors;

    public JobManager(WorkflowStore workflows, Executors executors) {
        this.workflows = workflows;
        this.executors = executors;
    }

    public Job submit(Workflow workflow, DuckDbSession db, int timeoutSec) {
        throw new UnsupportedOperationException("JobManager.submit: Phase 1 scheduler task");
    }

    public Job find(String session, String id) {
        throw new UnsupportedOperationException("JobManager.find: Phase 1 scheduler task");
    }

    public List<Job> list(String session) {
        throw new UnsupportedOperationException("JobManager.list: Phase 1 scheduler task");
    }

    public boolean cancel(Job job) {
        throw new UnsupportedOperationException("JobManager.cancel: Phase 1 scheduler task");
    }

    public int activeCount(String session) {
        throw new UnsupportedOperationException("JobManager.activeCount: Phase 1 scheduler task");
    }

    public int totalCount() {
        throw new UnsupportedOperationException("JobManager.totalCount: Phase 1 scheduler task");
    }

    @Override
    public void close() {
    }
}
