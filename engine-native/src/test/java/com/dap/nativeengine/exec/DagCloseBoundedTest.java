package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.DuckDbSession;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dag.close() must not block forever on a node task that ignores interrupts. */
class DagCloseBoundedTest {
    @Test
    void closeIsBoundedWhenANodeIgnoresInterrupts() throws Exception {
        DuckDbSession db = new DuckDbSession();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Dag dag = new Dag(new Executors().register(ExecTestSupport.fake("dap.stubborn",
                (context, inputs) -> {
                    started.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = release.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // deliberately ignored: this node never gives up on its own
                        }
                    }
                    return ExecTestSupport.plan(db, "SELECT 1 AS x", "x");
                })));
        WorkflowStore store = new WorkflowStore(10);
        Workflow workflow = store.create("s1", "wf");
        workflow.addNode("dap.stubborn", "stubborn", null);
        ExecutorService caller = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            caller.submit(() -> {
                try {
                    dag.execute(workflow, db, db.newConnection());
                } catch (Exception ignored) {
                    // the pool is shut down under the run
                }
                return null;
            });
            assertTrue(started.await(10, TimeUnit.SECONDS), "the stubborn node did not start");

            long startedAt = System.nanoTime();
            dag.close();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertTrue(elapsedMs < 8_000, "Dag.close() must be bounded, took " + elapsedMs + "ms");
            assertTrue(elapsedMs >= 4_000, "Dag.close() must wait for its 5 s bound, took "
                    + elapsedMs + "ms");
        } finally {
            release.countDown();
            caller.shutdownNow();
            db.close();
        }
    }
}
