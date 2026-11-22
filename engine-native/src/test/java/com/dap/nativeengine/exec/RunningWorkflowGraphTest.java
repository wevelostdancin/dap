package com.dap.nativeengine.exec;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.DuckDbSession;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A running job owns its graph snapshot: structural edits get 409 until the job finishes. */
class RunningWorkflowGraphTest {
    private static final long DEADLINE_SEC = 10;

    @Test
    void nodeEditsAreRejectedDuringTheRunAndAllowedAfterIt() throws Exception {
        DuckDbSession db = new DuckDbSession();
        WorkflowStore store = new WorkflowStore(20);
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Executors registry = new Executors().register(ExecTestSupport.fake("dap.wait", (context, inputs) -> {
            executing.countDown();
            release.await();
            return ExecTestSupport.plan(db, "SELECT 1 AS x", "x");
        }));
        JobManager jobs = new JobManager(store, registry);
        try {
            Workflow workflow = store.create("s1", "wf");
            WorkflowNode node = workflow.addNode("dap.wait", "wait", null);
            Job job = jobs.submit(workflow, db, 0);
            assertTrue(executing.await(DEADLINE_SEC, TimeUnit.SECONDS), "the node never started");
            assertTrue(workflow.structureLocked(), "the run must freeze the graph");

            ApiException removed = assertThrows(ApiException.class, () -> workflow.removeNode(node.id()));
            assertEquals(409, removed.status());
            assertEquals("workflow_running", removed.code());
            ApiException patched = assertThrows(ApiException.class,
                    () -> workflow.setConfig(node.id(), Json.object()));
            assertEquals(409, patched.status());
            ApiException added = assertThrows(ApiException.class,
                    () -> workflow.addNode("dap.wait", "intruder", null));
            assertEquals(409, added.status());
            assertEquals(1, workflow.nodeCount(), "no edit may land in the executing snapshot");

            release.countDown();
            assertTrue(job.await(TimeUnit.SECONDS.toMillis(DEADLINE_SEC)), "the job never finished");
            assertEquals(JobState.succeeded, job.state());
            assertFalse(workflow.structureLocked(), "the lock must be released for the next edit");

            workflow.setConfig(node.id(), Json.object());
            workflow.addNode("dap.wait", "second", null);
            assertEquals(2, workflow.nodeCount());
        } finally {
            release.countDown();
            jobs.close();
            db.close();
        }
    }
}
