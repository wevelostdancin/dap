package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.DuckDbSession;

import java.io.InterruptedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Schedules workflow runs, enforces timeouts and supports cancellation. Runs of
 * the same workflow are serialized so their shared node state cannot interleave;
 * different workflows still run concurrently.
 */
public final class JobManager implements AutoCloseable {
    private static final int DEFAULT_TIMEOUT_SEC = 600;
    /** DAP_MAX_RETAINED_JOBS default; terminal history per session is capped here. */
    private static final int DEFAULT_MAX_RETAINED = 200;
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Comparator<Job> NEWEST_FIRST = Comparator.comparingLong(Job::createdAt).reversed()
            .thenComparing(Job::id, JobManager::compareIdsNewestFirst);

    private final WorkflowStore workflows;
    private final Dag dag;
    private final int maxRetained;
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> workflowLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final ExecutorService runs = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchdog = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dap-job-watchdog");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean closed = new AtomicBoolean();

    public JobManager(WorkflowStore workflows, Executors executors) {
        this(workflows, executors, DEFAULT_MAX_RETAINED);
    }

    public JobManager(WorkflowStore workflows, Executors executors, int maxRetained) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
        this.dag = new Dag(Objects.requireNonNull(executors, "executors"));
        this.maxRetained = maxRetained;
    }

    public Job submit(Workflow workflow, DuckDbSession db, int timeoutSec) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(db, "db");
        if (closed.get()) {
            throw new IllegalStateException("job manager is closed");
        }
        Workflow tracked = workflows.find(workflow.session(), workflow.id());
        if (tracked != workflow) {
            throw new IllegalArgumentException("workflow '" + workflow.id() + "' is not managed by this store");
        }
        int timeout = timeoutSec <= 0 ? DEFAULT_TIMEOUT_SEC : timeoutSec;
        Job job = new Job("job-" + IDS.incrementAndGet(), workflow.id(), workflow.session(), timeout);
        // Leave room for the new job so the retained history never exceeds the cap, even
        // while the previous job's completion sweep is still running.
        trimHistory(job.sessionId(), Math.max(0, maxRetained - 1));
        jobs.put(job.id(), job);
        long delayMillis = Math.max(0L, job.deadlineMillis() - System.currentTimeMillis());
        timeouts.put(job.id(), watchdog.schedule(() -> onTimeout(job), delayMillis, TimeUnit.MILLISECONDS));
        Future<?> future = runs.submit(() -> run(job, workflow, db));
        futures.put(job.id(), future);
        if (job.state().terminal()) {
            futures.remove(job.id(), future);
        }
        return job;
    }

    public Job find(String session, String id) {
        Job job = jobs.get(id);
        return job != null && Objects.equals(session, job.sessionId()) ? job : null;
    }

    public List<Job> list(String session) {
        List<Job> matching = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (Objects.equals(session, job.sessionId())) {
                matching.add(job);
            }
        }
        matching.sort(NEWEST_FIRST);
        return matching;
    }

    public boolean cancel(Job job) {
        if (job == null || !job.requestCancel()) {
            return false;
        }
        Future<?> future = futures.remove(job.id());
        if (future != null) {
            future.cancel(true);
        }
        ScheduledFuture<?> timeout = timeouts.remove(job.id());
        if (timeout != null) {
            timeout.cancel(false);
        }
        closeJobConnection(job);
        if (job.state() == JobState.queued) {
            job.complete(JobState.cancelled, null, "cancelled");
            retain(job);
        }
        return true;
    }

    /** Sweep the completed job's session down to the cap. */
    private void retain(Job completed) {
        trimHistory(completed.sessionId(), maxRetained);
    }

    /**
     * Keep at most {@code limit} jobs of one session: the oldest terminal jobs are dropped
     * first, active jobs are never dropped.
     */
    private void trimHistory(String sessionId, int limit) {
        if (maxRetained <= 0) {
            return;
        }
        List<Job> sessionJobs = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (Objects.equals(job.sessionId(), sessionId)) {
                sessionJobs.add(job);
            }
        }
        int excess = sessionJobs.size() - limit;
        if (excess <= 0) {
            return;
        }
        sessionJobs.sort(Comparator.comparingLong(Job::createdAt).thenComparing(Job::id));
        for (Job job : sessionJobs) {
            if (excess <= 0) {
                break;
            }
            if (!job.state().terminal()) {
                continue;
            }
            if (jobs.remove(job.id(), job)) {
                futures.remove(job.id());
                timeouts.remove(job.id());
                excess--;
            }
        }
    }

    public void await(Job job) throws InterruptedException {
        Objects.requireNonNull(job, "job").await();
    }

    public List<Job> activeForWorkflow(String workflowId) {
        List<Job> active = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (job.workflowId().equals(workflowId) && !job.state().terminal()) {
                active.add(job);
            }
        }
        return active;
    }

    /** Non-terminal jobs across every session; the idle watchdog keeps a busy engine alive. */
    public int activeCount() {
        int active = 0;
        for (Job job : jobs.values()) {
            if (!job.state().terminal()) {
                active++;
            }
        }
        return active;
    }

    public int activeCount(String session) {
        int active = 0;
        for (Job job : jobs.values()) {
            if (!job.state().terminal() && Objects.equals(session, job.sessionId())) {
                active++;
            }
        }
        return active;
    }

    public int totalCount() {
        return jobs.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Job job : jobs.values()) {
            if (job.state().terminal()) {
                continue;
            }
            job.requestCancel();
            Future<?> future = futures.remove(job.id());
            if (future != null) {
                future.cancel(true);
            }
            ScheduledFuture<?> timeout = timeouts.remove(job.id());
            if (timeout != null) {
                timeout.cancel(false);
            }
            closeJobConnection(job);
            job.complete(JobState.cancelled, null, "cancelled");
        }
        watchdog.shutdownNow();
        runs.shutdownNow();
        awaitTermination(runs, 5);
        awaitTermination(watchdog, 5);
    }

    private void run(Job job, Workflow workflow, DuckDbSession db) {
        ReentrantLock lock = workflowLocks.computeIfAbsent(workflow.id(), ignored -> new ReentrantLock(true));
        boolean locked = false;
        try {
            if (job.state().terminal()) {
                return;
            }
            lock.lockInterruptibly();
            locked = true;
            if (job.state().terminal()) {
                return;
            }
            if (!job.start()) {
                return;
            }
            // Reset only after the job actually starts: a cancel that wins the queued
            // window must not wipe the results of the previous successful run.
            workflow.resetResults();
            Connection connection = db.newConnection();
            connections.put(job.id(), connection);
            dag.execute(workflow, db, connection);
            completeSuccess(job, workflow);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            completeInterrupted(job);
        } catch (InterruptedIOException interrupted) {
            Thread.currentThread().interrupt();
            completeInterrupted(job);
        } catch (Throwable failure) {
            completeFailure(job, failure);
        } finally {
            retain(job);
            futures.remove(job.id());
            ScheduledFuture<?> timeout = timeouts.remove(job.id());
            if (timeout != null) {
                timeout.cancel(false);
            }
            closeJobConnection(job);
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void onTimeout(Job job) {
        if (job.state().terminal() || !job.requestTimeout()) {
            return;
        }
        Future<?> future = futures.get(job.id());
        if (future != null) {
            future.cancel(true);
        }
        closeJobConnection(job);
        job.complete(JobState.timeout, null, "timeout");
        retain(job);
    }

    private static void completeSuccess(Job job, Workflow workflow) {
        if (job.timeoutRequested()) {
            job.complete(JobState.timeout, null, "timeout");
            return;
        }
        if (job.cancelRequested()) {
            job.complete(JobState.cancelled, null, "cancelled");
            return;
        }
        RunResult result = new RunResult(true, job.durationMs(), workflow.id(), nodeStates(workflow));
        job.complete(JobState.succeeded, result, null);
    }

    private static void completeInterrupted(Job job) {
        if (job.timeoutRequested()) {
            job.complete(JobState.timeout, null, "timeout");
        } else if (job.cancelRequested()) {
            job.complete(JobState.cancelled, null, "cancelled");
        } else {
            job.complete(JobState.failed, null, "InterruptedException");
        }
    }

    private static void completeFailure(Job job, Throwable failure) {
        if (job.timeoutRequested()) {
            job.complete(JobState.timeout, null, "timeout");
        } else if (job.cancelRequested()) {
            job.complete(JobState.cancelled, null, "cancelled");
        } else {
            job.complete(JobState.failed, null, message(failure));
        }
    }

    private static List<NodeStateView> nodeStates(Workflow workflow) {
        List<NodeStateView> views = new ArrayList<>();
        for (WorkflowNode node : workflow.nodes()) {
            views.add(new NodeStateView(node.id(), node.name(), node.state(), node.message()));
        }
        return views;
    }

    private void closeJobConnection(Job job) {
        Connection connection = connections.remove(job.id());
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {
                // best effort: closing a connection interrupts its running query
            }
        }
    }

    private static void awaitTermination(ExecutorService executor, int seconds) {
        try {
            if (!executor.awaitTermination(seconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static int compareIdsNewestFirst(String left, String right) {
        Long leftNumber = numericId(left);
        Long rightNumber = numericId(right);
        if (leftNumber != null && rightNumber != null) {
            return Long.compare(rightNumber, leftNumber);
        }
        return right == null ? (left == null ? 0 : -1) : right.compareTo(left);
    }

    private static Long numericId(String id) {
        if (id == null || !id.startsWith("job-")) {
            return null;
        }
        try {
            return Long.parseLong(id.substring(4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getName() : message;
    }
}
