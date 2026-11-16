package com.dap.nativeengine.exec;

/** A directed edge between two workflow nodes (data ports, 0-based). */
public record Connection(String from, int fromPort, String to, int toPort) {
    public Connection {
        if (from == null || to == null) {
            throw new IllegalArgumentException("connection endpoints are required");
        }
        if (fromPort < 0 || toPort < 0) {
            throw new IllegalArgumentException("port indices are 0-based");
        }
    }
}
