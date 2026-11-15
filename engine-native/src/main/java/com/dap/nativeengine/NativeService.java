package com.dap.nativeengine;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.api.CatalogHandlers;
import com.dap.nativeengine.api.DataHandlers;
import com.dap.nativeengine.api.DatasetHandlers;
import com.dap.nativeengine.api.Http;
import com.dap.nativeengine.api.JobHandlers;
import com.dap.nativeengine.api.OpsHandlers;
import com.dap.nativeengine.api.ServiceState;
import com.dap.nativeengine.api.WorkflowHandlers;
import io.javalin.http.Context;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.exec.Executors;
import com.dap.nativeengine.nodes.BinnerExecutor;
import com.dap.nativeengine.nodes.ChartExecutor;
import com.dap.nativeengine.nodes.ColumnResorterExecutor;
import com.dap.nativeengine.nodes.ConcatenateExecutor;
import com.dap.nativeengine.nodes.DateTimeExecutor;
import com.dap.nativeengine.nodes.DuplicateFilterExecutor;
import com.dap.nativeengine.nodes.EncoderExecutor;
import com.dap.nativeengine.nodes.NormalizerExecutor;
import com.dap.nativeengine.nodes.OutlierFilterExecutor;
import com.dap.nativeengine.nodes.PivotExecutor;
import com.dap.nativeengine.nodes.RowAggregatorExecutor;
import com.dap.nativeengine.nodes.RuleEngineExecutor;
import com.dap.nativeengine.nodes.SampleRowsExecutor;
import com.dap.nativeengine.nodes.TopNPerGroupExecutor;
import com.dap.nativeengine.nodes.TypeCastExecutor;
import com.dap.nativeengine.nodes.UnpivotExecutor;
import com.dap.nativeengine.nodes.ValueReplaceExecutor;
import com.dap.nativeengine.nodes.WindowExecutor;
import com.dap.nativeengine.nodes.CorrelationExecutor;
import com.dap.nativeengine.nodes.DataQualityExecutor;
import com.dap.nativeengine.nodes.DateRangeExecutor;
import com.dap.nativeengine.nodes.DescribeExecutor;
import com.dap.nativeengine.nodes.DiffExecutor;
import com.dap.nativeengine.nodes.ExcelWriterExecutor;
import com.dap.nativeengine.nodes.HistogramExecutor;
import com.dap.nativeengine.nodes.HypothesisTestExecutor;
import com.dap.nativeengine.nodes.OutlierDetectExecutor;
import com.dap.nativeengine.nodes.QuantileExecutor;
import com.dap.nativeengine.nodes.ResampleExecutor;
import com.dap.nativeengine.nodes.RollingExecutor;
import com.dap.nativeengine.nodes.ValueCountsExecutor;
import com.dap.nativeengine.nodes.ColumnFilterExecutor;
import com.dap.nativeengine.nodes.ColumnRenamerExecutor;
import com.dap.nativeengine.nodes.CsvReaderExecutor;
import com.dap.nativeengine.nodes.CsvWriterExecutor;
import com.dap.nativeengine.nodes.ExcelReaderExecutor;
import com.dap.nativeengine.nodes.GroupByExecutor;
import com.dap.nativeengine.nodes.JsonReaderExecutor;
import com.dap.nativeengine.nodes.JsonWriterExecutor;
import com.dap.nativeengine.nodes.LinearRegressionExecutor;
import com.dap.nativeengine.nodes.JoinerExecutor;
import com.dap.nativeengine.nodes.MarkdownReportExecutor;
import com.dap.nativeengine.nodes.MathFormulaExecutor;
import com.dap.nativeengine.nodes.MissingValueExecutor;
import com.dap.nativeengine.nodes.ParquetReaderExecutor;
import com.dap.nativeengine.nodes.ParquetWriterExecutor;
import com.dap.nativeengine.nodes.RowFilterExecutor;
import com.dap.nativeengine.nodes.SorterExecutor;
import com.dap.nativeengine.nodes.StringManipulationExecutor;
import io.javalin.Javalin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP entry point of the native engine: the frozen /v1 surface over the in-memory
 * workflow store, the DAG job manager and the per-session DuckDB databases.
 */
public final class NativeService implements AutoCloseable {
    public static final String NAME = "DAP";
    public static final String VERSION = "1.0.0";
    private static final String ERROR_BODY_ATTRIBUTE = "dap.errorBody";
    private static final String IN_FLIGHT_ATTRIBUTE = "dap.inFlight";

