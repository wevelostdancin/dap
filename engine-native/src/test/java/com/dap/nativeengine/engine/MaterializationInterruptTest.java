package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * /table and /profile materializations open their own connections; this test class covers the
 * handles that let close() and workflow cancellation interrupt them.
 */
class MaterializationInterruptTest {
    private static final Duration DEADLINE = Duration.ofSeconds(5);

    @Test
    void closingTheSessionInterruptsARunningProfile() throws Exception {
        DuckDbSession db = new DuckDbSession();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            LazyPlan plan = longPlan(db);
            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            Future<?> read = pool.submit(() -> {
                try {
                    plan.profile("wf-close", "node-1");
                    outcome.complete(null);
                } catch (Throwable failure) {
                    outcome.complete(failure);
                }
                return null;
            });
            awaitActiveReads(db, 1);
            // The plan's COUNT(*) scans a 4*10^10-row cross join; let it enter the scan so the
            // interruption happens while the query is running, not before it starts.
            Thread.sleep(100);
            assertFalse(read.isDone(), "the 4*10^10-row profile must still be running before close()");

            long started = System.nanoTime();
            db.close();
            Throwable failure = outcome.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertNotNull(failure, "closing the session must fail the in-flight profile, not let it complete");
            assertTrue(elapsedMs < DEADLINE.toMillis(), "profile was interrupted after " + elapsedMs + "ms");
            assertEquals(0, db.activeReadCount(), "close() must forget the interrupted reads");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void interruptReadsOnlyInterruptsTheMatchingWorkflow() throws Exception {
        DuckDbSession db = new DuckDbSession();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            LazyPlan first = longPlan(db);
            LazyPlan second = longPlan(db);
            CompletableFuture<Throwable> firstOutcome = new CompletableFuture<>();
            CompletableFuture<Throwable> secondOutcome = new CompletableFuture<>();
            pool.submit(() -> {
                try {
                    first.preview(1, "wf-a", "node-1");
                    firstOutcome.complete(null);
                } catch (Throwable failure) {
                    firstOutcome.complete(failure);
                }
                return null;
            });
            pool.submit(() -> {
                try {
                    second.preview(1, "wf-b", "node-1");
                    secondOutcome.complete(null);
                } catch (Throwable failure) {
                    secondOutcome.complete(failure);
                }
                return null;
            });
            awaitActiveReads(db, 2);
            Thread.sleep(100);
            assertFalse(firstOutcome.isDone(), "wf-a's read must be running before the interrupt");
            assertFalse(secondOutcome.isDone(), "wf-b's read must be running before the interrupt");

            db.interruptReads("wf-a");

            Throwable firstFailure = firstOutcome.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(firstFailure, "wf-a's read must be interrupted by interruptReads(\"wf-a\")");
            assertFalse(secondOutcome.isDone(), "wf-b's read must keep running");
            assertEquals(1, db.activeReadCount());

            db.close();
            Throwable secondFailure = secondOutcome.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(secondFailure, "closing the session must interrupt the remaining read");
            assertEquals(0, db.activeReadCount());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void smallPreviewAndProfileAreUnaffectedAndUnregistered() throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("city", "Utf8"));
        List<Object[]> rows = List.of(
                new Object[]{1L, "a"}, new Object[]{2L, ""}, new Object[]{3L, null});
        try (DuckDbSession db = new DuckDbSession();
             ArrowTable table = ArrowTable.of(columns, rows)) {
            String name = db.load(table, "small_reads");
            LazyPlan plan = new LazyPlan(db, "SELECT * FROM " + DuckDbSession.quote(name), columns);

            TableData tableData = plan.preview(10, "wf-small", "node-1");

            assertEquals(3, tableData.rows());
            assertEquals(3, tableData.preview());
            assertEquals(Arrays.asList(Arrays.asList("1", "a"), Arrays.asList("2", ""),
                    Arrays.asList("3", null)), tableData.data());
            assertEquals(0, db.activeReadCount(), "a finished preview must unregister its read");

            ProfileData profile = plan.profile("wf-small", "node-1");

            assertEquals(3, profile.rows());
            assertEquals(3, profile.columns().get(0).distinct());
            assertEquals(1, profile.columns().get(1).missing());
            assertEquals(0, db.activeReadCount(), "a finished profile must unregister its read");

            assertEquals(tableData.data(), plan.preview(10).data(),
                    "the legacy preview(int) signature must return the same rows");
            assertEquals(profile.columns(), plan.profile().columns(),
                    "the legacy profile() signature must return the same columns");
            assertEquals(0, db.activeReadCount());
        }
    }

    private static LazyPlan longPlan(DuckDbSession db) {
        return new LazyPlan(db,
                "SELECT a.range AS a, b.range AS b FROM range(200000) a, range(200000) b",
                List.of(ColumnSpec.of("a", "Int64"), ColumnSpec.of("b", "Int64")));
    }

    private static void awaitActiveReads(DuckDbSession db, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (db.activeReadCount() < expected) {
            if (System.nanoTime() > deadline) {
                fail("materializations did not start: active reads = " + db.activeReadCount());
            }
            Thread.sleep(10);
        }
    }
}
