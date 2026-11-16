package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.LazyPlan;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One node instance inside a workflow. Mutable state is volatile: /run and /table race. */
public final class WorkflowNode {
    private final String id;
    private final String factory;
    private final String name;
    private volatile ObjectNode config;
    private volatile String state = "IDLE";
    private volatile String message;
    private volatile LazyPlan result;

    public WorkflowNode(String id, String factory, String name, ObjectNode config) {
        this.id = id;
        this.factory = factory;
        this.name = name;
        this.config = config == null ? com.dap.nativeengine.api.Json.object() : config;
    }

    public String id() {
        return id;
    }

    public String factory() {
        return factory;
    }

    public String name() {
        return name;
    }

    public ObjectNode config() {
        return config;
    }

    public void config(ObjectNode config) {
        this.config = config == null ? com.dap.nativeengine.api.Json.object() : config;
    }

    public String state() {
        return state;
    }

    public String message() {
        return message;
    }

    public LazyPlan result() {
        return result;
    }

    public void reset() {
        state = "IDLE";
        message = null;
        result = null;
    }

    public void executed(LazyPlan plan) {
        this.result = plan;
        this.state = "EXECUTED";
        this.message = null;
    }

    public void failed(String error) {
        this.result = null;
        this.state = "ERROR";
        this.message = error;
    }

    public void running() {
        this.state = "RUNNING";
        this.message = null;
    }
}
