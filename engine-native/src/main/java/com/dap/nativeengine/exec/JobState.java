package com.dap.nativeengine.exec;

/** Terminal states are succeeded, failed, cancelled and timeout. */
public enum JobState {
    queued,
    running,
    succeeded,
    failed,
    cancelled,
    timeout;

    public boolean terminal() {
        return this == succeeded || this == failed || this == cancelled || this == timeout;
    }
}
