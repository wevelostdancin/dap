package com.dap.nativeengine.exec;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Node state in a run result. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeStateView(String node, String name, String state, String message) {
    public NodeStateView(String node, String name, String state) {
        this(node, name, state, null);
    }
}
