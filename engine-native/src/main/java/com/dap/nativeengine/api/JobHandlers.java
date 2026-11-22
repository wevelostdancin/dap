package com.dap.nativeengine.api;

import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.exec.Job;
import com.dap.nativeengine.exec.JobState;
import com.dap.nativeengine.exec.Workflow;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workflow execution and job status routes. */
public final class JobHandlers {
    private final ServiceState state;

    public JobHandlers(ServiceState state) {
        this.state = state;
    }

    public void run(Context ctx) throws Exception {
        String session = Http.session(ctx);
        JsonNode request = Json.parseBody(ctx.body());
        boolean async = request.path("async").asBoolean(false) || Http.queryBoolean(ctx, "async", false);
        Job job = submit(ctx, session, request);
        if (async) {
            Http.json(ctx, 202, view(job, false));
            return;
        }
        job.await();
        if (job.state() == JobState.succeeded) {
            Http.ok(ctx, job.result());
        } else if (job.state() == JobState.timeout) {
            Http.json(ctx, 408, view(job, false));
        } else {
            Map<String, Object> body = view(job, false);
            body.put("code", "node_failed");
            Http.json(ctx, 500, body);
        }
    }

    public void submit(Context ctx) throws Exception {
        String session = Http.session(ctx);
        JsonNode request = Json.parseBody(ctx.body());
        Job job = submit(ctx, session, request);
        Http.json(ctx, 202, view(job, false));
    }

    public void list(Context ctx) {
        String session = Http.session(ctx);
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Job job : state.jobs().list(session)) {
            jobs.add(view(job, false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", jobs.size());
        body.put("jobs", jobs);
        Http.ok(ctx, body);
    }

    public void get(Context ctx) {
        String session = Http.session(ctx);
        String jobId = ctx.pathParam("jobId");
        Job job = state.jobs().find(session, jobId);
        if (job == null) {
            throw ApiException.notFound("unknown job: " + jobId);
        }
        Http.ok(ctx, view(job, true));
    }

    public void cancel(Context ctx) {
        String session = Http.session(ctx);
        String jobId = ctx.pathParam("jobId");
        Job job = state.jobs().find(session, jobId);
        if (job == null) {
            throw ApiException.notFound("unknown job: " + jobId);
        }
        // Only a running job owns materializations: cancelling a queued job must not kill
        // the /table or /profile read of the sibling run that is currently executing.
        boolean wasRunning = job.state() == JobState.running;
        boolean cancelled = state.jobs().cancel(job);
        if (cancelled && wasRunning) {
            // A /table or /profile read runs on its own connection, so the job-connection
            // close above cannot reach it; close only the reads this job started. Other
            // clients of the same workflow keep their (unowned) reads.
            DuckDbSession db = state.sessionIfPresent(job.sessionId());
            if (db != null) {
                db.interruptReads(job.workflowId(), job.id());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", cancelled);
        body.put("id", jobId);
        body.put("job", view(job, true));
        if (!cancelled) {
            body.put("error", "job is not cancellable: " + jobId + " is " + job.state());
            body.put("code", "not_cancellable");
        }
        Http.json(ctx, cancelled ? 200 : 409, body);
    }

    private Job submit(Context ctx, String session, JsonNode request) {
        Workflow workflow = state.requireWorkflow(session, ctx.pathParam("workflowId"));
        int timeoutSec = request.path("timeoutSec").asInt(0);
        if (timeoutSec <= 0) {
            timeoutSec = state.settings().defaultJobTimeoutSec();
        }
        // Node results are reset by the job manager at the start of each run (per-workflow
        // serialization); resetting here as well would race with a queued second run.
        return state.jobs().submit(workflow, state.session(session), timeoutSec);
    }

    /** Serialize a job explicitly: Job fields are internal and only part of them is contract. */
    static Map<String, Object> view(Job job, boolean withResult) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", job.id());
        view.put("workflow", job.workflowId());
        view.put("state", job.state().name());
        view.put("createdAt", job.createdAt());
        view.put("timeoutSec", job.timeoutSec());
        if (job.startedAt() > 0) {
            view.put("startedAt", job.startedAt());
            view.put("durationMs", job.durationMs());
        }
        if (job.finishedAt() > 0) {
            view.put("finishedAt", job.finishedAt());
            view.put("durationMs", job.durationMs());
        }
        if (job.error() != null) {
            view.put("error", job.error());
        }
        if (withResult && job.result() != null) {
            view.put("result", job.result());
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("nodesTotal", job.nodesTotal());
        progress.put("nodesDone", job.nodesDone());
        progress.put("currentNode", job.currentNode());
        progress.put("percent", job.percent());
        view.put("progress", progress);
        view.put("statusUrl", job.statusUrl());
        return view;
    }
}
