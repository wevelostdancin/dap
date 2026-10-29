package com.dap.nativeengine.exec;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Scheduler semantics: inputs, ordering, parallelism and failure handling. */
class DagTest {
    private DuckDbSession db;
    private Connection jobConnection;

    @BeforeEach
    void open() throws Exception {
        db = new DuckDbSession();
        jobConnection = db.newConnection();
    }

    @AfterEach
    void close() throws Exception {
        try {
            jobConnection.close();
        } finally {
            db.close();
        }
    }

    @Test
    void linearChainExecutesInInsertionOrderAndKeepsExecutorPlans() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        LazyPlan planA = ExecTestSupport.plan(db, "SELECT 1 AS a", "a");
        LazyPlan planB = ExecTestSupport.plan(db, "SELECT 2 AS b", "b");
        LazyPlan planC = ExecTestSupport.plan(db, "SELECT 3 AS c", "c");
        Executors registry = new Executors()
                .register(ExecTestSupport.fake("dap.a", (context, inputs) -> {
                    assertTrue(inputs.isEmpty(), "source node must receive an empty input list");
                    assertEquals("1:1", context.nodeId());
                    order.add("a");
                    return planA;
                }))
                .register(ExecTestSupport.fake("dap.b", (context, inputs) -> {
                    assertEquals(List.of(planA), inputs);
                    order.add("b");
                    return planB;
                }))
                .register(ExecTestSupport.fake("dap.c", (context, inputs) -> {
                    assertEquals(List.of(planB), inputs);
                    order.add("c");
                    return planC;
                }));
        Workflow workflow = new Workflow("wf-1", "linear", "s1");
        WorkflowNode a = workflow.addNode("dap.a", "A", null);
        WorkflowNode b = workflow.addNode("dap.b", "B", null);
        WorkflowNode c = workflow.addNode("dap.c", "C", null);
        workflow.connect(new com.dap.nativeengine.exec.Connection(a.id(), 0, b.id(), 0));
        workflow.connect(new com.dap.nativeengine.exec.Connection(b.id(), 0, c.id(), 0));

        Map<String, LazyPlan> results = new Dag(registry).execute(workflow, db, jobConnection);

