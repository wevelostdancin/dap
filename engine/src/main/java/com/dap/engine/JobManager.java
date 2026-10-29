package com.dap.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Асинхронные задания DAP: запуск workflow, статус, отмена, таймаут.
 * Исполнение сериализовано одним worker-потоком (изоляция JVM-движка),
 * таймаут контролируется watchdog-потоком.
 */
final class JobManager {

    enum State {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMEOUT
    }

    static final class Job {
        final String id;
        final String session;
        final String workflowId;
        final long createdAt = System.currentTimeMillis();
        final Callable<ObjectNode> task;
        final Runnable cancelAction;
        final long timeoutSec;
        volatile State state = State.QUEUED;
        volatile long startedAt;
        volatile long finishedAt;
        volatile ObjectNode result;
        volatile String error;
        volatile boolean timeout;
        volatile boolean cancelRequested;
        Future<?> future;
        ScheduledFuture<?> watchdog;

        Job(final String id, final String session, final String workflowId,
                final long timeoutSec, final Callable<ObjectNode> task, final Runnable cancelAction) {
            this.id = id;
            this.session = session;
            this.workflowId = workflowId;
            this.timeoutSec = timeoutSec;
            this.task = task;
            this.cancelAction = cancelAction;
        }

        boolean active() {
            return state == State.QUEUED || state == State.RUNNING;
        }
    }

    private final ObjectMapper mapper;
    private final ExecutorService worker;
    private final ScheduledExecutorService watchdog;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final int maxJobs;

    JobManager(final ObjectMapper mapper, final int maxJobs) {
        this.mapper = mapper;
        this.maxJobs = maxJobs;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "dap-job-worker");
            t.setDaemon(true);
            return t;
        });
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "dap-job-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    synchronized Job submit(final String session, final String workflowId, final long timeoutSec,
            final Callable<ObjectNode> task, final Runnable cancelAction) {
        final long active = jobs.values().stream().filter(Job::active).count();
        if (active >= maxJobs) {
            throw new IllegalStateException("too many active jobs: " + active + " (max " + maxJobs + ")");
        }
        final Job job = new Job("job-" + seq.incrementAndGet(), session, workflowId,
                timeoutSec <= 0 ? 600 : timeoutSec, task, cancelAction);
        jobs.put(job.id, job);
        job.future = worker.submit(() -> run(job));
        job.watchdog = watchdog.schedule(() -> timeout(job), job.timeoutSec, TimeUnit.SECONDS);
        return job;
    }

    private void run(final Job job) {
        if (job.state == State.CANCELLED || job.timeout) {
            finish(job, job.timeout ? State.TIMEOUT : State.CANCELLED, null, "cancelled before start");
            return;
        }
        job.state = State.RUNNING;
        job.startedAt = System.currentTimeMillis();
        try {
            final ObjectNode result = job.task.call();
            job.result = result;
            finish(job, job.timeout ? State.TIMEOUT : State.SUCCEEDED, null, null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(job, job.timeout ? State.TIMEOUT : State.CANCELLED, null, "interrupted");
        } catch (final java.util.concurrent.CancellationException e) {
            finish(job, job.timeout ? State.TIMEOUT : State.CANCELLED, null, "cancelled");
        } catch (final Throwable t) {
            Throwable cause = t;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            finish(job, State.FAILED, null, cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
        }
    }

    private synchronized void finish(final Job job, final State state, final ObjectNode result,
            final String error) {
        if (job.state == State.SUCCEEDED || job.state == State.FAILED
                || job.state == State.CANCELLED || job.state == State.TIMEOUT) {
            return;
        }
        job.state = state;
        job.finishedAt = System.currentTimeMillis();
        if (result != null) {
            job.result = result;
        }
        if (error != null && job.error == null) {
            job.error = error;
        }
        if (job.watchdog != null) {
            job.watchdog.cancel(false);
        }
    }

    Job get(final String id, final String session) {
        final Job job = jobs.get(id);
        return job != null && job.session.equals(session) ? job : null;
    }

    List<Job> list(final String session) {
        final List<Job> result = new ArrayList<>();
        for (final Job job : jobs.values()) {
            if (job.session.equals(session)) {
                result.add(job);
            }
        }
        result.sort(Comparator.comparingLong((Job j) -> j.createdAt).reversed());
        return result;
    }

    boolean cancel(final String id, final String session) {
        final Job job = get(id, session);
        if (job == null || !job.active()) {
            return false;
        }
        job.cancelRequested = true;
        if (job.state == State.QUEUED) {
            finish(job, State.CANCELLED, null, "cancelled before start");
        }
        cancelExecution(job);
        return true;
    }

    private void timeout(final Job job) {
        if (!job.active() || job.timeout) {
            return;
        }
        job.timeout = true;
        if (job.state == State.QUEUED) {
            finish(job, State.TIMEOUT, null, "timeout before start");
        }
        cancelExecution(job);
    }

    private void cancelExecution(final Job job) {
        try {
            if (job.cancelAction != null) {
                job.cancelAction.run();
            }
        } catch (final Throwable ignored) {
            // отмена — best effort
        }
        if (job.future != null) {
            job.future.cancel(true);
        }
    }

    /** Синхронное ожидание для обратной совместимости POST /run. */
    ObjectNode await(final Job job, final long seconds) {
        try {
            job.future.get(seconds, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            job.timeout = true;
            cancelExecution(job);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            cancel(job.id, job.session);
        } catch (final ExecutionException | java.util.concurrent.CancellationException ignored) {
            // состояние уже выставлено worker-потоком
        }
        return json(job, true);
    }

    ObjectNode json(final Job job, final boolean includeResult) {
        final ObjectNode o = mapper.createObjectNode();
        o.put("id", job.id);
        o.put("workflow", job.workflowId);
        o.put("state", job.state.name().toLowerCase(java.util.Locale.ENGLISH));
        o.put("createdAt", job.createdAt);
        if (job.startedAt > 0) {
            o.put("startedAt", job.startedAt);
        }
        if (job.finishedAt > 0) {
            o.put("finishedAt", job.finishedAt);
            o.put("durationMs", job.finishedAt - (job.startedAt > 0 ? job.startedAt : job.createdAt));
        }
        o.put("timeoutSec", job.timeoutSec);
        if (job.error != null) {
            o.put("error", job.error);
        }
        if (includeResult && job.result != null) {
            o.set("result", job.result);
        }
        return o;
    }

    ArrayNode listJson(final String session) {
        final ArrayNode arr = mapper.createArrayNode();
        for (final Job job : list(session)) {
            arr.add(json(job, false));
        }
        return arr;
    }

    void shutdown() {
        for (final Job job : jobs.values()) {
            if (job.active()) {
                cancelExecution(job);
            }
        }
        worker.shutdownNow();
        watchdog.shutdownNow();
    }
}
