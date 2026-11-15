#!/usr/bin/env python3
"""Unit tests for dap_estimate: a temp workflow plus GET /estimate, then cleanup.

    python3 -m unittest tests.test_mcp_estimate -v
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

ESTIMATE_READER = {
    "node": "1:1",
    "rows": 150,
    "operators": [{"name": "SEQ_SCAN", "rows": 150}, {"name": "PROJECTION", "rows": 150}],
    "plan": {"name": "PROJECTION", "children": [{"name": "SEQ_SCAN"}]},
    "columns": [{"name": "sepal_length", "type": "Double"}],
}
ESTIMATE_QUERY = {
    "node": "1:2",
    "rows": 3,
    "operators": [{"name": "HASH_GROUP_BY", "rows": 3}],
    "plan": {"name": "HASH_GROUP_BY"},
}


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_estimate_under_test", ADAPTER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        os.environ.clear()
        os.environ.update(saved)


def rpc(module, method, params=None, msg_id=1):
    message = {"jsonrpc": "2.0", "id": msg_id, "method": method}
    if params is not None:
        message["params"] = params
    captured = io.StringIO()
    with contextlib.redirect_stdout(captured):
        module.handle(message)
    replies = [json.loads(line) for line in captured.getvalue().splitlines() if line.strip()]
    assert len(replies) == 1, "expected one reply, got %r" % (replies,)
    return replies[0]


def tool_ok(module, name, arguments):
    reply = rpc(module, "tools/call", {"name": name, "arguments": arguments})
    assert "result" in reply, "%s failed: %r" % (name, reply)
    return json.loads(reply["result"]["content"][0]["text"])


def tool_error(module, name, arguments):
    reply = rpc(module, "tools/call", {"name": name, "arguments": arguments})
    assert "error" in reply, "%s unexpectedly succeeded: %r" % (name, reply)
    return reply["error"]


class FakeEngine:
    def __init__(self, fail_estimate=None):
        self.calls = []
        self.fail_estimate = fail_estimate

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/datasets":
            return {"path": "/cache/sales.parquet", "rows": 3, "cached": False}
        if method == "POST" and path == "/v1/workflows":
            return {"id": "wf-1", "name": "estimate", "session": "default", "nodes": 0}
        if method == "POST" and path == "/v1/workflows/wf-1/nodes":
            factory = (body or {}).get("factory")
            if factory == "dap.parquet_reader":
                return {"node": "1:1", "name": "Parquet Reader"}
            if factory == "dap.sql_query":
                return {"node": "1:2", "name": "SQL Query"}
            raise AssertionError("unexpected factory: %r" % (factory,))
        if method == "POST" and path == "/v1/workflows/wf-1/connect":
            return {"ok": True}
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            return {"ok": True, "durationMs": 1, "nodes": []}
        if method == "GET" and path == "/v1/workflows/wf-1/nodes/1:1/estimate":
            if self.fail_estimate is not None:
                raise self.fail_estimate
            return ESTIMATE_READER
        if method == "GET" and path == "/v1/workflows/wf-1/nodes/1:2/estimate":
            if self.fail_estimate is not None:
                raise self.fail_estimate
            return ESTIMATE_QUERY
        if method == "DELETE" and path == "/v1/workflows/wf-1":
            return {"ok": True}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def paths(self):
        return [(method, path) for method, path, _ in self.calls]

    def bodies(self, factory):
        return [body for method, path, body in self.calls
                if method == "POST" and path == "/v1/workflows/wf-1/nodes"
                and (body or {}).get("factory") == factory]


class EstimateTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-estimate-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def run_tool(self, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_estimate", arguments)
        return result, engine

    def run_error(self, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, "dap_estimate", arguments)
        return error, engine

    def test_estimate_for_a_file_ingests_builds_reads_and_deletes(self):
        result, engine = self.run_tool({"file": "/data/iris.csv"})
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertFalse(result["cached"])
        self.assertEqual(result["node"], "1:1")
        self.assertEqual(result["rows"], 150)
        self.assertEqual(result["operators"], ESTIMATE_READER["operators"])
        self.assertEqual(result["plan"], ESTIMATE_READER["plan"])
        self.assertEqual(result["columns"], ESTIMATE_READER["columns"])
        self.assertEqual(engine.paths(), [
            ("POST", "/v1/datasets"),
            ("POST", "/v1/workflows"),
            ("POST", "/v1/workflows/wf-1/nodes"),
            ("POST", "/v1/workflows/wf-1/run"),
            ("GET", "/v1/workflows/wf-1/nodes/1:1/estimate"),
            ("DELETE", "/v1/workflows/wf-1"),
        ])
        self.assertEqual(engine.bodies("dap.parquet_reader")[0]["settings"],
                         {"file": "/cache/sales.parquet"})

    def test_estimate_with_sql_estimates_the_query_node(self):
        result, engine = self.run_tool({
            "dataset": "/cache/sales.parquet",
            "sql": "SELECT region, COUNT(*) AS n FROM src GROUP BY region",
        })
        self.assertEqual(result["node"], "1:2")
        self.assertEqual(result["rows"], 3)
        self.assertEqual(engine.bodies("dap.sql_query")[0]["settings"],
                         {"sql": "SELECT region, COUNT(*) AS n FROM src GROUP BY region",
                          "inputs": ["src"]})
        self.assertIn(("POST", "/v1/workflows/wf-1/connect"), engine.paths())
        self.assertIn(("POST", "/v1/workflows/wf-1/run"), engine.paths())
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())
        self.assertNotIn(("POST", "/v1/datasets"), engine.paths())

    def test_estimate_for_a_dataset_does_not_ingest(self):
        result, engine = self.run_tool({"dataset": "/cache/sales.parquet"})
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertNotIn(("POST", "/v1/datasets"), engine.paths())
        self.assertIn(("GET", "/v1/workflows/wf-1/nodes/1:1/estimate"), engine.paths())

    def test_limit_is_passed_to_the_sql_node(self):
        _result, engine = self.run_tool({"dataset": "/cache/x.parquet",
                                         "sql": "SELECT 1 AS n", "limit": 5})
        self.assertEqual(engine.bodies("dap.sql_query")[0]["settings"]["limit"], 5)

    def test_estimate_response_omits_absent_optional_fields(self):
        engine = FakeEngine()
        original = engine.__call__

        def sparse(method, path, body=None, timeout=None):
            if path.endswith("/estimate"):
                engine.calls.append((method, path, body))
                return {"node": "1:1", "rows": 7}
            return original(method, path, body, timeout)

        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=sparse):
            result = tool_ok(self.module, "dap_estimate",
                             {"dataset": "/cache/x.parquet"})
        self.assertEqual(result["rows"], 7)
        self.assertEqual(result["operators"], [])
        self.assertIsNone(result["plan"])
        self.assertNotIn("columns", result)

    # -- validation ----------------------------------------------------------

    def test_missing_source_is_rejected_before_http(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_estimate", {})
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()

    def test_both_sources_are_rejected_before_http(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_estimate",
                               {"file": "/a.csv", "dataset": "/b.parquet"})
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()

    def test_ddl_is_rejected_before_http_and_before_the_engine_starts(self):
        with mock.patch.object(self.module, "ensure_service") as ensure, \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_estimate",
                               {"dataset": "/cache/x.parquet", "sql": "DROP TABLE src"})
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()
        ensure.assert_not_called()

    # -- engine errors -------------------------------------------------------

    def test_engine_error_is_returned_and_the_workflow_is_deleted(self):
        failure = self.module.DapError(
            "estimate is not available", data={"status": 409, "code": "estimate_unavailable"})
        error, engine = self.run_error({"dataset": "/cache/x.parquet"},
                                       fail_estimate=failure)
        self.assertEqual(error["code"], -32000)
        self.assertEqual(error.get("data", {}).get("code"), "estimate_unavailable")
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())


if __name__ == "__main__":
    unittest.main(verbosity=2)
