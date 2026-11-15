package com.dap.nativeengine.exec;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One workflow execution attempt. State transitions are synchronized and one-way. */
public final class Job {
    private final String id;
    private final String workflowId;
    private final String sessionId;
    private final long createdAt = System.currentTimeMillis();
    private final int timeoutSec;
    private final CountDownLatch done = new CountDownLatch(1);

    private volatile JobState state = JobState.queued;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile String error;
    private volatile RunResult result;
    private volatile int nodesTotal;
    private volatile int nodesDone;
    private volatile String currentNode;
    private volatile boolean cancelRequested;
    private volatile boolean timeoutRequested;

    public Job(String id, String workflowId, String sessionId, int timeoutSec) {
        this.id = id;
        this.workflowId = workflowId;
        this.sessionId = sessionId;
        this.timeoutSec = timeoutSec;
    }

    public String id() {
        return id;
    }

    public String workflowId() {
        return workflowId;
    }

    public String sessionId() {
        return sessionId;
    }

    public JobState state() {
        return state;
    }

    public long createdAt() {
        return createdAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public int timeoutSec() {
        return timeoutSec;
    }

    public String error() {
        return error;
    }

    public RunResult result() {
        return result;
    }

    public String statusUrl() {
        return "/v1/jobs/" + id;
    }

    public int nodesTotal() {
        return nodesTotal;
    }

    public int nodesDone() {
        return nodesDone;
    }

    /** Node that started or just finished; null before the run and between levels. */
    public String currentNode() {
        return currentNode;
    }

    /** Whole-percent progress; 100 once the job is terminal and had nodes. */
    public int percent() {
        int total = nodesTotal;
        if (total <= 0) {
            return state.terminal() ? 100 : 0;
        }
        return Math.min(100, nodesDone * 100 / total);
    }

    /** Dag progress callback: called when a node starts and when one finishes. */
    public synchronized void progress(int total, int done, String current) {
        this.nodesTotal = total;
        this.nodesDone = done;
        this.currentNode = current;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public boolean timeoutRequested() {
        return timeoutRequested;
    }

    public long deadlineMillis() {
        return createdAt + timeoutSec * 1000L;
    }

    public synchronized boolean start() {
        if (state != JobState.queued) {
            return false;
        }
        state = JobState.running;
        startedAt = System.currentTimeMillis();
        return true;
    }

    public synchronized boolean complete(JobState terminal, RunResult result, String error) {
        if (state.terminal()) {
            return false;
        }
        this.state = terminal;
        this.result = result;
        this.error = error;
        this.finishedAt = System.currentTimeMillis();
        // A finished run is fully done even if the last progress callback was lost.
        if (nodesTotal > 0) {
            nodesDone = nodesTotal;
        }
        currentNode = null;
        done.countDown();
        return true;
    }

    public synchronized boolean requestCancel() {
        if (state.terminal()) {
            return false;
        }
        cancelRequested = true;
        return true;
    }

    public synchronized boolean requestTimeout() {
        if (state.terminal()) {
            return false;
        }
        timeoutRequested = true;
        return true;
    }

    public void await() throws InterruptedException {
        done.await();
    }

    public boolean await(long millis) throws InterruptedException {
        return done.await(millis, TimeUnit.MILLISECONDS);
    }

    public long durationMs() {
        long start = startedAt;
        if (start == 0) {
            return 0;
        }
        long end = finishedAt == 0 ? System.currentTimeMillis() : finishedAt;
        return Math.max(0, end - start);
    }
}