    /** Service configuration; production defaults come from the DAP_* environment. */
    public record Settings(int maxWorkflows, int defaultJobTimeoutSec, String bindHost, int port,
                           String duckdbMemoryLimit, Integer duckdbThreads, String duckdbTempDirectory,
                           int idleTimeoutSec, int queryTimeoutSec, int maxSessions,
                           int sessionTtlSec, int maxRetainedJobs, String duckdbExtensionDirectory) {
        private static final int DEFAULT_IDLE_TIMEOUT_SEC = 900;
        private static final int DEFAULT_QUERY_TIMEOUT_SEC = 60;
        private static final int DEFAULT_MAX_SESSIONS = 64;
        private static final int DEFAULT_SESSION_TTL_SEC = 1800;
        private static final int DEFAULT_MAX_RETAINED_JOBS = 200;

        public Settings {
            duckdbMemoryLimit = blankToNull(duckdbMemoryLimit);
            duckdbTempDirectory = blankToNull(duckdbTempDirectory);
            duckdbExtensionDirectory = blankToNull(duckdbExtensionDirectory);
            if (duckdbThreads != null && duckdbThreads <= 0) {
                throw new IllegalArgumentException(
                        "DAP_DUCKDB_THREADS must be a positive integer, got " + duckdbThreads);
            }
            if (idleTimeoutSec < 0) {
                throw new IllegalArgumentException(
                        "DAP_IDLE_TIMEOUT_SEC must be a non-negative integer, got " + idleTimeoutSec);
            }
            if (queryTimeoutSec < 0) {
                throw new IllegalArgumentException(
                        "DAP_QUERY_TIMEOUT_SEC must be a non-negative integer, got " + queryTimeoutSec);
            }
            if (maxSessions <= 0) {
                throw new IllegalArgumentException(
                        "DAP_MAX_SESSIONS must be a positive integer, got " + maxSessions);
            }
            if (sessionTtlSec < 0) {
                throw new IllegalArgumentException(
                        "DAP_SESSION_TTL_SEC must be a non-negative integer, got " + sessionTtlSec);
            }
            if (maxRetainedJobs <= 0) {
                throw new IllegalArgumentException(
                        "DAP_MAX_RETAINED_JOBS must be a positive integer, got " + maxRetainedJobs);
            }
        }

        /** Phase-1/2 tests build the HTTP settings only; DuckDB resources stay at their defaults. */
        public Settings(int maxWorkflows, int defaultJobTimeoutSec, String bindHost, int port) {
            this(maxWorkflows, defaultJobTimeoutSec, bindHost, port, null, null, null,
                    DEFAULT_IDLE_TIMEOUT_SEC, DEFAULT_QUERY_TIMEOUT_SEC, DEFAULT_MAX_SESSIONS,
                    DEFAULT_SESSION_TTL_SEC, DEFAULT_MAX_RETAINED_JOBS, null);
        }

        /** Phase-3 settings without an idle timeout keep the 900 s default. */
        public Settings(int maxWorkflows, int defaultJobTimeoutSec, String bindHost, int port,
                        String duckdbMemoryLimit, Integer duckdbThreads, String duckdbTempDirectory) {
            this(maxWorkflows, defaultJobTimeoutSec, bindHost, port, duckdbMemoryLimit, duckdbThreads,
                    duckdbTempDirectory, DEFAULT_IDLE_TIMEOUT_SEC, DEFAULT_QUERY_TIMEOUT_SEC,
                    DEFAULT_MAX_SESSIONS, DEFAULT_SESSION_TTL_SEC, DEFAULT_MAX_RETAINED_JOBS, null);
        }

        /** Settings without an explicit read timeout keep the 60 s default. */
        public Settings(int maxWorkflows, int defaultJobTimeoutSec, String bindHost, int port,
                        String duckdbMemoryLimit, Integer duckdbThreads, String duckdbTempDirectory,
                        int idleTimeoutSec) {
            this(maxWorkflows, defaultJobTimeoutSec, bindHost, port, duckdbMemoryLimit, duckdbThreads,
                    duckdbTempDirectory, idleTimeoutSec, DEFAULT_QUERY_TIMEOUT_SEC,
                    DEFAULT_MAX_SESSIONS, DEFAULT_SESSION_TTL_SEC, DEFAULT_MAX_RETAINED_JOBS, null);
        }

        /** Settings without explicit session limits keep the 64 sessions / 1800 s defaults. */
        public Settings(int maxWorkflows, int defaultJobTimeoutSec, String bindHost, int port,
                        String duckdbMemoryLimit, Integer duckdbThreads, String duckdbTempDirectory,
                        int idleTimeoutSec, int queryTimeoutSec) {
            this(maxWorkflows, defaultJobTimeoutSec, bindHost, port, duckdbMemoryLimit, duckdbThreads,
                    duckdbTempDirectory, idleTimeoutSec, queryTimeoutSec,
                    DEFAULT_MAX_SESSIONS, DEFAULT_SESSION_TTL_SEC, DEFAULT_MAX_RETAINED_JOBS, null);
        }

        /** Settings without an explicit job cap keep the 200 terminal jobs default. */
        public Settings(int maxWorkflows, int defaultJobTimeoutSec, String bindHost, int port,
                        String duckdbMemoryLimit, Integer duckdbThreads, String duckdbTempDirectory,
                        int idleTimeoutSec, int queryTimeoutSec, int maxSessions,
                        int sessionTtlSec) {
            this(maxWorkflows, defaultJobTimeoutSec, bindHost, port, duckdbMemoryLimit, duckdbThreads,
                    duckdbTempDirectory, idleTimeoutSec, queryTimeoutSec, maxSessions, sessionTtlSec,
                    DEFAULT_MAX_RETAINED_JOBS, null);
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
                    env.get("DAP_DUCKDB_TEMP_DIR"),
                    nonNegativeIntEnv(env, "DAP_IDLE_TIMEOUT_SEC", DEFAULT_IDLE_TIMEOUT_SEC),
                    nonNegativeIntEnv(env, "DAP_QUERY_TIMEOUT_SEC", DEFAULT_QUERY_TIMEOUT_SEC),
                    positiveIntEnv(env, "DAP_MAX_SESSIONS", DEFAULT_MAX_SESSIONS),
                    nonNegativeIntEnv(env, "DAP_SESSION_TTL_SEC", DEFAULT_SESSION_TTL_SEC),
                    positiveIntEnv(env, "DAP_MAX_RETAINED_JOBS", DEFAULT_MAX_RETAINED_JOBS),
                    extensionDirectory(env));
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

        /** Positive integer with a fallback; zero, negative and non-numeric values fail loudly. */
        private static int positiveIntEnv(Map<String, String> env, String name, int fallback) {
            int value = nonNegativeIntEnv(env, name, fallback);
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be a positive integer, got " + value);
            }
            return value;
        }

