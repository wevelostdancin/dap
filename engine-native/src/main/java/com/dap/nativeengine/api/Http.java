package com.dap.nativeengine.api;

import io.javalin.http.Context;

import java.util.Map;

/** Small HTTP helpers: JSON responses, session ids and query parsing. */
public final class Http {
    public static final String API_VERSION = "1";
    public static final String SESSION_HEADER = "X-DAP-Session";

    private Http() {
    }

    public static void json(Context context, int status, Object body) {
        context.status(status);
        context.contentType("application/json; charset=utf-8");
        context.header("X-DAP-Api-Version", API_VERSION);
        context.result(Json.write(body));
    }

    public static void ok(Context context, Object body) {
        json(context, 200, body);
    }

    public static void error(Context context, int status, String message) {
        json(context, status, Map.of("error", message == null ? "error" : message));
    }

    /** Validate and return the session id, echoing it back in the response. */
    public static String session(Context context) {
        String session = context.header(SESSION_HEADER);
        if (session == null || session.isEmpty()) {
            session = context.queryParam("session");
        }
        if (session == null || session.isEmpty()) {
            session = "default";
        }
        if (!session.matches("[A-Za-z0-9._-]{1,64}")) {
            throw ApiException.badRequest("invalid session id");
        }
        context.header(SESSION_HEADER, session);
        return session;
    }

    public static int queryInt(Context context, String name, int fallback) {
        String raw = context.queryParam(name);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new ApiException(500, "invalid " + name + ": " + raw);
        }
    }

    public static boolean queryBoolean(Context context, String name, boolean fallback) {
        String raw = context.queryParam(name);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw);
    }
}
