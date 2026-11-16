package com.dap.nativeengine.api;

import java.util.Map;

/**
 * An error with an HTTP status; the exception handler turns it into
 * {@code {"error": ..., "code": ..., "hint": ...}}. {@code code} defaults from the
 * status when absent, {@code hint} and {@code details} are omitted when empty.
 */
public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final String hint;
    private final Map<String, Object> details;

    public ApiException(int status, String message) {
        this(status, null, message, null);
    }

    public ApiException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = null;
        this.hint = null;
        this.details = Map.of();
    }

    public ApiException(int status, String code, String message, String hint) {
        this(status, code, message, hint, Map.of());
    }

    public ApiException(int status, String code, String message, String hint,
                        Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.hint = hint;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String hint() {
        return hint;
    }

    /** Extra machine-readable fields of the error body (for example {@code limit}). */
    public Map<String, Object> details() {
        return details;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public static ApiException badRequest(String code, String message, String hint) {
        return new ApiException(400, code, message, hint);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException notFound(String code, String message, String hint) {
        return new ApiException(404, code, message, hint);
    }

    public static ApiException conflict(String code, String message, String hint) {
        return new ApiException(409, code, message, hint);
    }

    public static ApiException timeout(String code, String message, String hint) {
        return new ApiException(408, code, message, hint);
    }
}
