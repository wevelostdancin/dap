package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads started by a node executor inherit the job id through the DAG's virtual threads, so
 * cancelling that job closes them — but never the unowned /table read of another client.
 */
class JobReadOwnerTest {
    private static final long DEADLINE_MILLIS = 10_000;

    @Test
    void aJobsNodeReadIsOwnedByTheJobAndAnUnownedReadSurvivesItsCancel() throws Exception {
        DuckDbSession db = new DuckDbSession();
        WorkflowStore store = new WorkflowStore(20);
        Executors executors = new Executors().register(ExecTestSupport.fake("dap.slow", (context, inputs) -> {
            longPlan(db).preview(1, context.workflowId(), context.nodeId());
            return ExecTestSupport.plan(db, "SELECT 1 AS x", "x");
        }));
        JobManager jobs = new JobManager(store, executors);
        ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            Workflow workflow = store.create("s1", "wf");
            workflow.addNode("dap.slow", "slow", null);
            Job job = jobs.submit(workflow, db, 0);
            awaitActiveReads(db, 1);

            CompletableFuture<Throwable> tableOutcome = new CompletableFuture<>();
            Future<?> table = pool.submit(() -> {
                try {
                    longPlan(db).preview(1, workflow.id(), "table-node");
                    tableOutcome.complete(null);
                } catch (Throwable failure) {
                    tableOutcome.complete(failure);
                }
                return null;
            });
            awaitActiveReads(db, 2);

            // The handler's cancel path, scoped to the job that owns the node read.
            db.interruptReads(workflow.id(), job.id());

            assertTrue(job.await(DEADLINE_MILLIS), "the interrupted job must reach a terminal state");
            assertFalse(table.isDone(), "an unowned /table read must survive a job cancel");
            assertEquals(JobState.failed, job.state());
            assertEquals(1, db.activeReadCount());

            db.close();
            assertNotNull(tableOutcome.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS));
        } finally {
            pool.shutdownNow();
            jobs.close();
            db.close();
        }
    }

    private static LazyPlan longPlan(DuckDbSession db) {
        return new LazyPlan(db,
                "SELECT a.range AS a, b.range AS b FROM range(200000) a, range(200000) b",
                List.of(ColumnSpec.of("a", "Int64"), ColumnSpec.of("b", "Int64")));
    }

    private static void awaitActiveReads(DuckDbSession db, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
        while (db.activeReadCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("materializations did not start: active reads = "
                        + db.activeReadCount());
            }
            Thread.sleep(10);
        }
    }
}
