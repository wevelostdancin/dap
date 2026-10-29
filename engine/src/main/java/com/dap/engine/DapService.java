package com.dap.engine;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.RowIterator;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.ConfigurableNodeFactory;
import org.knime.core.node.NodeFactory;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.context.ModifiableNodeCreationConfiguration;
import org.knime.core.node.port.PortObject;
import org.knime.core.node.workflow.NodeContainer;
import org.knime.core.node.workflow.NodeID;
import org.knime.core.node.workflow.NodeMessage;
import org.knime.core.node.workflow.NodeOutPort;
import org.knime.core.node.workflow.WorkflowCreationHelper;
import org.knime.core.node.workflow.WorkflowManager;
import org.osgi.framework.Bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Резидентный HTTP-сервис: каталог узлов, сборка workflow, исполнение, чтение таблиц.
 * Без UI и внешних зависимостей, только бандлы KNIME.
 */
public class DapService implements IApplication {

    private static final int DEFAULT_PORT = 7800;

    private static final String VERSION = "1.0.0";
    private static final String API_VERSION = "1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Wf> workflows = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> dialogCache = new ConcurrentHashMap<>();
    private final AtomicInteger wfSeq = new AtomicInteger();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private NodeCatalog catalog;
    private Tiers tiers = Tiers.empty();
    private JobManager jobs;
    private HttpServer server;
    private int port = DEFAULT_PORT;
    private String bind = "127.0.0.1";
    private int jobTimeoutSec = 600;
    private int maxWorkflowsPerSession = 100;
    private File schemasDir;

    private static final class Wf {
        final String id;
        final String name;
        final String session;
        final WorkflowManager wfm;
        final Map<String, String> factoryOf = new ConcurrentHashMap<>();

        Wf(final String id, final String name, final String session, final WorkflowManager wfm) {
            this.id = id;
            this.name = name;
            this.session = session;
            this.wfm = wfm;
        }
    }

    @Override
    public Object start(final IApplicationContext context) throws Exception {
        System.setProperty("java.awt.headless", "true");
        final String[] args = (String[]) context.getArguments().get("application.args");
        String catalogPath = "catalog.json";
        String curatedPath = null;
        boolean rebuildCatalog = false;
        for (final String a : args) {
            if (a.startsWith("-port=")) {
                port = Integer.parseInt(a.substring("-port=".length()));
            } else if (a.startsWith("-bind=")) {
                bind = a.substring("-bind=".length());
            } else if (a.startsWith("-catalog=")) {
                catalogPath = a.substring("-catalog=".length());
            } else if (a.startsWith("-schemas=")) {
                schemasDir = new File(a.substring("-schemas=".length()));
            } else if (a.startsWith("-curated=")) {
                curatedPath = a.substring("-curated=".length());
            } else if (a.startsWith("-jobTimeoutSec=")) {
                jobTimeoutSec = Integer.parseInt(a.substring("-jobTimeoutSec=".length()));
            } else if (a.startsWith("-maxWorkflowsPerSession=")) {
                maxWorkflowsPerSession = Integer.parseInt(a.substring("-maxWorkflowsPerSession=".length()));
            } else if (a.equals("-rebuildCatalog")) {
                rebuildCatalog = true;
            }
        }

        try {
            org.knime.core.internal.CorePlugin.getInstance();
            final Bundle wbCore = Platform.getBundle("org.knime.workbench.core");
            if (wbCore != null) {
                try {
                    wbCore.start(Bundle.START_TRANSIENT);
                } catch (final Exception e) {
                    System.out.println("[service] workbench.core not started: " + e.getMessage());
                }
            }

            final File catalogFile = new File(catalogPath);
            catalog = NodeCatalog.loadOrBuild(catalogFile);
            if (rebuildCatalog) {
                catalog.rebuild();
            }
            tiers = Tiers.load(curatedPath == null ? null : new File(curatedPath));
            final int core = tiers.apply(catalog);
            if (rebuildCatalog) {
                catalog.save(catalogFile);
            }
            jobs = new JobManager(mapper, 8);

            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
            server.createContext("/", this::handle);
            server.start();
            System.out.println("[dap] listening http://" + bind + ":" + port
                    + " nodes=" + catalog.size() + " core=" + core
                    + " schemas=" + (schemasDir == null ? "runtime" : schemasDir.getPath()));
            System.out.flush();

            stopped.await();
            return IApplication.EXIT_OK;
        } catch (final Throwable t) {
            System.err.println("[service] FAILED: " + t);
            t.printStackTrace();
            return 1;
        } finally {
            if (server != null) {
                server.stop(0);
            }
            if (jobs != null) {
                jobs.shutdown();
            }
        }
    }

