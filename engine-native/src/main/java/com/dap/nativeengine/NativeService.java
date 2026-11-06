package com.dap.nativeengine;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.api.CatalogHandlers;
import com.dap.nativeengine.api.DataHandlers;
import com.dap.nativeengine.api.Http;
import com.dap.nativeengine.api.JobHandlers;
import com.dap.nativeengine.api.OpsHandlers;
import com.dap.nativeengine.api.ServiceState;
import com.dap.nativeengine.api.WorkflowHandlers;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.exec.Executors;
import com.dap.nativeengine.nodes.ColumnFilterExecutor;
import com.dap.nativeengine.nodes.ColumnRenamerExecutor;
import com.dap.nativeengine.nodes.CsvReaderExecutor;
import com.dap.nativeengine.nodes.ExcelReaderExecutor;
import com.dap.nativeengine.nodes.GroupByExecutor;
import com.dap.nativeengine.nodes.JoinerExecutor;
import com.dap.nativeengine.nodes.MathFormulaExecutor;
import com.dap.nativeengine.nodes.MissingValueExecutor;
import com.dap.nativeengine.nodes.RowFilterExecutor;
import com.dap.nativeengine.nodes.SorterExecutor;
import com.dap.nativeengine.nodes.StringManipulationExecutor;
import io.javalin.Javalin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTTP entry point of the native engine: the frozen /v1 surface over the in-memory
 * workflow store, the DAG job manager and the per-session DuckDB databases.
 */
public final class NativeService implements AutoCloseable {
    public static final String NAME = "DAP";
    public static final String VERSION = "1.0.0";
    private static final String ERROR_BODY_ATTRIBUTE = "dap.errorBody";

    /** Service configuration; production defaults come from the DAP_* environment. */
    public record Settings(int maxWorkflows, int defaultJobTimeoutSec, String bindHost, int port,
                           String duckdbMemoryLimit, Integer duckdbThreads, String duckdbTempDirectory) {
        public Settings {
            duckdbMemoryLimit = blankToNull(duckdbMemoryLimit);
            duckdbTempDirectory = blankToNull(duckdbTempDirectory);
            if (duckdbThreads != null && duckdbThreads <= 0) {
                throw new IllegalArgumentException(
                        "DAP_DUCKDB_THREADS must be a positive integer, got " + duckdbThreads);
            }
        }

        /** Phase-1/2 tests build the HTTP settings only; DuckDB resources stay at their defaults. */
        public Settings(int maxWorkflows, int defaultJobTimeoutSec, String bindHost, int port) {
            this(maxWorkflows, defaultJobTimeoutSec, bindHost, port, null, null, null);
        }

        public static Settings fromEnv() {
            return fromEnv(System.getenv());
        }

        /** Environment snapshot, injectable for tests; {@link #fromEnv()} is the only caller of System.getenv. */
        static Settings fromEnv(Map<String, String> env) {
            return new Settings(
                    intEnv(env, "DAP_MAX_WORKFLOWS", 100),
                    intEnv(env, "DAP_JOB_TIMEOUT", 600),
                    env.getOrDefault("DAP_BIND", "127.0.0.1"),
                    intEnv(env, "DAP_PORT", 7800),
                    env.get("DAP_DUCKDB_MEMORY_LIMIT"),
                    positiveIntEnv(env, "DAP_DUCKDB_THREADS"),
                    env.get("DAP_DUCKDB_TEMP_DIR"));
        }

        private static int intEnv(Map<String, String> env, String name, int fallback) {
            String raw = env.get(name);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(raw.strip());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static Integer positiveIntEnv(Map<String, String> env, String name) {
            String raw = env.get(name);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Integer.parseInt(raw.strip());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        name + " must be a positive integer, got '" + raw.strip() + "'", failure);
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    private final Settings settings;
    private final ServiceState state;
    private final Javalin app;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private volatile int port;

    /** Production service: environment settings, classpath catalog, five Phase-1 executors. */
    public NativeService() {
        this(Settings.fromEnv(), new NodeRegistry(), defaultExecutors());
    }

    /** Test seam: inject catalog and executors (for example a test-only node). */
    public NativeService(Settings settings, NodeRegistry registry, Executors executors) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.state = new ServiceState(settings,
                Objects.requireNonNull(registry, "registry"),
                Objects.requireNonNull(executors, "executors"));
        this.app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.useVirtualThreads = true;
            config.jetty.defaultHost = this.settings.bindHost();
            config.http.defaultContentType = "application/json; charset=utf-8";
        });
        app.exception(ApiException.class, (exception, context) -> {
            context.attribute(ERROR_BODY_ATTRIBUTE, true);
            Http.error(context, exception.status(), exception.getMessage());
        });
        app.exception(Exception.class, (exception, context) -> {
            context.attribute(ERROR_BODY_ATTRIBUTE, true);
            Http.error(context, 500, exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        });
        // Unmatched routes are answered by Javalin before any handler ran, so the attribute
        // distinguishes them from a deliberate ApiException 404 with its own body.
        app.error(404, context -> {
            if (context.attribute(ERROR_BODY_ATTRIBUTE) != null) {
                return;
            }
            Http.error(context, 404, "no route: " + context.method() + " " + context.path());
        });
        registerRoutes(registry);
    }

