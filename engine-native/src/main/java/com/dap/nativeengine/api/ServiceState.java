package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.exec.Executors;
import com.dap.nativeengine.exec.JobManager;
import com.dap.nativeengine.exec.Workflow;
import com.dap.nativeengine.exec.WorkflowStore;

import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide resources of one service: catalog, workflow store, job manager and one
 * DuckDB database per session. Handlers share this object; the service owns its lifetime.
 */
public final class ServiceState implements AutoCloseable {
    private final NativeService.Settings settings;
    private final NodeRegistry registry;
    private final WorkflowStore workflows;
    private final JobManager jobs;
    private final ConcurrentHashMap<String, DuckDbSession> sessions = new ConcurrentHashMap<>();

    public ServiceState(NativeService.Settings settings, NodeRegistry registry, Executors executors) {
        this.settings = settings;
        this.registry = registry;
        this.workflows = new WorkflowStore(settings.maxWorkflows());
        this.jobs = new JobManager(workflows, executors);
    }

    public NativeService.Settings settings() {
        return settings;
    }

    public NodeRegistry registry() {
        return registry;
    }

    public WorkflowStore workflows() {
        return workflows;
    }

    public JobManager jobs() {
        return jobs;
    }

    /** Session database, created on first use and closed with the service. */
    public DuckDbSession session(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> {
            try {
                return new DuckDbSession();
            } catch (SQLException failure) {
                throw new ApiException(500, "cannot open session database: " + failure.getMessage(), failure);
            }
        });
    }

    /** Existing session database, or null; unlike session(id) this never creates one. */
    public DuckDbSession sessionIfPresent(String sessionId) {
        return sessions.get(sessionId);
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** Resolve a workflow or fail with the frozen 404 for unknown and foreign ids alike. */
    public Workflow requireWorkflow(String sessionId, String workflowId) {
        Workflow workflow = workflows.find(sessionId, workflowId);
        if (workflow == null) {
            throw ApiException.notFound("unknown workflow: " + workflowId);
        }
        return workflow;
    }

    public void closeSessions() {
        for (DuckDbSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    @Override
    public void close() {
        jobs.close();
        closeSessions();
    }
}
