package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes a workflow graph snapshot in topological levels. Every node of a
 * level starts concurrently on its own virtual thread; the next level starts
 * only after the whole previous level finished, so a node sees all of its
 * inputs as plans. Structural edits made after the snapshot was taken do not
 * affect the running execution.
 */
public final class Dag implements AutoCloseable {
    /** Bounded shutdown: a node task that ignores interrupts must not block close(). */
    private static final int CLOSE_TIMEOUT_SEC = 5;

    private final Executors executors;
    private final ExecutorService pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    public Dag(Executors executors) {
        this.executors = executors;
    }

    @Override
    public void close() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(CLOSE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    public Map<String, LazyPlan> execute(Workflow workflow, DuckDbSession db, java.sql.Connection jobConnection)
            throws Exception {
        return execute(workflow, db, jobConnection, null);
    }

    /** Same as the three-argument execute, reporting node progress to the listener. */
    public Map<String, LazyPlan> execute(Workflow workflow, DuckDbSession db, java.sql.Connection jobConnection,
                                         ProgressListener progress) throws Exception {
        Workflow.Graph graph = workflow.snapshot();
        List<WorkflowNode> nodes = graph.nodes();
        List<Connection> connections = graph.connections();
        Map<String, ObjectNode> configs = graph.configs();
        if (nodes.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, List<Connection>> incoming = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            incoming.put(node.id(), new ArrayList<>());
            outgoing.put(node.id(), new ArrayList<>());
        }
        for (Connection connection : connections) {
            List<Connection> targets = incoming.get(connection.to());
            if (targets == null) {
                throw new IllegalArgumentException("connection target is not a workflow node: " + connection.to());
            }
            if (!outgoing.containsKey(connection.from())) {
                throw new IllegalArgumentException("connection source is not a workflow node: " + connection.from());
            }
            targets.add(connection);
            outgoing.get(connection.from()).add(connection.to());
        }

        Map<String, NodeExecutor> resolved = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            NodeExecutor executor = executors.get(node.factory());
            if (executor == null) {
                throw new IllegalArgumentException(
                        "no executor registered for node '" + node.id() + "' factory '" + node.factory() + "'");
            }
            resolved.put(node.id(), executor);
            validateInputPorts(node, incoming.get(node.id()));
        }

        int total = nodes.size();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        if (progress != null) {
            progress.progress(total, 0, null);
        }

        Map<String, Integer> remaining = new LinkedHashMap<>();
        List<String> current = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            int dependencies = incoming.get(node.id()).size();
            remaining.put(node.id(), dependencies);
            if (dependencies == 0) {
                current.add(node.id());
            }
        }

        Map<String, LazyPlan> results = new LinkedHashMap<>();
        while (!current.isEmpty()) {
            Map<String, List<LazyPlan>> inputs = new LinkedHashMap<>();
            for (String nodeId : current) {
                inputs.put(nodeId, inputPlans(nodeId, incoming.get(nodeId), results));
            }
            Map<String, Future<LazyPlan>> level = new LinkedHashMap<>();
            List<Future<LazyPlan>> pending = new CopyOnWriteArrayList<>();
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            for (String nodeId : current) {
                List<LazyPlan> nodeInputs = inputs.get(nodeId);
                List<String> nodeInputIds = inputNodeIds(incoming.get(nodeId));
                NodeExecutor executor = resolved.get(nodeId);
                ObjectNode config = configs.get(nodeId);
                Future<?>[] self = new Future<?>[1];
                Future<LazyPlan> future = pool.submit((Callable<LazyPlan>) () -> {
                    try {
                        if (progress != null) {
                            progress.progress(total, done.get(), nodeId);
                        }
                        LazyPlan plan = executeNode(workflow, nodeId, executor, nodeInputs, config, db,
                                jobConnection, nodeInputIds);
                        if (progress != null) {
                            progress.progress(total, done.incrementAndGet(), null);
                        }
                        return plan;
                    } catch (Throwable failure) {
                        firstFailure.compareAndSet(null, failure);
                        for (Future<LazyPlan> sibling : pending) {
                            if (sibling != self[0] && !sibling.isDone()) {
                                sibling.cancel(true);
                            }
                        }
                        if (failure instanceof Exception exception) {
                            throw exception;
                        }
                        throw new CompletionException(failure);
                    }
                });
                self[0] = future;
                pending.add(future);
                level.put(nodeId, future);
            }
            if (firstFailure.get() != null) {
                cancelAll(level);
            }
            try {
                for (Map.Entry<String, Future<LazyPlan>> entry : level.entrySet()) {
                    results.put(entry.getKey(), entry.getValue().get());
                }
            } catch (InterruptedException interrupted) {
                cancelAll(level);
                closeQuietly(jobConnection);
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException failed) {
                cancelAll(level);
                closeQuietly(jobConnection);
                rethrow(firstFailure.get() != null ? firstFailure.get() : failed.getCause());
                throw new IllegalStateException("unreachable");
            } catch (CancellationException cancelled) {
                cancelAll(level);
                closeQuietly(jobConnection);
                Throwable failure = firstFailure.get();
                if (failure != null) {
                    rethrow(failure);
                }
                Thread.currentThread().interrupt();
                throw new InterruptedException("node execution was cancelled");
            }
            for (String nodeId : current) {
                for (String target : outgoing.get(nodeId)) {
                    remaining.merge(target, -1, Integer::sum);
                }
            }
            current = nextLevel(nodes, remaining, results.keySet());
        }

