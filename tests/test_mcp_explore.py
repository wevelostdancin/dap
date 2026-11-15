#!/usr/bin/env python3
"""Unit tests for dap_explore: POST /v1/explore after an optional ingest.

    python3 -m unittest tests.test_mcp_explore -v
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

REPORT = {
    "source": "/cache/sales.parquet",
    "rows": 150,
    "columns": [{"name": "region", "type": "String", "missing": 0, "distinct": 2}],
    "quality": {"score": 92, "issues": []},
    "relationships": [{"left": "a", "right": "b", "method": "pearson",
                       "value": 0.96, "p_value": 1e-20, "n": 150,
                       "strength": "very strong"}],
    "group_differences": [],
    "time": None,
    "hypotheses": [{"id": "h1", "question": "does a depend on b?",
                    "test": "pearson", "columns": ["a", "b"],
                    "rationale": "strong correlation", "priority": "high"}],
    "recommendations": ["check outliers in a"],
}


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_explore_under_test", ADAPTER)
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


def tool_ok(module, name, arguments):
    lines = rpc_lines(module, "tools/call", {"name": name, "arguments": arguments})
    replies = [line for line in lines if "id" in line]
    assert len(replies) == 1 and "result" in replies[0], "%s failed: %r" % (name, lines)
    return json.loads(replies[0]["result"]["content"][0]["text"])


def tool_error(module, name, arguments):
    lines = rpc_lines(module, "tools/call", {"name": name, "arguments": arguments})
    replies = [line for line in lines if "id" in line]
    assert len(replies) == 1 and "error" in replies[0], "%s succeeded: %r" % (name, lines)
    return replies[0]["error"]


class FakeEngine:
    def __init__(self, fail_explore=None):
        self.calls = []
        self.fail_explore = fail_explore

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/datasets":
            return {"path": "/cache/sales.parquet", "rows": 150, "cached": False}
        if method == "POST" and path == "/v1/explore":
            if self.fail_explore is not None:
                raise self.fail_explore
            return REPORT
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def paths(self):
        return [(method, path) for method, path, _ in self.calls]

    def body_of(self, path):
        for method, call_path, body in self.calls:
            if method == "POST" and call_path == path:
                return body
        raise AssertionError("no POST %s call: %r" % (path, self.calls))


class ExploreTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-explore-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def run_tool(self, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_explore", arguments)
        return result, engine

    def run_error(self, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, "dap_explore", arguments)
        return error, engine

    def test_explore_ingests_a_file_then_posts_it_as_a_dataset(self):
        result, engine = self.run_tool({"file": "/data/sales.csv"})
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertFalse(result["cached"])
        self.assertEqual(result["report"], REPORT)
        self.assertEqual(engine.paths(), [("POST", "/v1/datasets"),
                                          ("POST", "/v1/explore")])
        self.assertEqual(engine.body_of("/v1/explore"),
                         {"dataset": "/cache/sales.parquet"})
        # No temporary workflow is created or deleted: /v1/explore owns its own.
        self.assertFalse([path for method, path in engine.paths()
                          if path.startswith("/v1/workflows")])

    def test_explore_for_a_dataset_does_not_ingest(self):
        result, engine = self.run_tool({"dataset": "/cache/sales.parquet"})
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertEqual(engine.paths(), [("POST", "/v1/explore")])

    def test_explore_forwards_sql_and_limits(self):
        _result, engine = self.run_tool({
            "dataset": "/cache/sales.parquet",
            "sql": "SELECT region, total FROM src WHERE total > 0",
            "maxColumns": 10,
            "sampleRows": 500,
        })
        self.assertEqual(engine.body_of("/v1/explore"), {
            "dataset": "/cache/sales.parquet",
            "sql": "SELECT region, total FROM src WHERE total > 0",
            "maxColumns": 10,
            "sampleRows": 500,
        })

    def test_explore_omits_absent_optional_arguments(self):
        _result, engine = self.run_tool({"dataset": "/cache/sales.parquet"})
        self.assertEqual(engine.body_of("/v1/explore"),
                         {"dataset": "/cache/sales.parquet"})

    def test_missing_source_is_rejected_before_http(self):
        error, engine = self.run_error({})
        self.assertEqual(error["code"], -32602)
        self.assertEqual(engine.calls, [])

    def test_both_sources_are_rejected_before_http(self):
        error, engine = self.run_error({"file": "/a.csv", "dataset": "/b.parquet"})
        self.assertEqual(error["code"], -32602)
        self.assertEqual(engine.calls, [])

    def test_ddl_is_rejected_before_http_and_before_the_engine_starts(self):
        with mock.patch.object(self.module, "ensure_service") as ensure, \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_explore",
                               {"dataset": "/cache/x.parquet", "sql": "DROP TABLE src"})
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()
        ensure.assert_not_called()

    def test_invalid_limits_are_rejected_before_http(self):
        for arguments in ({"dataset": "/cache/x.parquet", "maxColumns": 0},
                          {"dataset": "/cache/x.parquet", "maxColumns": -1},
                          {"dataset": "/cache/x.parquet", "sampleRows": 0},
                          {"dataset": "/cache/x.parquet", "sampleRows": "many"}):
            with self.subTest(arguments=arguments):
                error, engine = self.run_error(arguments)
                self.assertEqual(error["code"], -32602)
                self.assertEqual(engine.calls, [])

    def test_engine_400_is_returned_with_its_envelope(self):
        failure = self.module.DapError(
            "both file and dataset", data={"status": 400, "code": "invalid_request",
                                           "body": {"error": "both file and dataset"}})
        error, engine = self.run_error({"dataset": "/cache/x.parquet"},
                                       fail_explore=failure)
        self.assertEqual(error["code"], -32000)
        self.assertEqual(error["data"]["status"], 400)
        self.assertEqual(error["data"]["code"], "invalid_request")

    def test_explore_emits_ingest_and_explore_stages(self):
        engine = FakeEngine()
        params = {"name": "dap_explore", "arguments": {"file": "/data/sales.csv"},
                  "_meta": {"progressToken": "tok-3"}}
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            lines = rpc_lines(self.module, "tools/call", params)
        notes = [line for line in lines if line.get("method") == "notifications/progress"]
        self.assertEqual([note["params"]["message"] for note in notes],
                         ["ingest", "explore"])
        self.assertEqual([note["params"]["progress"] for note in notes], [1, 2])
        self.assertEqual(lines[-1]["id"], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