    // ------------------------------------------------------------------ routing

    private void handle(final HttpExchange ex) throws java.io.IOException {
        try {
            final String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            final Map<String, String> query = query(ex.getRequestURI().getRawQuery());
            if (path.equals("/v1")) {
                final ObjectNode r = mapper.createObjectNode();
                r.put("name", "DAP");
                r.put("version", VERSION);
                r.put("api", "v1");
                r.put("endpoints", "/v1/nodes, /v1/workflows, /v1/jobs, /v1/health");
                send(ex, 200, r);
                return;
            }
            if (path.startsWith("/v1/")) {
                path = path.substring(3);
            }
            final String[] seg = path.split("/");
            final String session = session(ex, query);
            ex.getResponseHeaders().set("X-DAP-Api-Version", API_VERSION);
            ex.getResponseHeaders().set("X-DAP-Session", session);

            if (path.equals("/health") && method.equals("GET")) {
                final ObjectNode r = mapper.createObjectNode();
                r.put("ok", true);
                r.put("name", "DAP");
                r.put("version", VERSION);
                r.put("api", API_VERSION);
                r.put("nodes", catalog.size());
                r.put("coreNodes", tiers.size());
                r.put("workflows", workflows.size());
                r.put("sessions", workflows.values().stream().map(w -> w.session).distinct().count());
                r.put("jobs", jobs == null ? 0 : jobs.list(session).size());
                final Runtime rt = Runtime.getRuntime();
                r.put("memory", mapper.createObjectNode()
                        .put("usedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))
                        .put("maxMb", rt.maxMemory() / (1024 * 1024)));
                send(ex, 200, r);
                return;
            }
            if (path.equals("/shutdown") && method.equals("POST")) {
                send(ex, 200, mapper.createObjectNode().put("ok", true));
                stopped.countDown();
                return;
            }
            if (path.equals("/nodes") && method.equals("GET")) {
                final int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 50;
                final List<ObjectNode> found = catalog.search(query.get("q"), query.get("category"), limit);
                final ObjectNode r = mapper.createObjectNode();
                r.put("count", found.size());
                final ArrayNode arr = r.putArray("nodes");
                for (final ObjectNode n : found) {
                    final ObjectNode brief = mapper.createObjectNode();
                    brief.put("id", n.get("id").asText());
                    brief.put("name", n.get("name").asText());
                    brief.put("category", n.get("category").asText());
                    brief.put("type", n.get("type").asText());
                    brief.put("deprecated", n.get("deprecated").asBoolean());
                    brief.put("tier", n.path("tier").asText(Tiers.EXPERIMENTAL));
                    brief.set("inPorts", n.get("inPorts"));
                    brief.set("outPorts", n.get("outPorts"));
                    arr.add(brief);
                }
                send(ex, 200, r);
                return;
            }
            if (seg.length == 4 && seg[1].equals("nodes") && seg[3].equals("dialog")
                    && method.equals("GET")) {
                final String nodeId = java.net.URLDecoder.decode(seg[2], StandardCharsets.UTF_8);
                send(ex, 200, dialogSchema(nodeId));
                return;
            }
            if (seg.length >= 3 && seg[1].equals("nodes")) {
                final String id = java.net.URLDecoder.decode(seg[2], StandardCharsets.UTF_8);
                final ObjectNode node = catalog.byId(id);
                if (node == null) {
                    send(ex, 404, mapper.createObjectNode().put("error", "unknown node " + id));
                } else {
                    send(ex, 200, node);
                }
                return;
            }

            // /jobs...
            if (seg.length == 2 && seg[1].equals("jobs") && method.equals("GET")) {
                final ObjectNode r = mapper.createObjectNode();
                r.put("count", jobs.list(session).size());
                r.set("jobs", jobs.listJson(session));
                send(ex, 200, r);
                return;
            }
            if (seg.length == 3 && seg[1].equals("jobs")) {
                final String id = seg[2];
                final JobManager.Job job = jobs.get(id, session);
                if (job == null) {
                    send(ex, 404, mapper.createObjectNode().put("error", "unknown job " + id));
                    return;
                }
                if (method.equals("GET")) {
                    send(ex, 200, jobs.json(job, true));
                    return;
                }
                if (method.equals("DELETE")) {
                    final boolean cancelled = jobs.cancel(id, session);
                    final ObjectNode r = mapper.createObjectNode();
                    r.put("ok", cancelled);
                    r.put("id", id);
                    r.set("job", jobs.json(job, false));
                    send(ex, cancelled ? 200 : 409, r);
                    return;
                }
            }

            // /workflows...
            if (seg.length >= 2 && seg[1].equals("workflows")) {
                routeWorkflows(ex, method, seg, query, session);
                return;
            }

            send(ex, 404, mapper.createObjectNode().put("error", "no route: " + method + " " + path));
        } catch (final IllegalArgumentException e) {
            send(ex, 400, mapper.createObjectNode().put("error", String.valueOf(e.getMessage())));
        } catch (final Throwable t) {
            final ObjectNode err = mapper.createObjectNode();
            err.put("error", String.valueOf(t));
            send(ex, 500, err);
        } finally {
            ex.close();
        }
    }

    private void routeWorkflows(final HttpExchange ex, final String method, final String[] seg,
            final Map<String, String> query, final String session) throws Exception {
        // POST /workflows
        if (seg.length == 2) {
            if (method.equals("GET")) {
                final ObjectNode r = mapper.createObjectNode();
                final ArrayNode arr = r.putArray("workflows");
                for (final Wf wf : workflows.values()) {
                    if (!wf.session.equals(session)) {
                        continue;
                    }
                    final ObjectNode o = mapper.createObjectNode();
                    o.put("id", wf.id);
                    o.put("name", wf.name);
                    o.put("session", wf.session);
                    o.put("nodes", wf.wfm.getNodeContainers().size());
                    arr.add(o);
                }
                send(ex, 200, r);
                return;
            }
            if (method.equals("POST")) {
                final long owned = workflows.values().stream()
                        .filter(w -> w.session.equals(session)).count();
                if (owned >= maxWorkflowsPerSession) {
                    throw new IllegalStateException("workflow limit for session: "
                            + maxWorkflowsPerSession);
                }
                final JsonNode body = readBody(ex);
                final String name = body.path("name").asText("workflow-" + (wfSeq.get() + 1));
                final WorkflowManager wfm = WorkflowManager.ROOT
                        .createAndAddProject(name, new WorkflowCreationHelper());
                final String id = "wf-" + wfSeq.incrementAndGet();
                workflows.put(id, new Wf(id, name, session, wfm));
                final ObjectNode r = mapper.createObjectNode();
                r.put("id", id);
                r.put("name", name);
                r.put("session", session);
                send(ex, 200, r);
                return;
            }
        }

        final Wf wf = workflows.get(seg[2]);
        if (wf == null || !wf.session.equals(session)) {
            send(ex, 404, mapper.createObjectNode().put("error", "unknown workflow " + seg[2]));
            return;
        }

        // DELETE /workflows/{id}
        if (seg.length == 3 && method.equals("DELETE")) {
            workflows.remove(wf.id);
            wf.wfm.shutdown();
            send(ex, 200, mapper.createObjectNode().put("ok", true));
            return;
        }

        // GET /workflows/{id}/debug
        if (seg.length == 4 && seg[3].equals("debug") && method.equals("GET")) {
            final ObjectNode r = mapper.createObjectNode();
            final ArrayNode nodes = r.putArray("nodes");
            for (final NodeContainer nc : wf.wfm.getNodeContainers()) {
                final ObjectNode o = nodes.addObject();
                o.put("node", nc.getID().toString());
                o.put("in", nc.getNrInPorts());
                o.put("out", nc.getNrOutPorts());
                final ArrayNode inClasses = o.putArray("inClasses");
                for (int i = 0; i < nc.getNrInPorts(); i++) {
                    inClasses.add(String.valueOf(nc.getInPort(i).getPortType().getName()));
                }
                final ArrayNode outClasses = o.putArray("outClasses");
                for (int i = 0; i < nc.getNrOutPorts(); i++) {
                    final Object po = nc.getOutPort(i).getPortObject();
                    outClasses.add(po == null ? "null" : po.getClass().getSimpleName());
                }
            }
            final ArrayNode conns = r.putArray("connections");
            for (final Object c : wf.wfm.getConnectionContainers()) {
                conns.add(String.valueOf(c));
            }
            send(ex, 200, r);
            return;
        }

        // GET /workflows/{id}/nodes
        if (seg.length == 4 && seg[3].equals("nodes") && method.equals("GET")) {
            final ObjectNode r = mapper.createObjectNode();
            final ArrayNode arr = r.putArray("nodes");
            for (final NodeContainer nc : wf.wfm.getNodeContainers()) {
                final ObjectNode o = mapper.createObjectNode();
                o.put("node", nc.getID().toString());
                o.put("name", nc.getName());
                o.put("state", String.valueOf(nc.getState()));
                final NodeMessage msg = nc.getNodeMessage();
                if (msg != null) {
                    o.put("message", String.valueOf(msg.getMessage()));
                }
                arr.add(o);
            }
            send(ex, 200, r);
            return;
        }

        // POST /workflows/{id}/nodes
        if (seg.length == 4 && seg[3].equals("nodes") && method.equals("POST")) {
            final JsonNode body = readBody(ex);
            final String factoryId = body.path("factory").asText(null);
            if (factoryId == null) {
                throw new IllegalArgumentException("'factory' is required");
            }
            final ObjectNode nodeMeta = catalog.byId(factoryId);
            if (nodeMeta == null) {
                throw new IllegalArgumentException("unknown node: " + factoryId);
            }
            final NodeFactory<?> factory = catalog.newFactory(factoryId);
            final NodeID nid;
            if (factory instanceof ConfigurableNodeFactory) {
                final ModifiableNodeCreationConfiguration creation =
                        ((ConfigurableNodeFactory<?>) factory).createNodeCreationConfig();
                nid = wf.wfm.addNodeAndApplyContext(factory, creation, -1);
            } else {
                nid = wf.wfm.createAndAddNode(factory);
            }
            wf.factoryOf.put(nid.toString(), factoryId);
            if (body.has("settings") && !body.get("settings").isNull()) {
                applySettings(wf, nid, nodeMeta, body.get("settings"));
            }
            final ObjectNode r = mapper.createObjectNode();
            r.put("node", nid.toString());
            r.put("name", factory.getNodeName());
            send(ex, 200, r);
            return;
        }

        // PATCH /workflows/{id}/nodes/{node}
        if (seg.length == 5 && seg[3].equals("nodes") && method.equals("PATCH")) {
            final NodeContainer nc = find(wf, seg[4]);
            final String factoryId = wf.factoryOf.get(nc.getID().toString());
            final ObjectNode meta = factoryId == null ? null : catalog.byId(factoryId);
            if (meta == null) {
                throw new IllegalArgumentException("node was not created through this service: "
                        + nc.getID());
            }
            final JsonNode body = readBody(ex);
            applySettings(wf, nc.getID(), meta, body.path("settings"));
            send(ex, 200, mapper.createObjectNode().put("ok", true).put("node", nc.getID().toString()));
            return;
        }

        // POST /workflows/{id}/connect
        if (seg.length == 4 && seg[3].equals("connect") && method.equals("POST")) {
            final JsonNode body = readBody(ex);
            final NodeContainer src = find(wf, body.path("from").asText());
            final NodeContainer dst = find(wf, body.path("to").asText());
            final int fromPort = dataOutPort(src, body.path("fromPort").asInt(0));
            final int toPort = dataInPort(dst, body.path("toPort").asInt(0));
            wf.wfm.addConnection(src.getID(), fromPort, dst.getID(), toPort);
            send(ex, 200, mapper.createObjectNode().put("ok", true));
            return;
        }

        // POST /workflows/{id}/run?async=true  |  POST /workflows/{id}/jobs
        if ((seg.length == 4 && seg[3].equals("run") && method.equals("POST"))
                || (seg.length == 4 && seg[3].equals("jobs") && method.equals("POST"))) {
            final JsonNode body = readBody(ex);
            final boolean async = seg[3].equals("jobs")
                    || body.path("async").asBoolean(false)
                    || "true".equals(query.getOrDefault("async", "false"));
            final long timeout = body.path("timeoutSec").asLong(jobTimeoutSec);
            final Callable<ObjectNode> task = () -> executeWorkflow(wf);
            final JobManager.Job job = jobs.submit(session, wf.id, timeout, task,
                    () -> wf.wfm.cancelExecution());
            if (async) {
                final ObjectNode r = jobs.json(job, false);
                r.put("workflow", wf.id);
                r.put("statusUrl", "/v1/jobs/" + job.id);
                send(ex, 202, r);
                return;
            }
            final ObjectNode awaited = jobs.await(job, timeout + 30);
            final JsonNode result = awaited.get("result");
            if (result instanceof ObjectNode) {
                send(ex, 200, (ObjectNode) result);
            } else {
                final int code = "timeout".equals(awaited.path("state").asText()) ? 408 : 500;
                send(ex, code, awaited);
            }
            return;
        }

        // POST /workflows/{id}/save
        if (seg.length == 4 && seg[3].equals("save") && method.equals("POST")) {
            final JsonNode body = readBody(ex);
            final String dir = body.path("dir").asText(null);
            if (dir == null) {
                throw new IllegalArgumentException("'dir' is required");
            }
            wf.wfm.save(new File(dir), new ExecutionMonitor(), true);
            send(ex, 200, mapper.createObjectNode().put("ok", true).put("dir", dir));
            return;
        }

        // DELETE /workflows/{id}/nodes/{node}
        if (seg.length == 5 && seg[3].equals("nodes") && method.equals("DELETE")) {
            final NodeContainer nc = find(wf, seg[4]);
            if (!wf.wfm.canRemoveNode(nc.getID())) {
                send(ex, 400, mapper.createObjectNode().put("error",
                        "node cannot be removed (execution in progress or connections remain)"));
                return;
            }
            wf.wfm.removeNode(nc.getID());
            wf.factoryOf.remove(nc.getID().toString());
            send(ex, 200, mapper.createObjectNode().put("ok", true).put("node", nc.getID().toString()));
            return;
        }

        // POST /workflows/{id}/disconnect — разорвать соединение
        if (seg.length == 4 && seg[3].equals("disconnect") && method.equals("POST")) {
            final JsonNode body = readBody(ex);
            final NodeContainer src = find(wf, body.path("from").asText());
            final NodeContainer dst = find(wf, body.path("to").asText());
            final int fromPort = dataOutPort(src, body.path("fromPort").asInt(0));
            final int toPort = dataInPort(dst, body.path("toPort").asInt(0));
            for (final org.knime.core.node.workflow.ConnectionContainer cc : wf.wfm.getConnectionContainers()) {
                if (cc.getSource().equals(src.getID()) && cc.getSourcePort() == fromPort
                        && cc.getDest().equals(dst.getID()) && cc.getDestPort() == toPort) {
                    if (!wf.wfm.canRemoveConnection(cc)) {
                        send(ex, 400, mapper.createObjectNode().put("error", "connection cannot be removed"));
                        return;
                    }
                    wf.wfm.removeConnection(cc);
                    send(ex, 200, mapper.createObjectNode().put("ok", true));
                    return;
                }
            }
            send(ex, 404, mapper.createObjectNode().put("error", "connection not found"));
            return;
        }

        // GET /workflows/{id}/nodes/{node}/dialog — схема с вариантами по реальным входам
        if (seg.length == 6 && seg[3].equals("nodes") && seg[5].equals("dialog")
                && method.equals("GET")) {
            final NodeContainer nc = find(wf, seg[4]);
            final String factoryId = wf.factoryOf.get(nc.getID().toString());
            final ObjectNode meta = factoryId == null ? null : catalog.byId(factoryId);
            if (meta == null) {
                throw new IllegalArgumentException("node was not created through this service: "
                        + nc.getID());
            }
            final org.knime.core.node.NodeFactory<?> factory = catalog.newFactory(factoryId);
            final ObjectNode r = mapper.createObjectNode();
            r.put("id", factoryId);
            r.put("node", nc.getID().toString());
            r.put("tier", meta.path("tier").asText(Tiers.EXPERIMENTAL));
            r.setAll(DialogSchema.describeNode(factory,
                    SwingDialogSchema.keysOf(meta.path("settingsXml").asText("")),
                    DynamicChoices.forNode(wf.wfm, nc.getID())));
            send(ex, 200, r);
            return;
        }

        // GET /workflows/{id}/nodes/{node}/profile?port=0
        if (seg.length == 6 && seg[3].equals("nodes") && seg[5].equals("profile") && method.equals("GET")) {
            final NodeContainer nc = find(wf, seg[4]);
            final BufferedDataTable table = firstTable(nc);
            if (table == null) {
                send(ex, 400, mapper.createObjectNode().put("error", "node has no table output"));
                return;
            }
            send(ex, 200, profile(table));
            return;
        }

        // GET /workflows/{id}/nodes/{node}/table?port=0&limit=50
        if (seg.length == 6 && seg[3].equals("nodes") && seg[5].equals("table") && method.equals("GET")) {
            final NodeContainer nc = find(wf, seg[4]);
            final int portIndex = query.containsKey("port") ? Integer.parseInt(query.get("port")) : 0;
            final int limit = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : 50;
            if (portIndex >= nc.getNrOutPorts()) {
                throw new IllegalArgumentException("node has " + nc.getNrOutPorts() + " out ports");
            }
            PortObject po = null;
            int usedPort = portIndex;
            if (portIndex < nc.getNrOutPorts()) {
                po = nc.getOutPort(portIndex).getPortObject();
            }
            if (!(po instanceof BufferedDataTable)) {
                // у узла может быть скрытый порт переменных на индексе 0 — ищем таблицу
                for (int i = 0; i < nc.getNrOutPorts(); i++) {
                    final PortObject candidate = nc.getOutPort(i).getPortObject();
                    if (candidate instanceof BufferedDataTable) {
                        po = candidate;
                        usedPort = i;
                        break;
                    }
                }
            }
            if (!(po instanceof BufferedDataTable)) {
                final ObjectNode err = mapper.createObjectNode();
                err.put("error", "node has no table output");
                final ArrayNode ports = err.putArray("ports");
                for (int i = 0; i < nc.getNrOutPorts(); i++) {
                    final ObjectNode p = ports.addObject();
                    p.put("index", i);
                    final PortObject o = nc.getOutPort(i).getPortObject();
                    p.put("class", o == null ? "null" : o.getClass().getName());
                }
                send(ex, 400, err);
                return;
            }
            final ObjectNode table = tableToJson((BufferedDataTable) po, limit);
            if (usedPort != portIndex) {
                table.put("note", "table находится на порту " + usedPort);
                table.put("port", usedPort);
            }
            send(ex, 200, tableToJson((BufferedDataTable) po, limit));
            return;
        }

        send(ex, 404, mapper.createObjectNode().put("error", "no route for workflow path"));
    }

    /** Тело ответа POST /run: состояние узлов после исполнения. */
    private ObjectNode executeWorkflow(final Wf wf) throws Exception {
        final long t0 = System.currentTimeMillis();
        final boolean ok = wf.wfm.executeAllAndWaitUntilDoneInterruptibly();
        final long ms = System.currentTimeMillis() - t0;
        final ObjectNode r = mapper.createObjectNode();
        r.put("ok", ok);
        r.put("durationMs", ms);
        r.put("workflow", wf.id);
        final ArrayNode nodes = r.putArray("nodes");
        for (final NodeContainer nc : wf.wfm.getNodeContainers()) {
            final ObjectNode o = mapper.createObjectNode();
            o.put("node", nc.getID().toString());
            o.put("name", nc.getName());
            o.put("state", String.valueOf(nc.getState()));
            final NodeMessage msg = nc.getNodeMessage();
            if (msg != null && msg.getMessage() != null && !msg.getMessage().isEmpty()) {
                o.put("message", msg.getMessage());
            }
            nodes.add(o);
        }
        return r;
    }

    /** Схема диалога узла: подписи, типы, умолчания и списки допустимых значений. */
    private ObjectNode dialogSchema(final String nodeId) throws Exception {
        final ObjectNode cached = dialogCache.get(nodeId);
        if (cached != null) {
            return cached;
        }
        final ObjectNode meta = catalog.byId(nodeId);
        if (meta == null) {
            throw new IllegalArgumentException("unknown node: " + nodeId);
        }
        final ObjectNode precomputed = precomputedSchema(nodeId);
        if (precomputed != null) {
            final ObjectNode copy = precomputed.deepCopy();
            copy.put("precomputed", true);
            copy.put("tier", meta.path("tier").asText(Tiers.EXPERIMENTAL));
            dialogCache.put(nodeId, copy);
            return copy;
        }
        final ObjectNode result = mapper.createObjectNode();
        result.put("id", nodeId);
        result.put("tier", meta.path("tier").asText(Tiers.EXPERIMENTAL));
        try {
            final org.knime.core.node.NodeFactory<?> factory = catalog.newFactory(nodeId);
            result.setAll(DialogSchema.describeNode(factory,
                    SwingDialogSchema.keysOf(meta.path("settingsXml").asText(""))));
        } catch (final Throwable t) {
            result.put("available", false);
            result.put("reason", String.valueOf(t));
        }
        dialogCache.put(nodeId, result);
        return result;
    }

    /** Схема, предрассчитанная scripts/build-catalog.sh, если она есть. */
    private ObjectNode precomputedSchema(final String nodeId) {
        if (schemasDir == null || nodeId.contains("/") || nodeId.contains("\\")
                || nodeId.contains("..")) {
            return null;
        }
        final File file = new File(schemasDir, nodeId + ".json");
        if (!file.isFile()) {
            return null;
        }
        try {
            return (ObjectNode) mapper.readTree(file);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Сессия агента: X-DAP-Session или ?session=, по умолчанию default. */
    private static String session(final HttpExchange ex, final Map<String, String> query) {
        String session = ex.getRequestHeaders().getFirst("X-DAP-Session");
        if (session == null || session.isBlank()) {
            session = query.getOrDefault("session", "default");
        }
        session = session.trim();
        if (!session.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("invalid session id");
        }
        return session;
    }

    private void applySettings(final Wf wf, final NodeID nid, final ObjectNode meta,
            final JsonNode settings) throws Exception {
        final NodeSettingsRO merged = SettingsMerge.applyToNode(wf.wfm, nid, meta, settings);
        wf.wfm.loadNodeSettings(nid, merged);
        try {
            wf.wfm.resetAndConfigureNode(nid);
        } catch (final Exception e) {
            throw new IllegalArgumentException("settings rejected: " + e.getMessage());
        }
    }

    private BufferedDataTable firstTable(final NodeContainer nc) {
        for (int i = 0; i < nc.getNrOutPorts(); i++) {
            final PortObject po = nc.getOutPort(i).getPortObject();
            if (po instanceof BufferedDataTable) {
                return (BufferedDataTable) po;
            }
        }
        return null;
    }

    /** Профиль колонок: заполненность, уникальность, диапазон — основа доказательности. */
    private ObjectNode profile(final BufferedDataTable table) {
        final DataTableSpec spec = table.getDataTableSpec();
        final int cols = spec.getNumColumns();
        final long[] missing = new long[cols];
        final java.util.Set<String>[] distinct = new java.util.HashSet[cols];
        final double[] min = new double[cols];
        final double[] max = new double[cols];
        final double[] sum = new double[cols];
        final long[] numeric = new long[cols];
        final boolean[] isNumber = new boolean[cols];
        for (int c = 0; c < cols; c++) {
            distinct[c] = new java.util.HashSet<>();
            min[c] = Double.POSITIVE_INFINITY;
            max[c] = Double.NEGATIVE_INFINITY;
            isNumber[c] = spec.getColumnSpec(c).getType().getName().startsWith("Number");
        }
        final RowIterator it = table.iterator();
        long rows = 0;
        while (it.hasNext()) {
            final DataRow row = it.next();
            rows++;
            for (int c = 0; c < cols; c++) {
                final DataCell cell = row.getCell(c);
                if (cell.isMissing()) {
                    missing[c]++;
                    continue;
                }
                if (distinct[c].size() < 100_000) {
                    distinct[c].add(cell.toString());
                }
                if (isNumber[c] && cell instanceof org.knime.core.data.DoubleValue) {
                    final double v = ((org.knime.core.data.DoubleValue) cell).getDoubleValue();
                    min[c] = Math.min(min[c], v);
                    max[c] = Math.max(max[c], v);
                    sum[c] += v;
                    numeric[c]++;
                }
            }
        }
        final ObjectNode r = mapper.createObjectNode();
        r.put("rows", rows);
        final ArrayNode colsJson = r.putArray("columns");
        for (int c = 0; c < cols; c++) {
            final ObjectNode o = colsJson.addObject();
            o.put("name", spec.getColumnSpec(c).getName());
            o.put("type", spec.getColumnSpec(c).getType().getName());
            o.put("missing", missing[c]);
            o.put("distinct", distinct[c].size());
            if (numeric[c] > 0) {
                o.put("min", min[c]);
                o.put("max", max[c]);
                o.put("mean", sum[c] / numeric[c]);
            }
        }
        return r;
    }

    private ObjectNode tableToJson(final BufferedDataTable table, final int limit) {
        final ObjectNode r = mapper.createObjectNode();
        final DataTableSpec spec = table.getDataTableSpec();
        r.put("rows", table.size());
        final ArrayNode cols = r.putArray("columns");
        for (int i = 0; i < spec.getNumColumns(); i++) {
            final ObjectNode c = cols.addObject();
            c.put("index", i);
            c.put("name", spec.getColumnSpec(i).getName());
            c.put("type", spec.getColumnSpec(i).getType().getName());
        }
        final ArrayNode rows = r.putArray("data");
        final RowIterator it = table.iterator();
        int n = 0;
        while (it.hasNext() && n < limit) {
            final DataRow row = it.next();
            final ArrayNode arr = rows.addArray();
            for (final DataCell cell : row) {
                arr.add(cell.isMissing() ? null : cell.toString());
            }
            n++;
        }
        r.put("preview", n);
        return r;
    }

    /** Порт 0 у узла — скрытый порт переменных; агент оперирует индексами портов данных. */
    private static int dataOutPort(final NodeContainer nc, final int dataPort) {
        final boolean hidden = nc.getNrOutPorts() > 0
                && "Flow Variable".equals(nc.getOutPort(0).getPortType().getName());
        return hidden ? dataPort + 1 : dataPort;
    }

    private static int dataInPort(final NodeContainer nc, final int dataPort) {
        final boolean hidden = nc.getNrInPorts() > 0
                && "Flow Variable".equals(nc.getInPort(0).getPortType().getName());
        return hidden ? dataPort + 1 : dataPort;
    }

    private NodeContainer find(final Wf wf, final String nodeId) {
        for (final NodeContainer nc : wf.wfm.getNodeContainers()) {
            if (nc.getID().toString().equals(nodeId)) {
                return nc;
            }
        }
        throw new IllegalArgumentException("unknown node: " + nodeId);
    }

    private JsonNode readBody(final HttpExchange ex) throws java.io.IOException {
        final byte[] raw = ex.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(raw);
    }

    private void send(final HttpExchange ex, final int code, final JsonNode body) throws java.io.IOException {
        final byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> query(final String raw) {
        final Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (final String pair : raw.split("&")) {
            final int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    @Override
    public void stop() {
        stopped.countDown();
        if (server != null) {
            server.stop(0);
        }
    }
}