        Map<String, LazyPlan> ordered = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (results.containsKey(node.id())) {
                ordered.put(node.id(), results.get(node.id()));
            }
        }
        if (ordered.size() != nodes.size()) {
            throw new IllegalStateException("workflow is not executable: some nodes are unreachable");
        }
        return ordered;
    }

    private static void validateInputPorts(WorkflowNode node, List<Connection> incoming) {
        if (incoming.isEmpty()) {
            return;
        }
        int maxPort = -1;
        for (Connection connection : incoming) {
            maxPort = Math.max(maxPort, connection.toPort());
        }
        boolean[] connected = new boolean[maxPort + 1];
        for (Connection connection : incoming) {
            connected[connection.toPort()] = true;
        }
        for (int port = 0; port < connected.length; port++) {
            if (!connected[port]) {
                throw new IllegalStateException(
                        "node '" + node.id() + "' input port " + port + " is not connected");
            }
        }
    }

    private static List<LazyPlan> inputPlans(String nodeId, List<Connection> incoming, Map<String, LazyPlan> results) {
        if (incoming.isEmpty()) {
            return List.of();
        }
        int width = 0;
        for (Connection connection : incoming) {
            width = Math.max(width, connection.toPort() + 1);
        }
        LazyPlan[] plans = new LazyPlan[width];
        for (Connection connection : incoming) {
            LazyPlan plan = results.get(connection.from());
            if (plan == null) {
                throw new IllegalStateException("node '" + nodeId + "' input port " + connection.toPort()
                        + " has no completed source '" + connection.from() + "'");
            }
            plans[connection.toPort()] = plan;
        }
        return Arrays.asList(plans);
    }

    /** Source node id per input port (null for an unconnected port). */
    private static List<String> inputNodeIds(List<Connection> connections) {
        int size = 0;
        for (Connection connection : connections) {
            size = Math.max(size, connection.toPort() + 1);
        }
        String[] ids = new String[size];
        for (Connection connection : connections) {
            ids[connection.toPort()] = connection.from();
        }
        return Arrays.asList(ids);
    }

    private LazyPlan executeNode(Workflow workflow, String nodeId, NodeExecutor executor, List<LazyPlan> inputs,
                                 ObjectNode config, DuckDbSession db, java.sql.Connection jobConnection,
                                 List<String> inputNodeIds) {
        try {
            WorkflowNode node = workflow.node(nodeId);
            if (node == null) {
                throw new IllegalStateException("node was removed during the run: " + nodeId);
            }
            node.running();
            LazyPlan plan = executor.execute(
                    new NodeContext(workflow.id(), nodeId, workflow.session(), config, db, jobConnection, inputNodeIds), inputs);
            workflow.executed(nodeId, plan);
            return plan;
        } catch (Throwable failure) {
            workflow.failed(nodeId, message(failure));
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw failure instanceof CompletionException completion ? completion : new CompletionException(failure);
        }
    }

    private static List<String> nextLevel(List<WorkflowNode> nodes, Map<String, Integer> remaining,
                                          Set<String> completed) {
        List<String> next = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (!completed.contains(node.id()) && remaining.get(node.id()) == 0) {
                next.add(node.id());
            }
        }
        return next;
    }

    /** Closing a DuckDB connection interrupts its running native query/appender. */
    private static void closeQuietly(java.sql.Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // best effort: the job manager closes it again in its cleanup path
        }
    }

    private static void cancelAll(Map<String, Future<LazyPlan>> level) {
        for (Future<LazyPlan> future : level.values()) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static void rethrow(Throwable throwable) throws Exception {
        Throwable cause = unwrap(throwable);
        if (cause instanceof InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof Exception exception) {
            throw exception;
        }
        throw new IllegalStateException(cause);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getName() : message;
    }
}
