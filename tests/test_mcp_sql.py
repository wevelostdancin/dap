#!/usr/bin/env python3
"""Unit tests for the dap_sql tool: SQL safety and the /v1 call sequence.

    python3 -m unittest tests.test_mcp_sql -v
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

TABLE = {
    "node": "1:2",
    "port": 0,
    "columns": [{"name": "n", "type": "Int64"}],
    "rows": 2,
    "data": [["1"], ["2"]],
}


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
    """Minimal /v1 double: add node, optional connect, run, table."""

    def __init__(self):
        self.calls = []

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/workflows/wf-1/nodes":
            return {"node": "1:2", "name": "SQL Query"}
        if method == "POST" and path == "/v1/workflows/wf-1/connect":
            return {"ok": True}
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            return {"ok": True, "durationMs": 1, "nodes": []}
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:2/table"):
            return TABLE
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def body_of(self, method, path):
        for call_method, call_path, body in self.calls:
            if call_method == method and call_path == path:
                return body
        raise AssertionError("no %s %s call: %r" % (method, path, self.calls))


class SqlToolTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-sql-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)
        # tools/call starts the engine before the handler; the HTTP call is patched
        patcher = mock.patch.object(self.module, "ensure_service")
        patcher.start()
        self.addCleanup(patcher.stop)

    def run_sql(self, arguments):
        engine = FakeEngine()
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_sql", arguments)
        return result, engine

    # -- rejection before the HTTP call --------------------------------------

    def test_rejects_ddl_before_the_http_call(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_sql",
                               {"workflow": "wf-1", "sql": "DROP TABLE src"})
        self.assertEqual(error["code"], -32602)
        self.assertIn("DROP", error["message"])
        self.assertEqual(error.get("data", {}).get("code"), "sql_not_allowed")
        call.assert_not_called()

    def test_rejects_multiple_statements(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_sql",
                               {"workflow": "wf-1", "sql": "SELECT 1; SELECT 2"})
        self.assertEqual(error["code"], -32602)
        self.assertIn("one statement", error["message"])
        call.assert_not_called()

    def test_rejects_non_select_statements(self):
        for sql in ("DELETE FROM src", "UPDATE src SET a = 1", "PRAGMA database_list"):
            with self.subTest(sql=sql):
                with mock.patch.object(self.module, "ensure_service"), \
                        mock.patch.object(self.module, "call") as call:
                    error = tool_error(self.module, "dap_sql",
                                       {"workflow": "wf-1", "sql": sql})
                self.assertEqual(error["code"], -32602)
                call.assert_not_called()

    def test_rejects_empty_sql(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_sql", {"workflow": "wf-1", "sql": "  "})
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()

    # -- execution -----------------------------------------------------------

    def test_adds_sql_node_connects_runs_and_returns_the_table(self):
        result, engine = self.run_sql({
            "workflow": "wf-1",
            "node": "1:1",
            "sql": "SELECT COUNT(*) AS n FROM \"1:1\"",
        })
        self.assertEqual(result["workflow"], "wf-1")
        self.assertEqual(result["node"], "1:2")
        self.assertEqual(result["table"], TABLE)
        added = engine.body_of("POST", "/v1/workflows/wf-1/nodes")
        self.assertEqual(added["factory"], "dap.sql_query")
        self.assertEqual(added["settings"]["sql"], 'SELECT COUNT(*) AS n FROM "1:1"')
        self.assertEqual(added["settings"]["inputs"], ["1:1"])
        connected = engine.body_of("POST", "/v1/workflows/wf-1/connect")
        self.assertEqual((connected["from"], connected["fromPort"],
                          connected["to"], connected["toPort"]), ("1:1", 0, "1:2", 0))
        self.assertEqual([(method, path) for method, path, _ in engine.calls],
                         [("POST", "/v1/workflows/wf-1/nodes"),
                          ("POST", "/v1/workflows/wf-1/connect"),
                          ("POST", "/v1/workflows/wf-1/run"),
                          ("GET", "/v1/workflows/wf-1/nodes/1:2/table?port=0&limit=20")])

    def test_without_node_does_not_connect(self):
        result, engine = self.run_sql({"workflow": "wf-1", "sql": "SELECT 42 AS n"})
        self.assertEqual(result["node"], "1:2")
        added = engine.body_of("POST", "/v1/workflows/wf-1/nodes")
        self.assertNotIn("inputs", added["settings"])
        self.assertNotIn(("POST", "/v1/workflows/wf-1/connect"),
                         [(method, path) for method, path, _ in engine.calls])

    def test_strips_the_trailing_semicolon(self):
        _result, engine = self.run_sql({
            "workflow": "wf-1", "node": "1:1",
            "sql": "WITH x AS (SELECT 1 AS n) SELECT * FROM x;",
        })
        added = engine.body_of("POST", "/v1/workflows/wf-1/nodes")
        self.assertEqual(added["settings"]["sql"], "WITH x AS (SELECT 1 AS n) SELECT * FROM x")

    def test_keywords_inside_string_literals_and_comments_are_allowed(self):
        result, engine = self.run_sql({
            "workflow": "wf-1",
            "sql": "SELECT 'DROP TABLE src' AS note -- create table is only a comment",
        })
        self.assertEqual(result["table"], TABLE)
        added = engine.body_of("POST", "/v1/workflows/wf-1/nodes")
        self.assertIn("DROP TABLE src", added["settings"]["sql"])

    def test_limit_is_passed_to_the_node_and_the_table(self):
        _result, engine = self.run_sql({
            "workflow": "wf-1", "node": "1:1", "sql": "SELECT 1 AS n", "limit": 5,
        })
        added = engine.body_of("POST", "/v1/workflows/wf-1/nodes")
        self.assertEqual(added["settings"]["limit"], 5)
        table_calls = [path for _method, path, _body in engine.calls if "/table" in path]
        self.assertEqual(table_calls, ["/v1/workflows/wf-1/nodes/1:2/table?port=0&limit=5"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