        /** Zero disables the idle watchdog; negative and non-numeric values are configuration errors. */
        private static int nonNegativeIntEnv(Map<String, String> env, String name, int fallback) {
            String raw = env.get(name);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            int value;
            try {
                value = Integer.parseInt(raw.strip());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        name + " must be a non-negative integer, got '" + raw.strip() + "'", failure);
            }
            if (value < 0) {
                throw new IllegalArgumentException(
                        name + " must be a non-negative integer, got " + value);
            }
            return value;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }

        /**
         * DAP_DUCKDB_EXTENSION_DIR, else $DAP_HOME/build/duckdb-ext, else ~/.dap/extensions.
         * A blank override keeps the default.
         */
        private static String extensionDirectory(Map<String, String> env) {
            String configured = blankToNull(env.get("DAP_DUCKDB_EXTENSION_DIR"));
            if (configured != null) {
                return configured;
            }
            String home = blankToNull(env.get("DAP_HOME"));
            if (home != null) {
                return java.nio.file.Path.of(home, "build", "duckdb-ext").toString();
            }
            return java.nio.file.Path.of(System.getProperty("user.home"), ".dap", "extensions")
                    .toString();
        }
    }

    private final Settings settings;
    private final ServiceState state;
    private final Javalin app;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    /** Boot id: a new value per process start, so clients can notice an engine restart. */
    private final String generation = java.util.UUID.randomUUID().toString();
    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ScheduledExecutorService idleWatchdog;
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
        // Every request, matched route or not, postpones the idle shutdown and counts as
        // in-flight work: a long /table, /profile or /v1/datasets call must not be cut off.
        app.before(context -> {
            lastActivity.set(System.currentTimeMillis());
            inFlight.incrementAndGet();
            context.attribute(IN_FLIGHT_ATTRIBUTE, Boolean.TRUE);
        });
        app.after(this::releaseInFlight);
        this.idleWatchdog = settings.idleTimeoutSec() > 0 || settings.sessionTtlSec() > 0
                ? java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "dap-idle-watchdog");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        app.exception(ApiException.class, (exception, context) -> {
            releaseInFlight(context);
            context.attribute(ERROR_BODY_ATTRIBUTE, true);
            Http.error(context, exception);
        });
        app.exception(Exception.class, (exception, context) -> {
            releaseInFlight(context);
            context.attribute(ERROR_BODY_ATTRIBUTE, true);
            Http.error(context, 500, exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        });
        // Unmatched routes are answered by Javalin before any handler ran, so the attribute
        // distinguishes them from a deliberate ApiException 404 with its own body.
        app.error(404, context -> {
            releaseInFlight(context);
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
        DatasetHandlers datasets = new DatasetHandlers();
        OpsHandlers ops = new OpsHandlers(state, this::shutdownAsync);

        app.get("/v1", context -> Http.ok(context, meta()));
        app.get("/v1/health", context -> Http.ok(context, health(Http.session(context))));
        app.get("/v1/sessions", context -> {
            Http.session(context);
            Http.ok(context, sessionList());
        });
        app.delete("/v1/sessions/{sessionId}", this::deleteSession);

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
        app.post("/v1/datasets", datasets::ingest);
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
        body.put("generation", generation);
        body.put("extensions", Map.of("installed",
                DuckDbSession.installedExtensions(settings.duckdbExtensionDirectory())));
        body.put("nodes", state.registry().size());
        body.put("coreNodes", state.registry().coreCount());
        body.put("workflows", state.workflows().count());
        body.put("sessions", state.sessionCount());
        body.put("jobs", state.jobs().activeCount(session));
        body.put("memory", memory);
        return body;
    }

    /** GET /v1/sessions: every open session database with its last access and workflow count. */
    private Map<String, Object> sessionList() {
        List<Map<String, Object>> views = new ArrayList<>();
        for (ServiceState.SessionView view : state.sessionViews()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", view.id());
            item.put("lastAccess", view.lastAccess());
            item.put("workflows", view.workflows());
            views.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", views.size());
        body.put("sessions", views);
        return body;
    }

    /** DELETE /v1/sessions/{id}: cancel the session jobs, drop its workflows, close its DB. */
    private void deleteSession(Context context) {
        Http.session(context);
        String sessionId = context.pathParam("sessionId");
        if (!state.closeSession(sessionId)) {
            throw ApiException.notFound("unknown session: " + sessionId);
        }
        Http.ok(context, Map.of("ok", true, "id", sessionId));
    }

    public NativeService start(int requestedPort) {
        app.start(settings.bindHost(), requestedPort);
        // Port 0 lets the OS pick a free port (used by tests); ask Jetty for the real one.
        int bound = app.jettyServer().port();
        this.port = bound > 0 ? bound : app.port();
        scheduleIdleWatchdog();
        return this;
    }

    private void scheduleIdleWatchdog() {
        if (idleWatchdog == null) {
            return;
        }
        // The tick serves both the idle shutdown and the session TTL, so it also runs when
        // only DAP_SESSION_TTL_SEC is configured (DAP_IDLE_TIMEOUT_SEC=0 disables shutdown).
        long idleMs = settings.idleTimeoutSec() * 1000L;
        long ttlMs = settings.sessionTtlSec() * 1000L;
        long windowMs = idleMs > 0 && ttlMs > 0 ? Math.min(idleMs, ttlMs) : Math.max(idleMs, ttlMs);
        long periodMs = Math.min(30_000L, Math.max(1_000L, windowMs / 2));
        idleWatchdog.scheduleAtFixedRate(this::checkIdle, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** Idle shutdown is deferred while a job is running, so a long run is never cut short. */
    private void checkIdle() {
        if (closed.get() || shuttingDown.get()) {
            return;
        }
        state.evictIdle(System.currentTimeMillis());
        if (settings.idleTimeoutSec() <= 0) {
            return;
        }
        if (System.currentTimeMillis() - lastActivity.get() < settings.idleTimeoutSec() * 1000L) {
            return;
        }
        if (inFlight.get() > 0 || state.jobs().activeCount() > 0) {
            return;
        }
        idleShutdown();
    }

    /**
     * Idle shutdown with a grace window: a request that arrives between the idle check and the
     * CAS keeps the engine alive instead of being cut off by close().
     */
    private void idleShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            long idleMs = System.currentTimeMillis() - lastActivity.get();
            if (inFlight.get() > 0 || state.jobs().activeCount() > 0
                    || idleMs < settings.idleTimeoutSec() * 1000L) {
                shuttingDown.set(false);
                return;
            }
            close();
        }, "dap-idle-shutdown");
        thread.setDaemon(true);
        thread.start();
    }

    private void releaseInFlight(Context context) {
        if (Boolean.TRUE.equals(context.attribute(IN_FLIGHT_ATTRIBUTE))) {
            context.attribute(IN_FLIGHT_ATTRIBUTE, null);
            inFlight.decrementAndGet();
        }
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
            if (idleWatchdog != null) {
                idleWatchdog.shutdownNow();
            }
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

    /** All thirty-three node executors. */
    public static Executors defaultExecutors() {
        return new Executors()
                .register(new BinnerExecutor())
                .register(new ColumnResorterExecutor())
                .register(new ConcatenateExecutor())
                .register(new DateTimeExecutor())
                .register(new DuplicateFilterExecutor())
                .register(new EncoderExecutor())
                .register(new NormalizerExecutor())
                .register(new OutlierFilterExecutor())
                .register(new PivotExecutor())
                .register(new RowAggregatorExecutor())
                .register(new RuleEngineExecutor())
                .register(new SampleRowsExecutor())
                .register(new TopNPerGroupExecutor())
                .register(new TypeCastExecutor())
                .register(new UnpivotExecutor())
                .register(new ValueReplaceExecutor())
                .register(new WindowExecutor())
                .register(new DescribeExecutor())
                .register(new ValueCountsExecutor())
                .register(new CorrelationExecutor())
                .register(new HypothesisTestExecutor())
                .register(new HistogramExecutor())
                .register(new QuantileExecutor())
                .register(new OutlierDetectExecutor())
                .register(new ResampleExecutor())
                .register(new RollingExecutor())
                .register(new DiffExecutor())
                .register(new DateRangeExecutor())
                .register(new ExcelWriterExecutor())
                .register(new CsvReaderExecutor())
                .register(new CsvWriterExecutor())
                .register(new JsonReaderExecutor())
                .register(new JsonWriterExecutor())
                .register(new LinearRegressionExecutor())
                .register(new ParquetReaderExecutor())
                .register(new ParquetWriterExecutor())
                .register(new RowFilterExecutor())
                .register(new ColumnFilterExecutor())
                .register(new ColumnRenamerExecutor())
                .register(new SorterExecutor())
                .register(new MissingValueExecutor())
                .register(new ExcelReaderExecutor())
                .register(new GroupByExecutor())
                .register(new JoinerExecutor())
                .register(new StringManipulationExecutor())
                .register(new MathFormulaExecutor())
                .register(new MarkdownReportExecutor())
                .register(new ChartExecutor())
                .register(new DataQualityExecutor());
    }

    /**
     * CLI mode: install DuckDB extensions into the configured directory and exit 0/1.
     * {@code java -jar dap-native.jar --install-extensions sqlite postgres httpfs}
     */
    public static int installExtensions(String directory, List<String> names) {
        try {
            DuckDbSession.installExtensions(directory, names);
            System.out.println("dap-native: installed " + String.join(", ", names)
                    + " into " + directory);
            return 0;
        } catch (SQLException | RuntimeException failure) {
            System.err.println("dap-native: cannot install DuckDB extensions into " + directory
                    + ": " + failure.getMessage());
            return 1;
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--install-extensions".equals(args[0])) {
            List<String> names = new ArrayList<>(List.of(args).subList(1, args.length));
            Settings configured;
            try {
                configured = Settings.fromEnv();
            } catch (IllegalArgumentException invalidSettings) {
                System.err.println("dap-native: " + invalidSettings.getMessage());
                System.exit(2);
                return;
            }
            if (names.isEmpty()) {
                System.err.println("dap-native: --install-extensions needs at least one name, "
                        + "for example: sqlite postgres httpfs");
                System.exit(2);
                return;
            }
            System.exit(installExtensions(configured.duckdbExtensionDirectory(), names));
            return;
        }
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
                defaults.duckdbMemoryLimit(), defaults.duckdbThreads(), defaults.duckdbTempDirectory(),
                defaults.idleTimeoutSec(), defaults.queryTimeoutSec(), defaults.maxSessions(),
                defaults.sessionTtlSec(), defaults.maxRetainedJobs(),
                defaults.duckdbExtensionDirectory());
        NativeService service = new NativeService(settings, new NodeRegistry(), defaultExecutors());
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "dap-shutdown-hook"));
        service.start(port);
        System.out.println("dap-native listening on http://" + bind + ":" + service.port() + "/v1");
        service.awaitStop();
    }
}
