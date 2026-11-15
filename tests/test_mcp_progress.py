#!/usr/bin/env python3
"""Unit tests for MCP progress notifications (notifications/progress).

A tools/call with params._meta.progressToken gets notifications on stdout before
the final response; without a token the adapter writes nothing extra.

    python3 -m unittest tests.test_mcp_progress -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"

TABLE = {"node": "1:1", "port": 0, "columns": [{"name": "n", "type": "Int64"}],
         "rows": 2, "data": [["1"], ["2"]], "preview": 2}
PROFILE = {"rows": 2, "columns": [{"name": "n", "type": "Number (Integer)"}]}
RUN_RESULT = {"ok": True, "durationMs": 5, "nodes": [{"node": "1:1", "state": "EXECUTED"}]}


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_progress_under_test", ADAPTER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        os.environ.clear()
        os.environ.update(saved)


def rpc_lines(module, method, params=None, msg_id=1):
    message = {"jsonrpc": "2.0", "id": msg_id, "method": method}
    if params is not None:
        message["params"] = params
    captured = io.StringIO()
    with contextlib.redirect_stdout(captured):
        module.handle(message)
    return [json.loads(line) for line in captured.getvalue().splitlines() if line.strip()]


def call_lines(module, name, arguments, token=None):
    params = {"name": name, "arguments": arguments}
    if token is not None:
        params["_meta"] = {"progressToken": token}
    return rpc_lines(module, "tools/call", params)


def notifications(lines):
    return [line for line in lines if line.get("method") == "notifications/progress"]


def replies(lines):
    return [line for line in lines if "id" in line]


class JobEngine:
    """POST /run with async=true returns a job; GET /v1/jobs/{id} reports progress."""

    def __init__(self, states=None):
        self.calls = []
        self.states = list(states or [
            {"id": "job-1", "state": "running",
             "progress": {"nodesDone": 0, "nodesTotal": 2, "currentNode": "1:1",
                          "percent": 0}},
            {"id": "job-1", "state": "running",
             "progress": {"nodesDone": 1, "nodesTotal": 2, "currentNode": "1:2",
                          "percent": 50}},
            {"id": "job-1", "state": "succeeded", "result": RUN_RESULT,
             "progress": {"nodesDone": 2, "nodesTotal": 2, "currentNode": None,
                          "percent": 100}},
        ])

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            return {"id": "job-1", "workflow": "wf-1", "state": "queued",
                    "statusUrl": "/v1/jobs/job-1"}
        if method == "GET" and path == "/v1/jobs/job-1":
            return self.states.pop(0) if len(self.states) > 1 else self.states[0]
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))


class SyncEngine:
    """The plain synchronous /run: used to prove the no-token path is unchanged."""

    def __init__(self):
        self.calls = []

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            return RUN_RESULT
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))


class AnalyzeEngine:
    def __init__(self):
        self.calls = []

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/datasets":
            return {"path": "/cache/sales.parquet", "rows": 2, "cached": False}
        if method == "POST" and path == "/v1/workflows":
            return {"id": "wf-1", "name": "analyze"}
        if method == "POST" and path == "/v1/workflows/wf-1/nodes":
            return {"node": "1:1", "name": "Parquet Reader"}
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            return RUN_RESULT
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:1/table"):
            return TABLE
        if method == "GET" and path == "/v1/workflows/wf-1/nodes/1:1/profile":
            return PROFILE
        if method == "DELETE" and path == "/v1/workflows/wf-1":
            return {"ok": True}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))


class ProgressTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-progress-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)
        self.module.PROGRESS_POLL = 0

    def run_tool(self, name, arguments, engine, token=None):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            return call_lines(self.module, name, arguments, token=token)

    # -- dap_run: async job + progress --------------------------------------

    def test_run_with_token_polls_the_job_and_emits_progress(self):
        engine = JobEngine()
        lines = self.run_tool("dap_run", {"workflow": "wf-1"}, engine, token="tok-1")

        self.assertEqual(replies(lines)[0]["result"]["content"][0]["text"],
                         json.dumps(RUN_RESULT, ensure_ascii=False))
        notes = notifications(lines)
        self.assertEqual(len(notes), 3, lines)
        for note in notes:
            self.assertEqual(note["jsonrpc"], "2.0")
            self.assertEqual(note["params"]["progressToken"], "tok-1")
            self.assertIn("progress", note["params"])
            self.assertEqual(note["params"]["total"], 2)
        self.assertEqual([note["params"]["progress"] for note in notes], [0, 1, 2])
        self.assertEqual([note["params"].get("message") for note in notes],
                         ["1:1", "1:2", None])
        # The notifications precede the final response.
        self.assertEqual(lines[-1], replies(lines)[0])
        self.assertEqual(engine.calls[0][0:2], ("POST", "/v1/workflows/wf-1/run"))
        self.assertEqual(engine.calls[0][2], {"async": True})
        self.assertEqual([path for method, path, _ in engine.calls[1:]],
                         ["/v1/jobs/job-1"] * 3)

    def test_run_without_token_uses_the_synchronous_call(self):
        engine = SyncEngine()
        lines = self.run_tool("dap_run", {"workflow": "wf-1"}, engine)
        self.assertEqual(notifications(lines), [])
        self.assertEqual(len(replies(lines)), 1)
        self.assertEqual(engine.calls, [("POST", "/v1/workflows/wf-1/run", {})])

    def test_run_with_token_passes_timeout_sec_to_the_async_call(self):
        engine = JobEngine()
        self.run_tool("dap_run", {"workflow": "wf-1", "timeoutSec": 30}, engine,
                      token="tok-1")
        self.assertEqual(engine.calls[0][2], {"async": True, "timeoutSec": 30})

    def test_run_with_token_returns_the_failed_job_payload(self):
        engine = JobEngine(states=[
            {"id": "job-1", "state": "failed", "error": "boom",
             "progress": {"nodesDone": 0, "nodesTotal": 1, "currentNode": "1:1"}},
        ])
        lines = self.run_tool("dap_run", {"workflow": "wf-1"}, engine, token="tok-1")
        payload = json.loads(replies(lines)[0]["result"]["content"][0]["text"])
        self.assertEqual(payload["state"], "failed")
        self.assertEqual(payload["error"], "boom")

    def test_run_falls_back_to_the_sync_result_when_the_engine_ignores_async(self):
        class AsyncIgnored(SyncEngine):
            pass

        engine = AsyncIgnored()
        lines = self.run_tool("dap_run", {"workflow": "wf-1"}, engine, token="tok-1")
        payload = json.loads(replies(lines)[0]["result"]["content"][0]["text"])
        self.assertEqual(payload, RUN_RESULT)

    def test_progress_token_is_cleared_after_the_call(self):
        self.run_tool("dap_run", {"workflow": "wf-1"}, JobEngine(), token="tok-1")
        self.assertIsNone(self.module._PROGRESS_TOKEN)
        lines = self.run_tool("dap_run", {"workflow": "wf-1"}, SyncEngine())
        self.assertEqual(notifications(lines), [])

    # -- stage progress for the one-call tools ------------------------------

    def test_analyze_emits_ingest_run_table_profile_stages(self):
        engine = AnalyzeEngine()
        lines = self.run_tool("dap_analyze", {"file": "/data/sales.csv"}, engine,
                              token="tok-2")
        notes = notifications(lines)
        self.assertEqual([note["params"]["message"] for note in notes],
                         ["ingest", "run", "table", "profile"])
        self.assertEqual([note["params"]["progress"] for note in notes], [1, 2, 3, 4])
        self.assertEqual({note["params"]["total"] for note in notes}, {4})
        self.assertEqual({note["params"]["progressToken"] for note in notes}, {"tok-2"})
        self.assertEqual(lines[-1], replies(lines)[0])

    def test_analyze_without_token_emits_nothing(self):
        engine = AnalyzeEngine()
        lines = self.run_tool("dap_analyze", {"file": "/data/sales.csv"}, engine)
        self.assertEqual(notifications(lines), [])
        self.assertEqual(len(replies(lines)), 1)

    def test_analyze_without_profile_has_three_stages(self):
        engine = AnalyzeEngine()
        lines = self.run_tool("dap_analyze", {"file": "/data/sales.csv",
                                              "profile": False}, engine, token="t")
        notes = notifications(lines)
        self.assertEqual([note["params"]["message"] for note in notes],
                         ["ingest", "run", "table"])
        self.assertEqual({note["params"]["total"] for note in notes}, {3})


if __name__ == "__main__":
    unittest.main(verbosity=2)
