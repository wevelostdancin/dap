package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * interruptReads must take the reads lock before iterating open reads, so a read that is
 * registered before the cancel cannot slip past it.
 */
class ReadInterruptRaceTest {
    private static final long DEADLINE_MILLIS = 5_000;

    @Test
    void interruptReadsTakesTheReadsLockBeforeIterating() throws Exception {
        DuckDbSession db = new DuckDbSession();
        try {
            CountDownLatch held = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                synchronized (db.readsLock) {
                    held.countDown();
                    sleepQuietly(300);
                }
            }, "reads-lock-holder");
            holder.start();
            assertTrue(held.await(DEADLINE_MILLIS, TimeUnit.MILLISECONDS),
                    "the helper thread did not take the reads lock");

            long started = System.nanoTime();
            db.interruptReads("wf-a");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            holder.join(DEADLINE_MILLIS);

            assertTrue(elapsedMs >= 200, "interruptReads must take readsLock before iterating "
                    + "open reads, took " + elapsedMs + "ms");
        } finally {
            db.close();
        }
    }

    @Test
    void interruptedReadFailsAndUnregisters() throws Exception {
        DuckDbSession db = new DuckDbSession();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            LazyPlan plan = longPlan(db);
            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            Future<?> read = pool.submit(() -> {
                try {
                    plan.preview(1, "wf-a", "1:1");
                    outcome.complete(null);
                } catch (Throwable failure) {
                    outcome.complete(failure);
                }
                return null;
            });
            awaitActiveReads(db, 1);
            assertFalse(read.isDone(), "the read must still be running before the interrupt");

            db.interruptReads("wf-a");

            Throwable failure = outcome.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
            assertNotNull(failure, "an interrupted read must not complete");
            assertEquals(0, db.activeReadCount(), "the interrupted read must be unregistered");
        } finally {
            pool.shutdownNow();
            db.close();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