    private void registerRoutes(NodeRegistry registry) {
        CatalogHandlers catalog = new CatalogHandlers(registry);
        WorkflowHandlers workflows = new WorkflowHandlers(state);
        JobHandlers jobs = new JobHandlers(state);
        DataHandlers data = new DataHandlers(state);
        OpsHandlers ops = new OpsHandlers(state, this::shutdownAsync);

        app.get("/v1", context -> Http.ok(context, meta()));
        app.get("/v1/health", context -> Http.ok(context, health(Http.session(context))));

        app.get("/v1/nodes", catalog::list);
        app.get("/v1/nodes/{nodeTypeId}", catalog::get);
        app.get("/v1/nodes/{nodeTypeId}/dialog", catalog::dialog);

        app.get("/v1/workflows", workflows::list);
        app.post("/v1/workflows", workflows::create);
        app.post("/v1/workflows/load", workflows::load);
        app.delete("/v1/workflows/{workflowId}", workflows::delete);
        app.get("/v1/workflows/{workflowId}/nodes", workflows::nodes);
        app.post("/v1/workflows/{workflowId}/nodes", workflows::addNode);
        app.patch("/v1/workflows/{workflowId}/nodes/{workflowNodeId}", workflows::configureNode);
        app.delete("/v1/workflows/{workflowId}/nodes/{workflowNodeId}", workflows::removeNode);
        app.post("/v1/workflows/{workflowId}/connect", workflows::connect);
        app.post("/v1/workflows/{workflowId}/disconnect", workflows::disconnect);
        app.get("/v1/workflows/{workflowId}/nodes/{workflowNodeId}/dialog", workflows::nodeDialog);
        app.post("/v1/workflows/{workflowId}/run", jobs::run);
        app.post("/v1/workflows/{workflowId}/jobs", jobs::submit);
        app.get("/v1/jobs", jobs::list);
        app.get("/v1/jobs/{jobId}", jobs::get);
        app.delete("/v1/jobs/{jobId}", jobs::cancel);
        app.get("/v1/workflows/{workflowId}/nodes/{workflowNodeId}/table", data::table);
        app.get("/v1/workflows/{workflowId}/nodes/{workflowNodeId}/profile", data::profile);
        app.post("/v1/workflows/{workflowId}/save", ops::save);
        app.get("/v1/workflows/{workflowId}/debug", ops::debug);
        app.post("/v1/shutdown", ops::shutdown);
    }

    private static Map<String, Object> meta() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", NAME);
        body.put("version", VERSION);
        body.put("api", "v1");
        body.put("endpoints", "/v1/nodes, /v1/workflows, /v1/jobs, /v1/health");
        return body;
    }

    private Map<String, Object> health(String session) {
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMb", usedMb);
        memory.put("maxMb", maxMb);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("name", NAME);
        body.put("version", VERSION);
        body.put("api", Http.API_VERSION);
        body.put("nodes", state.registry().size());
        body.put("coreNodes", state.registry().coreCount());
        body.put("workflows", state.workflows().count());
        body.put("sessions", state.sessionCount());
        body.put("jobs", state.jobs().activeCount(session));
        body.put("memory", memory);
        return body;
    }

    public NativeService start(int requestedPort) {
        app.start(settings.bindHost(), requestedPort);
        // Port 0 lets the OS pick a free port (used by tests); ask Jetty for the real one.
        int bound = app.jettyServer().port();
        this.port = bound > 0 ? bound : app.port();
        return this;
    }

    public int port() {
        return port;
    }

    public boolean shuttingDown() {
        return shuttingDown.get();
    }

    /** Blocks the caller until {@link #close()} or {@link #shutdownAsync()} finished. */
    public void awaitStop() {
        try {
            stopped.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stop after the current response is written: a new daemon thread delays, then closes the
     * job manager, the HTTP server and every session database.
     */
    public void shutdownAsync() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            close();
        }, "dap-shutdown");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            stopped.countDown();
            return;
        }
        try {
            state.jobs().close();
        } finally {
            try {
                app.stop();
            } finally {
                try {
                    state.closeSessions();
                } finally {
                    stopped.countDown();
                }
            }
        }
    }

    /** All eleven node executors. */
    public static Executors defaultExecutors() {
        return new Executors()
                .register(new CsvReaderExecutor())
                .register(new RowFilterExecutor())
                .register(new ColumnFilterExecutor())
                .register(new ColumnRenamerExecutor())
                .register(new SorterExecutor())
                .register(new MissingValueExecutor())
                .register(new ExcelReaderExecutor())
                .register(new GroupByExecutor())
                .register(new JoinerExecutor())
                .register(new StringManipulationExecutor())
                .register(new MathFormulaExecutor());
    }

    public static void main(String[] args) {
        Settings defaults;
        try {
            defaults = Settings.fromEnv();
        } catch (IllegalArgumentException invalidSettings) {
            // Bad DAP_* configuration must exit like the DAP_BIND check, not as a stack trace.
            System.err.println("dap-native: " + invalidSettings.getMessage());
            System.exit(2);
            return;
        }
        int port = defaults.port();
        String configuredPort = System.getProperty("dap.port");
        if (configuredPort != null && !configuredPort.isBlank()) {
            port = Integer.parseInt(configuredPort.strip());
        }
        String bind = defaults.bindHost();
        if (!"127.0.0.1".equals(bind) && !"localhost".equals(bind) && !"0.0.0.0".equals(bind)) {
            System.err.println("dap-native: unsupported DAP_BIND " + bind);
            System.exit(2);
        }
        Settings settings = new Settings(defaults.maxWorkflows(), defaults.defaultJobTimeoutSec(), bind, port,
                defaults.duckdbMemoryLimit(), defaults.duckdbThreads(), defaults.duckdbTempDirectory());
        NativeService service = new NativeService(settings, new NodeRegistry(), defaultExecutors());
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "dap-shutdown-hook"));
        service.start(port);
        System.out.println("dap-native listening on http://" + bind + ":" + service.port() + "/v1");
        service.awaitStop();
    }
}
