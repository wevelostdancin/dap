package com.dap.nativeengine.exec;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.api.ServiceState;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deleting a workflow must clear its run lock and interrupt its in-flight reads. */
class WorkflowLockCleanupTest {
    @Test
    void forgetWorkflowRemovesTheLock() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            WorkflowStore store = new WorkflowStore(10);
            JobManager jobs = new JobManager(store, new Executors().register(
                    ExecTestSupport.fake("dap.one", (context, inputs) ->
                            ExecTestSupport.plan(db, "SELECT 1 AS x", "x"))));
            try {
                Workflow workflow = store.create("s1", "wf");
                workflow.addNode("dap.one", "one", null);
                Job job = jobs.submit(workflow, db, 5);
                assertTrue(job.await(10_000), "job did not finish");
                assertTrue(jobs.hasWorkflowLock(workflow.id()),
                        "a run must register the workflow lock");

                jobs.forgetWorkflow(workflow.id());

                assertFalse(jobs.hasWorkflowLock(workflow.id()),
                        "forgetWorkflow must drop the lock of the deleted workflow");
            } finally {
                jobs.close();
            }
        }
    }

    @Test
    void deletingAWorkflowClearsItsLockAndTables() throws Exception {
        DuckDbSession planDb = new DuckDbSession();
        ServiceState state = new ServiceState(new NativeService.Settings(100, 600, "127.0.0.1", 0),
                new NodeRegistry(), new Executors().register(
                        ExecTestSupport.fake("dap.one", (context, inputs) ->
                                ExecTestSupport.plan(planDb, "SELECT 1 AS x", "x"))));
        try {
            Workflow workflow = state.workflows().create("s1", "wf");
            workflow.addNode("dap.one", "one", null);
            DuckDbSession db = state.session("s1");
            Job job = state.jobs().submit(workflow, db, 5);
            assertTrue(job.await(10_000), "job did not finish");
            String table = "csv_" + workflow.id().replaceAll("[^A-Za-z0-9_]", "_") + "_1_1";
            db.execute("CREATE TABLE " + DuckDbSession.quote(table) + " (x INTEGER)");
            assertTrue(state.jobs().hasWorkflowLock(workflow.id()));

            assertTrue(state.deleteWorkflow("s1", workflow.id()));

            assertNull(state.workflows().find("s1", workflow.id()), "the workflow must be gone");
            assertFalse(state.jobs().hasWorkflowLock(workflow.id()), "its run lock must be gone");
            assertEquals(0, tableCount(db, table), "its reader table must be dropped");
        } finally {
            state.close();
            planDb.close();
        }
    }

    @Test
    void deletingAWorkflowInterruptsItsInFlightRead() throws Exception {
        ServiceState state = new ServiceState(new NativeService.Settings(100, 600, "127.0.0.1", 0),
                new NodeRegistry(), new Executors());
        ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            Workflow workflow = state.workflows().create("s1", "wf");
            workflow.addNode("dap.one", "one", null);
            DuckDbSession db = state.session("s1");
            LazyPlan plan = new LazyPlan(db,
                    "SELECT a.range AS a, b.range AS b FROM range(200000) a, range(200000) b",
                    List.of(ColumnSpec.of("a", "Int64"), ColumnSpec.of("b", "Int64")));
            CompletableFuture<Throwable> outcome = new CompletableFuture<>();
            pool.submit(() -> {
                try {
                    plan.preview(1, workflow.id(), "1:1");
                    outcome.complete(null);
                } catch (Throwable failure) {
                    outcome.complete(failure);
                }
                return null;
            });
            awaitActiveReads(db, 1);

            assertTrue(state.deleteWorkflow("s1", workflow.id()));

            Throwable failure = outcome.get(10_000, TimeUnit.MILLISECONDS);
            assertNotNull(failure, "deleting a workflow must interrupt its in-flight /table read");
            assertEquals(0, db.activeReadCount());
        } finally {
            pool.shutdownNow();
            state.close();
        }
    }

    private static void awaitActiveReads(DuckDbSession db, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (db.activeReadCount() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the read did not register");
            }
            Thread.sleep(10);
        }
    }

    private static int tableCount(DuckDbSession db, String table) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '"
                             + table + "'")) {
            result.next();
            return result.getInt(1);
        }
    }
}
