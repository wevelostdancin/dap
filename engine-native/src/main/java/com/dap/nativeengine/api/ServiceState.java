package com.dap.nativeengine.api;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.exec.Executors;
import com.dap.nativeengine.exec.Job;
import com.dap.nativeengine.exec.JobManager;
import com.dap.nativeengine.exec.Workflow;
import com.dap.nativeengine.exec.WorkflowStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide resources of one service: catalog, workflow store, job manager and one
 * DuckDB database per session. Handlers share this object; the service owns its lifetime.
 * Sessions are tracked with their last access so idle ones can be evicted by TTL or by
 * the LRU cap.
 */
public final class ServiceState implements AutoCloseable {
    /** One session database plus its eviction bookkeeping. */
    private static final class Session {
        private final DuckDbSession database;
        private final long sequence;
        private volatile long lastAccess;

        private Session(DuckDbSession database, long sequence, long lastAccess) {
            this.database = database;
            this.sequence = sequence;
            this.lastAccess = lastAccess;
        }
    }

    /** One row of GET /v1/sessions. */
    public record SessionView(String id, long lastAccess, int workflows) {
    }

    private final NativeService.Settings settings;
    private final NodeRegistry registry;
    private final WorkflowStore workflows;
    private final JobManager jobs;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong sessionSequence = new AtomicLong();

    public ServiceState(NativeService.Settings settings, NodeRegistry registry, Executors executors) {
        this.settings = settings;
        this.registry = registry;
        this.workflows = new WorkflowStore(settings.maxWorkflows());
        this.jobs = new JobManager(workflows, executors, settings.maxRetainedJobs());
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

    /**
     * Session database, created on first use; the access refreshes its timestamp and
     * enforces the TTL/LRU limits, so a busy session is never the one evicted.
     */
    public DuckDbSession session(String sessionId) {
        Session session = sessions.computeIfAbsent(sessionId, id -> {
            try {
                return new Session(new DuckDbSession(settings.duckdbMemoryLimit(),
                        settings.duckdbThreads(), settings.duckdbTempDirectory(),
                        settings.duckdbExtensionDirectory()),
                        sessionSequence.incrementAndGet(), System.currentTimeMillis());
            } catch (SQLException failure) {
                throw new ApiException(500, "cannot open session database: " + failure.getMessage(),
                        failure);
            }
        });
        session.lastAccess = System.currentTimeMillis();
        evictIdle(session.lastAccess);
        return session.database;
    }

    /**
     * Refresh the TTL of an already open session without creating a database. Pure lookup and
     * job-history routes use this: a 404 must not leave a new session behind, and /debug must
     * still report configured DuckDB resources before the first data request.
     */
    public void touch(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.lastAccess = System.currentTimeMillis();
        }
    }

    /** Existing session database, or null; unlike session(id) this never creates one. */
    public DuckDbSession sessionIfPresent(String sessionId) {
        Session session = sessions.get(sessionId);
        return session == null ? null : session.database;
    }

    public int sessionCount() {
        return sessions.size();
    }

    /** Session views for GET /v1/sessions, least recently used first. */
    public List<SessionView> sessionViews() {
        List<Map.Entry<String, Session>> entries = new ArrayList<>(sessions.entrySet());
        entries.sort((left, right) -> compare(left.getValue(), right.getValue()));
        List<SessionView> views = new ArrayList<>(entries.size());
        for (Map.Entry<String, Session> entry : entries) {
            views.add(new SessionView(entry.getKey(), entry.getValue().lastAccess,
                    workflows.count(entry.getKey())));
        }
        return views;
    }

    /**
     * Close one session: stop its jobs and reads, drop its tables, forget its jobs/locks and
     * session key, then close its database. Returns false when the session is not open.
     */
    public boolean closeSession(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        for (Workflow workflow : workflows.list(sessionId)) {
            stopReadsAndDropTables(workflow.id(), session.database);
        }
        jobs.forgetSession(sessionId);
        session.database.close();
        return true;
    }

    /** Delete a workflow and everything it owns, using the same cleanup as session close. */
    public boolean deleteWorkflow(String sessionId, String workflowId) {
        Workflow workflow = workflows.find(sessionId, workflowId);
        if (workflow == null) {
            return false;
        }
        stopReadsAndDropTables(workflow.id(), sessionIfPresent(sessionId));
        jobs.forgetWorkflow(workflow.id());
        return workflows.delete(sessionId, workflowId);
    }

    /**
     * The shared part of workflow cleanup: cancel its jobs, interrupt its in-flight /table and
     * /profile reads, and drop its reader tables. A read on its own connection survives the job
     * cancel, so it must be interrupted before the table drop races a running scan.
     */
    private void stopReadsAndDropTables(String workflowId, DuckDbSession database) {
        for (Job job : jobs.activeForWorkflow(workflowId)) {
            jobs.cancel(job);
        }
        if (database != null) {
            database.interruptReads(workflowId);
            database.dropTablesForWorkflow(workflowId);
        }
    }

    /**
     * Close TTL-expired sessions and LRU-evict above the cap. Active jobs of an evicted
     * session are cancelled; the engine itself stays up.
     */
    public void evictIdle(long nowMillis) {
        long ttlMillis = settings.sessionTtlSec() * 1000L;
        if (ttlMillis > 0) {
            List<String> expired = new ArrayList<>();
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                if (nowMillis - entry.getValue().lastAccess >= ttlMillis) {
                    expired.add(entry.getKey());
                }
            }
            for (String sessionId : expired) {
                closeSession(sessionId);
            }
        }
        while (sessions.size() > settings.maxSessions()) {
            String oldest = null;
            Session oldestSession = null;
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                if (oldestSession == null || compare(entry.getValue(), oldestSession) < 0) {
                    oldest = entry.getKey();
                    oldestSession = entry.getValue();
                }
            }
            if (oldest == null || !closeSession(oldest)) {
                return;
            }
        }
    }

    /** Least recently used first; the creation sequence breaks equal timestamps. */
    private static int compare(Session left, Session right) {
        int byAccess = Long.compare(left.lastAccess, right.lastAccess);
        return byAccess != 0 ? byAccess : Long.compare(left.sequence, right.sequence);
    }

    /** Resolve a workflow or fail with the frozen 404 for unknown and foreign ids alike. */
    public Workflow requireWorkflow(String sessionId, String workflowId) {
        // Every workflow-scoped route is active use: refresh the TTL before the lookup so a
        // busy session cannot be evicted by its own watchdog between two requests.
        touch(sessionId);
        Workflow workflow = workflows.find(sessionId, workflowId);
        if (workflow == null) {
            throw ApiException.notFound("unknown workflow: " + workflowId);
        }
        return workflow;
    }

    public void closeSessions() {
        for (Session session : sessions.values()) {
            session.database.close();
        }
        sessions.clear();
    }

    @Override
    public void close() {
        jobs.close();
        closeSessions();
    }
}