        assertEquals(List.of("a", "b", "c"), order);
        assertEquals(List.of(a.id(), b.id(), c.id()), List.copyOf(results.keySet()));
        assertSame(planA, results.get(a.id()));
        assertSame(planB, results.get(b.id()));
        assertSame(planC, results.get(c.id()));
        assertEquals("SELECT 1 AS a", results.get(a.id()).sql());
        assertEquals("a", results.get(a.id()).columns().get(0).name());
        assertEquals("EXECUTED", workflow.node(c.id()).state());
    }

    @Test
    void diamondWaitsForBothInputs() throws Exception {
        List<String> executed = new CopyOnWriteArrayList<>();
        LazyPlan rootPlan = ExecTestSupport.plan(db, "SELECT 1 AS root", "root");
        LazyPlan leftPlan = ExecTestSupport.plan(db, "SELECT 2 AS l", "l");
        LazyPlan rightPlan = ExecTestSupport.plan(db, "SELECT 3 AS r", "r");
        LazyPlan joinPlan = ExecTestSupport.plan(db, "SELECT 4 AS joined", "joined");
        Executors registry = new Executors()
                .register(ExecTestSupport.fake("dap.root", (context, inputs) -> {
                    executed.add("root");
                    return rootPlan;
                }))
                .register(ExecTestSupport.fake("dap.left", (context, inputs) -> {
                    assertEquals(List.of(rootPlan), inputs);
                    executed.add("left");
                    return leftPlan;
                }))
                .register(ExecTestSupport.fake("dap.right", (context, inputs) -> {
                    assertEquals(List.of(rootPlan), inputs);
                    executed.add("right");
                    return rightPlan;
                }))
                .register(ExecTestSupport.fake("dap.join", (context, inputs) -> {
                    assertEquals(2, inputs.size());
                    assertSame(leftPlan, inputs.get(0));
                    assertSame(rightPlan, inputs.get(1));
                    assertTrue(executed.contains("left") && executed.contains("right"),
                            "join must not start before both inputs completed");
                    executed.add("join");
                    return joinPlan;
                }));
        Workflow workflow = new Workflow("wf-1", "diamond", "s1");
        WorkflowNode root = workflow.addNode("dap.root", "Root", null);
        WorkflowNode left = workflow.addNode("dap.left", "Left", null);
        WorkflowNode right = workflow.addNode("dap.right", "Right", null);
        WorkflowNode join = workflow.addNode("dap.join", "Join", null);
        workflow.connect(new com.dap.nativeengine.exec.Connection(root.id(), 0, left.id(), 0));
        workflow.connect(new com.dap.nativeengine.exec.Connection(root.id(), 0, right.id(), 0));
        workflow.connect(new com.dap.nativeengine.exec.Connection(left.id(), 0, join.id(), 0));
        workflow.connect(new com.dap.nativeengine.exec.Connection(right.id(), 0, join.id(), 1));

        Map<String, LazyPlan> results = new Dag(registry).execute(workflow, db, jobConnection);

        assertEquals(List.of(root.id(), left.id(), right.id(), join.id()), List.copyOf(results.keySet()));
        assertEquals("root", executed.get(0));
        assertEquals("join", executed.get(executed.size() - 1));
        assertSame(joinPlan, results.get(join.id()));
        assertEquals("EXECUTED", workflow.node(join.id()).state());
    }

    @Test
    void independentBranchesRunConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        Executors registry = new Executors()
                .register(ExecTestSupport.fake("dap.left", (context, inputs) -> {
                    bothStarted.countDown();
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS),
                            "independent branches were not running at the same time");
                    return ExecTestSupport.plan(db, "SELECT 1 AS l", "l");
                }))
                .register(ExecTestSupport.fake("dap.right", (context, inputs) -> {
                    bothStarted.countDown();
                    assertTrue(bothStarted.await(5, TimeUnit.SECONDS),
                            "independent branches were not running at the same time");
                    return ExecTestSupport.plan(db, "SELECT 1 AS r", "r");
                }));
        Workflow workflow = new Workflow("wf-1", "parallel", "s1");
        WorkflowNode left = workflow.addNode("dap.left", "Left", null);
        WorkflowNode right = workflow.addNode("dap.right", "Right", null);

        Map<String, LazyPlan> results = new Dag(registry).execute(workflow, db, jobConnection);

        assertEquals(List.of(left.id(), right.id()), List.copyOf(results.keySet()));
        assertEquals("EXECUTED", workflow.node(left.id()).state());
        assertEquals("EXECUTED", workflow.node(right.id()).state());
    }

    @Test
    void failingNodeIsMarkedErrorAndFailurePropagates() {
        Executors registry = new Executors()
                .register(ExecTestSupport.fake("dap.boom", (context, inputs) -> {
                    throw new IllegalStateException("boom");
                }))
                .register(ExecTestSupport.fake("dap.after", (context, inputs) ->
                        ExecTestSupport.plan(db, "SELECT 1 AS x", "x")));
        Workflow workflow = new Workflow("wf-1", "failure", "s1");
        WorkflowNode boom = workflow.addNode("dap.boom", "Boom", null);
        WorkflowNode after = workflow.addNode("dap.after", "After", null);
        workflow.connect(new com.dap.nativeengine.exec.Connection(boom.id(), 0, after.id(), 0));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new Dag(registry).execute(workflow, db, jobConnection));

        assertEquals("boom", failure.getMessage());
        assertEquals("ERROR", workflow.node(boom.id()).state());
        assertEquals("boom", workflow.node(boom.id()).message());
        assertEquals("IDLE", workflow.node(after.id()).state(), "downstream node must not run after a failure");
    }

    @Test
    void unknownExecutorIsRejectedBeforeAnythingRuns() {
        List<String> executed = new CopyOnWriteArrayList<>();
        Executors registry = new Executors()
                .register(ExecTestSupport.fake("dap.ok", (context, inputs) -> {
                    executed.add("ok");
                    return ExecTestSupport.plan(db, "SELECT 1 AS x", "x");
                }));
        Workflow workflow = new Workflow("wf-1", "unknown", "s1");
        workflow.addNode("dap.ok", "Ok", null);
        WorkflowNode unknown = workflow.addNode("dap.missing", "Missing", null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Dag(registry).execute(workflow, db, jobConnection));

        assertTrue(failure.getMessage().contains("dap.missing"), failure.getMessage());
        assertTrue(failure.getMessage().contains(unknown.id()), failure.getMessage());
        assertTrue(executed.isEmpty(), "validation must complete before execution");
    }

    @Test
    void unconnectedInputPortIsRejected() {
        Executors registry = new Executors()
                .register(ExecTestSupport.fake("dap.a", (context, inputs) ->
                        ExecTestSupport.plan(db, "SELECT 1 AS a", "a")))
                .register(ExecTestSupport.fake("dap.b", (context, inputs) ->
                        ExecTestSupport.plan(db, "SELECT 2 AS b", "b")));
        Workflow workflow = new Workflow("wf-1", "hole", "s1");
        WorkflowNode a = workflow.addNode("dap.a", "A", null);
        WorkflowNode b = workflow.addNode("dap.b", "B", null);
        workflow.connect(new com.dap.nativeengine.exec.Connection(a.id(), 0, b.id(), 1));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new Dag(registry).execute(workflow, db, jobConnection));

        assertTrue(failure.getMessage().contains(b.id()), failure.getMessage());
        assertTrue(failure.getMessage().contains("port 0"), failure.getMessage());
        assertEquals("IDLE", workflow.node(a.id()).state());
    }

    @Test
    void emptyWorkflowReturnsEmptyMap() throws Exception {
        Workflow workflow = new Workflow("wf-1", "empty", "s1");

        Map<String, LazyPlan> results = new Dag(new Executors()).execute(workflow, db, jobConnection);

        assertTrue(results.isEmpty());
    }

    @Test
    void failingSiblingInterruptsBlockedNodeAndFailsFast() {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Executors registry = new Executors()
                .register(ExecTestSupport.fake("dap.block", (context, inputs) -> {
                    blocked.countDown();
                    try {
                        new CountDownLatch(1).await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        throw exception;
                    }
                    return ExecTestSupport.plan(db, "SELECT 1 AS blocked", "blocked");
                }))
                .register(ExecTestSupport.fake("dap.fail", (context, inputs) -> {
                    assertTrue(blocked.await(5, TimeUnit.SECONDS), "blocked sibling did not start");
                    throw new IllegalStateException("sibling failure");
                }));
        Workflow workflow = new Workflow("wf-1", "interrupt", "s1");
        WorkflowNode blockedNode = workflow.addNode("dap.block", "Block", null);
        workflow.addNode("dap.fail", "Fail", null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new Dag(registry).execute(workflow, db, jobConnection));

        assertEquals("sibling failure", failure.getMessage());
        try {
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "blocked node did not observe interruption");
        } catch (InterruptedException exception) {
            throw new AssertionError("interrupted while waiting for the blocked node", exception);
        }
        assertEquals("ERROR", workflow.node(blockedNode.id()).state());
    }

    @Test
    void executionUsesSnapshotTakenBeforeMidRunEdits() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> configSeen = new AtomicReference<>();
        Executors registry = new Executors()
                .register(ExecTestSupport.fake("dap.gate", (context, inputs) -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return ExecTestSupport.plan(db, "SELECT 1 AS gate", "gate");
                }))
                .register(ExecTestSupport.fake("dap.check", (context, inputs) -> {
                    configSeen.set(context.config().path("value").asText());
                    return ExecTestSupport.plan(db, "SELECT 2 AS checked", "checked");
                }));
        Workflow workflow = new Workflow("wf-1", "snapshot", "s1");
        WorkflowNode gate = workflow.addNode("dap.gate", "Gate", null);
        ObjectNode config = Json.object();
        config.put("value", "before");
        WorkflowNode check = workflow.addNode("dap.check", "Check", config);
        workflow.connect(new com.dap.nativeengine.exec.Connection(gate.id(), 0, check.id(), 0));
        CompletableFuture<Map<String, LazyPlan>> execution = CompletableFuture.supplyAsync(() -> {
            try {
                return new Dag(registry).execute(workflow, db, jobConnection);
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS), "dag did not start");

        // Mutate the live graph while the snapshot run is in flight.
        workflow.setConfig(check.id(), Json.object().put("value", "after"));
        WorkflowNode late = workflow.addNode("dap.late", "Late", null);
        workflow.connect(new com.dap.nativeengine.exec.Connection(gate.id(), 0, late.id(), 0));
        release.countDown();

        Map<String, LazyPlan> results = execution.get(10, TimeUnit.SECONDS);
        assertEquals(List.of(gate.id(), check.id()), List.copyOf(results.keySet()));
        assertEquals("before", configSeen.get(), "downstream node must use the snapshot config");
        assertEquals("after", workflow.node(check.id()).config().path("value").asText());
        assertEquals("IDLE", workflow.node(late.id()).state(), "node added mid-run must not execute");
    }
}
