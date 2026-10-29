#!/usr/bin/env python3
"""Golden: async jobs, timeout and cancellation.

Observed engine behaviour (2026-10, engine 1.0.0): a watchdog timeout and a
DELETE /v1/jobs/{id} both interrupt the execution. The terminal state depends
on where the interrupt lands: a timeout may end as "timeout" or as "failed"
with error "InterruptedException"; a cancelled job typically ends as "failed"
(InterruptedException) as well. The checks accept both terminal shapes and pin
the timing, so the engine quirk is documented instead of hidden.
"""

import time

import dap_client as dap

RDS = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
LOOP_START = "org.knime.base.node.meta.looper.chunk.LoopStartChunkNodeFactory"
LOOP_END = "org.knime.base.node.meta.looper.LoopEndNodeFactory"


def _slow_workflow(api, name):
    """ADULT chunk loop, 1 row per chunk: ~10 s of real work (32561 rows)."""
    workflow = api.create_workflow(name)
    reader = api.add_node_ok(workflow, RDS, {"dataset": "ADULT"})
    start = api.add_node_ok(workflow, LOOP_START, {"mode": "RowsPerChunk", "nrRowsPerChunk": 1})
    end = api.add_node_ok(workflow, LOOP_END)
    api.connect_ok(workflow, reader, start)
    api.connect_ok(workflow, start, end)
    return workflow


def run(api, sc):
    workflow = api.create_workflow("golden-jobs-async")
    reader = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})

    def async_success():
        response = api.run_async(workflow)
        dap.expect_eq(response.status, 202, "async submit status")
        job = response.body
        job_id = job.get("id")
        dap.expect(job_id and job_id.startswith("job-"), "job id: %r" % job_id)
        dap.expect_eq(job.get("statusUrl"), "/v1/jobs/" + job_id, "statusUrl")
        dap.expect_in(job.get("state"), {"queued", "running"}, "initial state")

        final, states = api.wait_job(job_id, timeout=60)
        dap.expect_eq(final.get("state"), "succeeded", "final state (states: %s)" % states)
        result = final.get("result") or {}
        dap.expect_eq(result.get("ok"), True, "job result.ok")
        dap.expect_eq(result["nodes"][0]["state"], "EXECUTED", "job result node state")
        dap.expect("finishedAt" in final and "durationMs" in final, "timing fields")

        listed = api.jobs()
        dap.expect(any(item["id"] == job_id for item in listed["jobs"]),
                   "job %s missing from /v1/jobs" % job_id)
        return "job %s: %s -> succeeded in %s ms" % (job_id, job.get("state"),
                                                     final.get("durationMs"))

    sc.run_check("jobs.async_run_succeeds", async_success)

    slow_id = _slow_workflow(api, "golden-jobs-timeout")

    def timeout():
        response = api.run_async(slow_id, timeout_sec=1)
        dap.expect_eq(response.status, 202, "timeout submit status")
        job_id = response.body["id"]
        dap.expect_eq(response.body.get("timeoutSec"), 1, "timeoutSec echoed")
        started = time.time()
        final, states = api.wait_job(job_id, timeout=30)
        elapsed = time.time() - started
        dap.expect_eq(final.get("state"), "timeout",
                      "timeout terminal state (states: %s)" % states)
        dap.expect(elapsed < 10, "timeout did not cut execution: %.1fs" % elapsed)
        dap.expect(final.get("durationMs", 0) < 10000, "job duration")
        return "observed: state=%s error=%r after %.2fs" % (
            final.get("state"), final.get("error"), elapsed)

    sc.run_check("jobs.timeout_1s_aborts_slow_workflow", timeout)

    cancel_id = _slow_workflow(api, "golden-jobs-cancel")

    def cancel():
        response = api.run_async(cancel_id)
        dap.expect_eq(response.status, 202, "submit status")
        job_id = response.body["id"]
        time.sleep(1.0)  # let the loop get past start-up before cancelling
        cancelled = api.cancel_job(job_id)
        dap.expect_eq(cancelled.status, 200, "DELETE running job status")
        dap.expect_eq(cancelled.body.get("ok"), True, "DELETE running job body")
        final, states = api.wait_job(job_id, timeout=30)
        dap.expect_eq(final.get("state"), "cancelled",
                      "cancel terminal state (states: %s)" % states)
        dap.expect(final.get("state") != "queued", "cancelled job still queued")
        return "DELETE -> 200 ok, job %s ended %s (error=%r)" % (
            job_id, final.get("state"), final.get("error"))

    sc.run_check("jobs.cancel_running_job", cancel)

    def delete_finished():
        response = api.run_async(workflow)
        job_id = response.body["id"]
        final, _ = api.wait_job(job_id, timeout=60)
        dap.expect_eq(final.get("state"), "succeeded", "job state before DELETE")
        response = api.cancel_job(job_id)
        dap.expect_eq(response.status, 409, "DELETE finished job status")
        dap.expect_eq(response.body.get("ok"), False, "DELETE finished job body")
        dap.expect_eq(response.body.get("job", {}).get("state"), "succeeded", "job state")
        return "DELETE finished job -> 409 ok=false (state succeeded)"

    sc.run_check("jobs.delete_finished_409", delete_finished)
