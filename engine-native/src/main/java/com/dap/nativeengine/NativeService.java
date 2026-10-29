package com.dap.nativeengine;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.api.Http;
import io.javalin.Javalin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP entry point. Phase 1 skeleton: /v1 and /v1/health are wired here; the
 * remaining routes live in the api package and are registered by the routes task.
 */
public final class NativeService implements AutoCloseable {
    public static final String NAME = "DAP";
    public static final String VERSION = "1.0.0";

    private final Javalin app;
    private volatile int port;
    private volatile boolean shuttingDown;

    public NativeService() {
        this.app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.defaultContentType = "application/json; charset=utf-8";
        });
        app.exception(ApiException.class, (exception, context) ->
                Http.error(context, exception.status(), exception.getMessage()));
        app.exception(Exception.class, (exception, context) ->
                Http.error(context, 500, exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage()));
        app.get("/v1", context -> Http.ok(context, meta()));
        app.get("/v1/health", context -> Http.ok(context, health()));
        app.error(404, context -> Http.error(context, 404, "no route: " + context.method() + " " + context.path()));
    }

    private static Map<String, Object> meta() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", NAME);
        body.put("version", VERSION);
        body.put("api", "v1");
        body.put("endpoints", "/v1/nodes, /v1/workflows, /v1/jobs, /v1/health");
        return body;
    }

    private Map<String, Object> health() {
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
        body.put("nodes", 0);
        body.put("coreNodes", 0);
        body.put("workflows", 0);
        body.put("sessions", 0);
        body.put("jobs", 0);
        body.put("memory", memory);
        return body;
    }

    public NativeService start(int requestedPort) {
        this.port = requestedPort;
        app.start(requestedPort);
        this.port = app.port();
        return this;
    }

    public int port() {
        return port;
    }

    public Javalin app() {
        return app;
    }

    public boolean shuttingDown() {
        return shuttingDown;
    }

    @Override
    public void close() {
        app.stop();
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getProperty("dap.port", System.getenv().getOrDefault("DAP_PORT", "7800")));
        String bind = System.getenv().getOrDefault("DAP_BIND", "127.0.0.1");
        if (!"127.0.0.1".equals(bind) && !"localhost".equals(bind) && !"0.0.0.0".equals(bind)) {
            System.err.println("dap-native: unsupported DAP_BIND " + bind);
            System.exit(2);
        }
        NativeService service = new NativeService();
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "dap-shutdown"));
        service.start(port);
        System.out.println("dap-native listening on http://" + bind + ":" + service.port() + "/v1");
    }
}
