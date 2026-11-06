package com.dap.nativeengine.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory workflows isolated per session. Ids are process-wide (wf-N). */
public final class WorkflowStore {
    private final int maxPerSession;
    private final AtomicInteger ids = new AtomicInteger();
    private final ConcurrentHashMap<String, LinkedHashMap<String, Workflow>> sessions = new ConcurrentHashMap<>();

    public WorkflowStore(int maxPerSession) {
        this.maxPerSession = maxPerSession;
    }

    public Workflow create(String session, String name) {
        Workflow workflow = detached(session, name);
        add(session, workflow);
        return workflow;
    }

    /** Allocate an id and name without publishing the workflow to the session. */
    public Workflow detached(String session, String name) {
        String id = "wf-" + ids.incrementAndGet();
        return new Workflow(id, name == null || name.isBlank() ? "workflow-" + ids.get() : name, session);
    }

    /** Publish a detached workflow; the per-session limit is enforced here. */
    public void add(String session, Workflow workflow) {
        LinkedHashMap<String, Workflow> workflows =
                sessions.computeIfAbsent(session, ignored -> new LinkedHashMap<>());
        synchronized (workflows) {
            if (workflows.size() >= maxPerSession) {
                throw new IllegalStateException("workflow limit for session");
            }
            workflows.put(workflow.id(), workflow);
        }
    }

    public List<Workflow> list(String session) {
        LinkedHashMap<String, Workflow> workflows = sessions.get(session);
        if (workflows == null) {
            return List.of();
        }
        synchronized (workflows) {
            return new ArrayList<>(workflows.values());
        }
    }

    public Workflow find(String session, String id) {
        LinkedHashMap<String, Workflow> workflows = sessions.get(session);
        if (workflows == null) {
            return null;
        }
        synchronized (workflows) {
            return workflows.get(id);
        }
    }

    public List<Workflow> all() {
        List<Workflow> result = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<String, Workflow>> entry : sessions.entrySet()) {
            synchronized (entry.getValue()) {
                result.addAll(entry.getValue().values());
            }
        }
        return result;
    }

    public boolean delete(String session, String id) {
        LinkedHashMap<String, Workflow> workflows = sessions.get(session);
        if (workflows == null) {
            return false;
        }
        synchronized (workflows) {
            return workflows.remove(id) != null;
        }
    }

    public int count() {
        int total = 0;
        for (LinkedHashMap<String, Workflow> workflows : sessions.values()) {
            synchronized (workflows) {
                total += workflows.size();
            }
        }
        return total;
    }

    public int count(String session) {
        LinkedHashMap<String, Workflow> workflows = sessions.get(session);
        if (workflows == null) {
            return 0;
        }
        synchronized (workflows) {
            return workflows.size();
        }
    }

    public int sessionCount() {
        return sessions.size();
    }

    public Set<String> sessions() {
        return Set.copyOf(sessions.keySet());
    }
}
