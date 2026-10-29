package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.DuckDbSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Job lifecycle: success, failure, cancellation, timeout and session scoping. */
class JobManagerTest {
    private DuckDbSession db;
    private WorkflowStore workflows;
    private Executors registry;
    private JobManager jobs;

    @BeforeEach
    void setUp() throws Exception {
        db = new DuckDbSession();
        workflows = new WorkflowStore(20);
        registry = new Executors()
                .register(ExecTestSupport.fake("dap.one", (context, inputs) ->
                        ExecTestSupport.plan(db, "SELECT 1 AS x", "x")));
        jobs = new JobManager(workflows, registry);
    }

    @AfterEach
    void tearDown() {
        if (jobs != null) {
            jobs.close();
        }
        if (db != null) {
            db.close();
        }
    }

    private Workflow singleNodeWorkflow(String session, String factory) {
        Workflow workflow = workflows.create(session, "wf");
        workflow.addNode(factory, factory, null);
        return workflow;
    }

    private static void awaitClockPast(long millis) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.currentTimeMillis() <= millis && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(System.currentTimeMillis() > millis, "wall clock did not advance");
    }

    @Test
    void successProducesRunResultWithExecutedNodes() throws Exception {
        Workflow workflow = singleNodeWorkflow("s1", "dap.one");

        Job job = jobs.submit(workflow, db, 0);
        assertTrue(job.await(10_000), "job did not finish");
        assertTrue(job.id().matches("job-\\d+"), job.id());
        assertEquals(600, job.timeoutSec(), "non-positive timeout must default to 600s");
        assertEquals(JobState.succeeded, job.state());
        assertNull(job.error());
        assertNotNull(job.result());
        assertTrue(job.result().ok());
        assertEquals(workflow.id(), job.result().workflow());
        assertEquals(1, job.result().nodes().size());
        NodeStateView view = job.result().nodes().get(0);
        assertEquals(workflow.nodes().get(0).id(), view.node());
        assertEquals("EXECUTED", view.state());
        assertTrue(job.result().durationMs() >= 0);
        assertEquals(0, jobs.activeCount("s1"));
        assertEquals(1, jobs.totalCount());
    }

    @Test
    void failureProducesFailedJobAndErrorNode() throws Exception {
        registry.register(ExecTestSupport.fake("dap.boom", (context, inputs) -> {
            throw new IllegalStateException("kaboom");
        }));
        Workflow workflow = singleNodeWorkflow("s1", "dap.boom");

        Job job = jobs.submit(workflow, db, 5);
        assertTrue(job.await(10_000), "job did not finish");

        assertEquals(JobState.failed, job.state());
        assertNull(job.result());
        assertEquals("kaboom", job.error());
        assertEquals("ERROR", workflow.nodes().get(0).state());
        assertEquals("kaboom", workflow.nodes().get(0).message());
    }

    @Test
    void cancelStopsLongRunningJob() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        registry.register(ExecTestSupport.fake("dap.block", (context, inputs) -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return ExecTestSupport.plan(db, "SELECT 1 AS x", "x");
        }));
        Workflow workflow = singleNodeWorkflow("s1", "dap.block");
        Job job = jobs.submit(workflow, db, 30);
        assertTrue(started.await(5, TimeUnit.SECONDS), "job did not start");
        assertEquals(1, jobs.activeCount("s1"));

        assertTrue(jobs.cancel(job), "running job should be cancellable");
        assertTrue(job.await(10_000), "cancelled job did not finish");
        assertEquals(JobState.cancelled, job.state());
        assertNull(job.result());
        assertEquals(0, jobs.activeCount("s1"));
    }

    @Test
    void watchdogTurnsTimeoutIntoTimeoutState() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        registry.register(ExecTestSupport.fake("dap.slow", (context, inputs) -> {
            started.countDown();
            never.await();
            return ExecTestSupport.plan(db, "SELECT 1 AS x", "x");
        }));
        Workflow workflow = singleNodeWorkflow("s1", "dap.slow");

        Job job = jobs.submit(workflow, db, 1);
        assertTrue(started.await(5, TimeUnit.SECONDS), "job did not start");
        assertTrue(job.await(10_000), "timed out job did not finish");

        assertEquals(JobState.timeout, job.state());
        assertNull(job.result());
        assertEquals("timeout", job.error());
        assertEquals(0, jobs.activeCount("s1"));
    }

    @Test
    void findAndListAreSessionScopedAndNewestFirst() throws Exception {
        Workflow firstWorkflow = singleNodeWorkflow("s1", "dap.one");
        Job first = jobs.submit(firstWorkflow, db, 5);
        assertTrue(first.await(10_000));
        awaitClockPast(first.createdAt());
        Workflow secondWorkflow = singleNodeWorkflow("s1", "dap.one");
        Job second = jobs.submit(secondWorkflow, db, 5);
        assertTrue(second.await(10_000));
        Workflow otherWorkflow = singleNodeWorkflow("s2", "dap.one");
        Job other = jobs.submit(otherWorkflow, db, 5);
        assertTrue(other.await(10_000));

        assertSame(first, jobs.find("s1", first.id()));
        assertSame(other, jobs.find("s2", other.id()));
        assertNull(jobs.find("s2", first.id()));
        assertNull(jobs.find("s1", "job-does-not-exist"));
        assertEquals(List.of(second, first), jobs.list("s1"));
        assertEquals(List.of(other), jobs.list("s2"));
        assertTrue(jobs.list("s3").isEmpty());
        assertEquals(3, jobs.totalCount());
        assertEquals(0, jobs.activeCount("s2"));
    }

    @Test
    void cancelOnFinishedJobReturnsFalse() throws Exception {
        registry.register(ExecTestSupport.fake("dap.fail", (context, inputs) -> {
            throw new IllegalStateException("nope");
        }));
        Workflow workflow = singleNodeWorkflow("s1", "dap.fail");
        Job job = jobs.submit(workflow, db, 5);
        assertTrue(job.await(10_000));

        assertFalse(jobs.cancel(job));
        assertEquals(JobState.failed, job.state());
    }

    @Test
    void closeIsIdempotentAndCancelsActiveJobs() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        registry.register(ExecTestSupport.fake("dap.block", (context, inputs) -> {
            started.countDown();
            never.await();
            return ExecTestSupport.plan(db, "SELECT 1 AS x", "x");
        }));
        Workflow workflow = singleNodeWorkflow("s1", "dap.block");
        Job job = jobs.submit(workflow, db, 30);
        assertTrue(started.await(5, TimeUnit.SECONDS), "job did not start");

        jobs.close();
        jobs.close();

        assertTrue(job.await(5_000), "close must finish active jobs");
        assertEquals(JobState.cancelled, job.state());
        assertThrows(IllegalStateException.class, () -> jobs.submit(workflow, db, 5));
    }

    @Test
    void concurrentRunsOfSameWorkflowAreSerialized() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger overlaps = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        registry.register(ExecTestSupport.fake("dap.serial", (context, inputs) -> {
            int run = invocations.incrementAndGet();
            if (active.incrementAndGet() > 1) {
                overlaps.incrementAndGet();
            }
            events.add("start-" + run);
            try {
                if (run == 1) {
                    firstStarted.countDown();
                    releaseFirst.await(10, TimeUnit.SECONDS);
                }
                return ExecTestSupport.plan(db, "SELECT " + run + " AS x", "x");
            } finally {
                active.decrementAndGet();
                events.add("end-" + run);
            }
        }));
        Workflow workflow = singleNodeWorkflow("s1", "dap.serial");
        Job first = jobs.submit(workflow, db, 10);
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS), "first run did not start");
        Job second = jobs.submit(workflow, db, 10);
        releaseFirst.countDown();
        assertTrue(first.await(10_000), "first run did not finish");
        assertTrue(second.await(10_000), "second run did not finish");

        assertEquals(JobState.succeeded, first.state());
        assertEquals(JobState.succeeded, second.state());
        assertEquals(0, overlaps.get(), "runs of the same workflow must not overlap");
        assertEquals(List.of("start-1", "end-1", "start-2", "end-2"), events);
        assertEquals(2, invocations.get());
        assertEquals("SELECT 2 AS x", workflow.nodes().get(0).result().sql());
        assertEquals("EXECUTED", workflow.nodes().get(0).state());
        for (Job job : List.of(first, second)) {
            NodeStateView view = job.result().nodes().get(0);
            assertEquals("EXECUTED", view.state());
        }
    }

    @Test
    void differentWorkflowsStillRunConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        registry.register(ExecTestSupport.fake("dap.parallel", (context, inputs) -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "different workflows must run concurrently");
            return ExecTestSupport.plan(db, "SELECT 1 AS x", "x");
        }));
        Workflow one = singleNodeWorkflow("s1", "dap.parallel");
        Workflow two = singleNodeWorkflow("s1", "dap.parallel");
        Job first = jobs.submit(one, db, 10);
        Job second = jobs.submit(two, db, 10);
        assertTrue(first.await(10_000), "first workflow did not finish");
        assertTrue(second.await(10_000), "second workflow did not finish");

        assertEquals(JobState.succeeded, first.state());
        assertEquals(JobState.succeeded, second.state());
    }
}
