#!/usr/bin/env python3
"""Unit tests for CSV/Excel read options in one-call MCP tools (mocked /v1)."""
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


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_ingest_params", ADAPTER)
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
    def __init__(self, ingest=None):
        self.calls = []
        self.ingest = ingest or {"path": "/cache/sales.parquet", "rows": 3, "cached": False}

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/datasets":
            return dict(self.ingest)
        if method == "POST" and path == "/v1/workflows":
            return {"id": "wf-1"}
        if method == "POST" and path == "/v1/workflows/wf-1/nodes":
            return {"node": "1:1"}
        if method == "POST" and path == "/v1/workflows/wf-1/connect":
            return {"ok": True}
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            return {"ok": True}
        if method == "GET" and "/nodes/1:1/table" in path:
            return TABLE
        if method == "GET" and path.endswith("/profile"):
            return PROFILE
        if method == "DELETE" and path == "/v1/workflows/wf-1":
            return {"ok": True}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def body_of(self, method, path):
        for call_method, call_path, body in self.calls:
            if call_method == method and call_path == path:
                return body
        raise AssertionError("no %s %s call: %r" % (method, path, self.calls))


class IngestParamsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-ingest-params-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def tool_schemas(self):
        reply = rpc(self.module, "tools/list")
        return {tool["name"]: tool["inputSchema"]["properties"]
                for tool in reply["result"]["tools"]}

    def run_tool(self, name, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, name, arguments)
        return result, engine

    def test_tools_list_exposes_csv_excel_properties(self):
        schemas = self.tool_schemas()
        ingest = schemas["dap_ingest"]
        for key in ("encoding", "allVarchar", "decimalComma", "sheet"):
            self.assertIn(key, ingest)
        self.assertIn("автоопределение", ingest["delimiter"]["description"])
        for name in ("dap_analyze", "dap_profile", "dap_report", "dap_explore",
                     "dap_estimate"):
            props = schemas[name]
            for key in ("delimiter", "encoding", "sheet"):
                self.assertIn(key, props, "%s is missing %s" % (name, key))
        # dap_report's format is the report format, not the input file format.
        self.assertIn("отчёта", schemas["dap_report"]["format"]["description"])

    def test_csv_body_forwards_new_options(self):
        body = self.module._csv_body(
            {"encoding": "cp1251", "allVarchar": True,
             "decimalComma": True, "sheet": "S2", "header": None},
            {"file": "/data/x.csv"})
        self.assertEqual(body, {"file": "/data/x.csv", "encoding": "cp1251",
                                "allVarchar": True, "decimalComma": True,
                                "sheet": "S2"})

    def test_dap_ingest_forwards_new_options(self):
        _result, engine = self.run_tool("dap_ingest", {
            "file": "/data/x.csv", "encoding": "cp1251", "allVarchar": True,
            "decimalComma": True, "sheet": 1})
        self.assertEqual(engine.body_of("POST", "/v1/datasets"), {
            "file": "/data/x.csv", "encoding": "cp1251", "allVarchar": True,
            "decimalComma": True, "sheet": 1})

    def test_analyze_sends_csv_options_to_ingest(self):
        result, engine = self.run_tool("dap_analyze", {
            "file": "/data/x.csv", "delimiter": ";", "encoding": "cp1251",
            "sheet": "S2"})
        body = engine.body_of("POST", "/v1/datasets")
        self.assertEqual(body["file"], "/data/x.csv")
        self.assertEqual(body["delimiter"], ";")
        self.assertEqual(body["encoding"], "cp1251")
        self.assertEqual(body["sheet"], "S2")
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertFalse(result["cached"])

    def test_analyze_returns_ingest_sheet_metadata(self):
        result, _engine = self.run_tool("dap_analyze", {"file": "/data/x.xlsx"},
            ingest={"path": "/cache/sales.parquet", "rows": 3, "cached": True,
                    "sheet": "S2", "sheets": ["S1", "S2"], "encoding": "cp1251"})
        self.assertEqual(result["sheet"], "S2")
        self.assertEqual(result["sheets"], ["S1", "S2"])
        self.assertEqual(result["encoding"], "cp1251")
        self.assertTrue(result["cached"])

    def test_one_call_tools_skip_absent_metadata(self):
        result, _engine = self.run_tool("dap_analyze", {"file": "/data/x.csv"})
        for key in ("sheet", "sheets", "encoding"):
            self.assertNotIn(key, result)

    def test_profile_and_report_return_ingest_metadata(self):
        ingest = {"path": "/cache/sales.parquet", "rows": 3, "cached": False,
                  "sheet": 0, "sheets": ["S1"], "encoding": "utf-8"}
        with self.subTest(tool="dap_profile"):
            result, _engine = self.run_tool(
                "dap_profile", {"file": "/data/x.xlsx"}, ingest=ingest)
            self.assertEqual(result["sheet"], 0)
            self.assertEqual(result["sheets"], ["S1"])
            self.assertEqual(result["encoding"], "utf-8")
        with self.subTest(tool="dap_report"):
            result, _engine = self.run_tool(
                "dap_report", {"file": "/data/x.xlsx", "report": "out.md"}, ingest=ingest)
            self.assertEqual(result["sheet"], 0)
            self.assertEqual(result["sheets"], ["S1"])
            self.assertEqual(result["encoding"], "utf-8")

    def test_restart_note_is_prefixed_and_flagged(self):
        self.module._note_generation("gen-a")
        self.module._note_generation("gen-b")
        failure = self.module.DapError("boom", data={"status": 404})
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=failure):
            error = tool_error(self.module, "dap_list_workflows", {})
        self.assertTrue(error["message"].startswith("engine restarted"), error)
        self.assertIn("boom", error["message"])
        self.assertTrue(error["data"]["restarted"])
        self.assertEqual(error["data"]["status"], 404)


if __name__ == "__main__":
    unittest.main(verbosity=2)
