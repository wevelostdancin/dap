package com.dap.nativeengine.engine;

import com.dap.nativeengine.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cancel must close only the reads of the job that was cancelled. A parallel, unowned
 * /table read of the same workflow (another client) must keep running; a timeout answers 408
 * without touching any other connection.
 */
class OwnedReadInterruptTest {
    private static final long DEADLINE_MILLIS = 10_000;

    @Test
    void cancellingAJobLeavesTheOtherClientsTableReadAlive() throws Exception {
        DuckDbSession db = new DuckDbSession();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            LazyPlan jobRead = longPlan(db);
            LazyPlan tableRead = longPlan(db);
            CompletableFuture<Throwable> jobOutcome = new CompletableFuture<>();
            CompletableFuture<Throwable> tableOutcome = new CompletableFuture<>();
            Future<?> job = pool.submit(() -> {
                db.setReadOwner("job-1");
                try {
                    jobRead.preview(1, "wf-a", "job-node");
                    jobOutcome.complete(null);
                } catch (Throwable failure) {
                    jobOutcome.complete(failure);
                }
                return null;
            });
            Future<?> table = pool.submit(() -> {
                try {
                    tableRead.preview(1, "wf-a", "table-node");
                    tableOutcome.complete(null);
                } catch (Throwable failure) {
                    tableOutcome.complete(failure);
                }
                return null;
            });
            awaitActiveReads(db, 2);
            assertFalse(job.isDone(), "the job read must still be running before the cancel");
            assertFalse(table.isDone(), "the /table read must still be running before the cancel");

            // Same call JobHandlers.cancel makes for a running job.
            db.interruptReads("wf-a", "job-1");

            assertNotNull(jobOutcome.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS),
                    "the cancelled job's own read must be interrupted");
            assertFalse(table.isDone(), "the other client's /table read must survive the cancel");
            assertEquals(1, db.activeReadCount());
            db.close();
            assertNotNull(tableOutcome.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS),
                    "closing the session must still interrupt the remaining read");
        } finally {
            pool.shutdownNow();
            db.close();
        }
    }

    @Test
    void timeoutAnswers408AndLeavesOtherReadsUntouched() throws Exception {
        DuckDbSession db = new DuckDbSession();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            LazyPlan sibling = longPlan(db);
            CompletableFuture<Throwable> siblingOutcome = new CompletableFuture<>();
            Future<?> running = pool.submit(() -> {
                try {
                    sibling.preview(1, "wf-b", "sibling");
                    siblingOutcome.complete(null);
                } catch (Throwable failure) {
                    siblingOutcome.complete(failure);
                }
                return null;
            });
            awaitActiveReads(db, 1);

            LazyPlan slow = longPlan(db);
            ApiException timeout = assertThrows(ApiException.class,
                    () -> slow.preview(1, 100L, "wf-b", "slow"));
            assertEquals(408, timeout.status());
            assertEquals("query_timeout", timeout.code());
            assertFalse(running.isDone(), "a timeout must not close another client's connection");
            assertEquals(1, db.activeReadCount());

            db.close();
            assertNotNull(siblingOutcome.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS));
        } finally {
            pool.shutdownNow();
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
