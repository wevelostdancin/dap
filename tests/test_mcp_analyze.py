
#!/usr/bin/env python3
"""Unit tests for dap_analyze and dap_workflow_build (mocked /v1)."""
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

TABLE_READER = {"node": "1:1", "port": 0, "columns": [{"name": "n", "type": "Int64"}],
                "rows": 2, "data": [["1"], ["2"]], "preview": 2}
TABLE_QUERY = {"node": "1:2", "port": 0, "columns": [{"name": "total", "type": "Float64"}],
               "rows": 1, "data": [["3.0"]], "preview": 1}
PROFILE = {"rows": 2, "columns": [{"name": "n", "type": "Number (Integer)", "missing": 0, "distinct": 2}]}


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_under_test", ADAPTER)
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
    def __init__(self, fail_factory=None, fail_run=False):
        self.calls = []
        self.fail_factory = fail_factory
        self.fail_run = fail_run

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/datasets":
            return {"path": "/cache/sales.parquet", "rows": 3, "cached": False}
        if method == "POST" and path == "/v1/workflows":
            return {"id": "wf-1", "name": "x", "session": "default", "nodes": 0}
        if method == "POST" and path == "/v1/workflows/wf-1/nodes":
            factory = (body or {}).get("factory")
            if factory == self.fail_factory:
                raise RuntimeError("boom: " + str(factory))
            if factory == "dap.parquet_reader":
                return {"node": "1:1", "name": "Parquet Reader"}
            if factory == "dap.sql_query":
                return {"node": "1:2", "name": "SQL Query"}
            return {"node": "1:3", "name": "Node"}
        if method == "POST" and path == "/v1/workflows/wf-1/connect":
            return {"ok": True}
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            if self.fail_run:
                raise RuntimeError("run boom")
            return {"ok": True, "durationMs": 1, "nodes": []}
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:1/table"):
            return TABLE_READER
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:2/table"):
            return TABLE_QUERY
        if method == "GET" and path == "/v1/workflows/wf-1/nodes/1:1/profile":
            return PROFILE
        if method == "DELETE" and path == "/v1/workflows/wf-1":
            return {"ok": True}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def paths(self):
        return [(method, path) for method, path, _ in self.calls]

    def body_of(self, method, path):
        for call_method, call_path, body in self.calls:
            if call_method == method and call_path == path:
                return body
        raise AssertionError("no %s %s call: %r" % (method, path, self.calls))


class AnalyzeTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-analyze-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def run_tool(self, name, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, name, arguments)
        return result, engine

    def run_error(self, name, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, name, arguments)
        return error, engine

    def test_analyze_file_returns_table_and_profile_in_one_call(self):
        result, engine = self.run_tool("dap_analyze", {"file": "/data/sales.csv"})
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertEqual(result["table"], TABLE_READER)
        self.assertEqual(result["profile"], PROFILE)
        self.assertFalse(result["cached"])
        self.assertIn(("POST", "/v1/datasets"), engine.paths())
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())
        factories = [body["factory"] for method, path, body in engine.calls
                     if method == "POST" and path == "/v1/workflows/wf-1/nodes"]
        self.assertEqual(factories, ["dap.parquet_reader"])

    def test_analyze_reports_ingest_cache_hit(self):
        engine = FakeEngine()
        original = engine.__call__

        def cached_engine(method, path, body=None, timeout=None):
            if method == "POST" and path == "/v1/datasets":
                engine.calls.append((method, path, body))
                return {"path": "/cache/sales.parquet", "rows": 3, "cached": True}
            return original(method, path, body, timeout)

        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=cached_engine):
            result = tool_ok(self.module, "dap_analyze", {"file": "/data/sales.csv"})
        self.assertTrue(result["cached"])

    def test_analyze_with_sql_uses_src_alias_and_does_not_cap_the_query(self):
        result, engine = self.run_tool("dap_analyze", {
            "file": "/data/sales.csv", "sql": "SELECT COUNT(*) AS total FROM src"})
        self.assertEqual(result["table"], TABLE_QUERY)
        self.assertEqual(result["node"], "1:2")
        sql_body = [body for method, path, body in engine.calls
                    if method == "POST" and path == "/v1/workflows/wf-1/nodes"
                    and body.get("factory") == "dap.sql_query"][0]
        self.assertEqual(sql_body["settings"], {"sql": "SELECT COUNT(*) AS total FROM src",
                                                "inputs": ["src"]})
        self.assertIn(("POST", "/v1/workflows/wf-1/connect"), engine.paths())

    def test_analyze_requires_file_or_dataset(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_analyze", {})
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()

    def test_analyze_rejects_both_file_and_dataset(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_analyze",
                               {"file": "/a.csv", "dataset": "/b.parquet"})
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()

    def test_analyze_rejects_ddl_before_http_and_before_engine_start(self):
        with mock.patch.object(self.module, "ensure_service") as ensure, \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_analyze",
                               {"file": "/data/sales.csv", "sql": "DROP TABLE src"})
        self.assertEqual(error["code"], -32602)
        self.assertEqual(error.get("data", {}).get("code"), "sql_not_allowed")
        call.assert_not_called()
        ensure.assert_not_called()

    def test_analyze_deletes_the_workflow_when_run_fails(self):
        error, engine = self.run_error("dap_analyze", {"dataset": "/cache/x.parquet"},
                                       fail_run=True)
        self.assertEqual(error["code"], -32603)
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())


class WorkflowBuildTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-build-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def run_tool(self, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_workflow_build", arguments)
        return result, engine

    def run_error(self, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, "dap_workflow_build", arguments)
        return error, engine

    def test_builds_graph_runs_and_returns_outputs_in_one_call(self):
        result, engine = self.run_tool({
            "nodes": [
                {"key": "src", "factory": "dap.parquet_reader", "settings": {"file": "/data/x.parquet"}},
                {"key": "q", "factory": "dap.sql_query", "settings": {"sql": "SELECT 1 AS n"}},
            ],
            "edges": [{"from": "src", "to": "q"}],
            "run": True,
            "outputs": ["q"],
        })
        self.assertEqual(result["workflow"], "wf-1")
        self.assertEqual(result["nodes"], {"src": "1:1", "q": "1:2"})
        self.assertEqual(result["tables"]["q"], TABLE_QUERY)
        self.assertIn(("POST", "/v1/workflows/wf-1/run"), engine.paths())
        connected = engine.body_of("POST", "/v1/workflows/wf-1/connect")
        self.assertEqual((connected["from"], connected["to"]), ("1:1", "1:2"))

    def test_accepts_integer_node_indices(self):
        result, _engine = self.run_tool({
            "nodes": [{"factory": "dap.parquet_reader"}, {"factory": "dap.sql_query"}],
            "edges": [{"from": 0, "to": 1}],
            "run": True,
            "outputs": [1],
        })
        self.assertEqual(result["nodes"], {"0": "1:1", "1": "1:2"})
        self.assertEqual(result["tables"]["1"], TABLE_QUERY)

    def test_unknown_edge_is_rejected_before_http(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_workflow_build", {
                "nodes": [{"key": "src", "factory": "dap.parquet_reader"}],
                "edges": [{"from": "src", "to": "missing"}],
            })
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()

    def test_duplicate_keys_are_rejected(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_workflow_build", {
                "nodes": [{"key": "a", "factory": "dap.parquet_reader"},
                          {"key": "a", "factory": "dap.parquet_reader"}],
            })
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()

    def test_explicit_key_zero_conflicting_with_index_is_rejected(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_workflow_build", {
                "nodes": [{"factory": "dap.parquet_reader"},
                          {"key": 0, "factory": "dap.sql_query"}],
            })
        self.assertEqual(error["code"], -32602)
        self.assertIn("дублирующийся ключ", error["message"])
        call.assert_not_called()

    def test_outputs_require_run(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_workflow_build", {
                "nodes": [{"key": "src", "factory": "dap.parquet_reader"}],
                "outputs": ["src"],
            })
        self.assertEqual(error["code"], -32602)
        self.assertIn("run=true", error["message"])
        call.assert_not_called()

    def test_unknown_node_key_is_rejected(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_workflow_build", {
                "nodes": [{"factory": "dap.parquet_reader", "setings": {}}],
            })
        self.assertEqual(error["code"], -32602)
        self.assertIn("неизвестные ключи", error["message"])
        call.assert_not_called()

    def test_non_boolean_run_is_rejected(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_workflow_build", {
                "nodes": [{"factory": "dap.parquet_reader"}], "run": "true",
            })
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()

    def test_failure_after_create_deletes_the_workflow(self):
        error, engine = self.run_error({
            "nodes": [{"key": "bad", "factory": "dap.nope"}],
        }, fail_factory="dap.nope")
        self.assertEqual(error["code"], -32603)
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())


if __name__ == "__main__":
    unittest.main()
