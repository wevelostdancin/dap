package com.dap.nativeengine.api;

import io.javalin.http.Context;

import java.util.LinkedHashMap;
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
        error(context, status, null, message, null, Map.of());
    }

    /** Error envelope of an ApiException: error, its code (or the status default) and hint. */
    public static void error(Context context, ApiException exception) {
        error(context, exception.status(), exception.code(), exception.getMessage(),
                exception.hint(), exception.details());
    }

    private static void error(Context context, int status, String code, String message,
                              String hint, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message == null || message.isBlank() ? "error" : message);
        body.put("code", code == null || code.isBlank() ? codeForStatus(status) : code);
        if (hint != null && !hint.isBlank()) {
            body.put("hint", hint);
        }
        if (details != null) {
            body.putAll(details);
        }
        json(context, status, body);
    }

    /** Machine-readable default code for an error without an explicit one. */
    public static String codeForStatus(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 409 -> "conflict";
            default -> "server_error";
        };
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
            throw ApiException.badRequest("invalid_query_param", "invalid " + name + ": " + raw,
                    "pass an integer for '" + name + "'");
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
