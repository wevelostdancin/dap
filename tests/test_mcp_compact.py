#!/usr/bin/env python3
"""Regression tests for compact table output, health probing and shutdown bounds.

    python3 -m unittest tests.test_mcp_compact -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import pathlib
import socket
import urllib.error
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_compact_under_test", ADAPTER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        os.environ.clear()
        os.environ.update(saved)


class Response(io.BytesIO):
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


LONG_TABLE = {
    "columns": [{"name": "region", "type": "String"}, {"name": "note", "type": "String"}],
    "rows": 1,
    "data": [["north", "x" * 100_000]],
}


class ProbeTest(unittest.TestCase):
    def setUp(self):
        self.module = load_adapter()

    def test_no_response_is_none(self):
        with mock.patch.object(self.module.urllib.request, "urlopen",
                               side_effect=urllib.error.URLError("refused")):
            self.assertIsNone(self.module.probe())

    def test_http_error_is_a_dap_error_with_status(self):
        error_response = urllib.error.HTTPError(
            "http://127.0.0.1:1/v1/health", 503, "Unavailable", {},
            io.BytesIO(b'{"error":"busy"}'))
        with mock.patch.object(self.module.urllib.request, "urlopen",
                               side_effect=error_response):
            with self.assertRaises(self.module.DapError) as caught:
                self.module.probe()
        self.assertEqual(caught.exception.data["status"], 503)

    def test_200_with_broken_json_is_not_treated_as_absent_engine(self):
        with mock.patch.object(self.module.urllib.request, "urlopen",
                               return_value=Response(b"<html>proxy</html>")):
            with self.assertRaises(self.module.DapError) as caught:
                self.module.probe()
        self.assertEqual(caught.exception.data["status"], 200)

    def test_valid_json_is_returned(self):
        with mock.patch.object(self.module.urllib.request, "urlopen",
                               return_value=Response(b'{"version":"1.2.0"}')):
            self.assertEqual(self.module.probe(), {"version": "1.2.0"})


class PortGuardTest(unittest.TestCase):
    def setUp(self):
        self.module = load_adapter()

    def test_port_busy_detects_a_listener(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
            listener.listen(1)
            with mock.patch.object(self.module, "HOST", "127.0.0.1"), \
                    mock.patch.object(self.module, "PORT", port):
                self.assertTrue(self.module._port_busy())
        with mock.patch.object(self.module, "HOST", "127.0.0.1"), \
                mock.patch.object(self.module, "PORT", port):
            self.assertFalse(self.module._port_busy())

    def test_ensure_service_never_starts_a_second_jar_on_a_busy_port(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
            listener.listen(1)
            with mock.patch.object(self.module, "HOST", "127.0.0.1"), \
                    mock.patch.object(self.module, "PORT", port), \
                    mock.patch.object(self.module, "probe", return_value=None), \
                    mock.patch.object(self.module.subprocess, "Popen") as popen:
                with self.assertRaises(self.module.DapError) as caught:
                    self.module.ensure_service()
        self.assertEqual(caught.exception.code, -32001)
        popen.assert_not_called()


class ShutdownTest(unittest.TestCase):
    def setUp(self):
        self.module = load_adapter()

    def test_shutdown_call_is_bounded_to_ten_seconds(self):
        with mock.patch.object(self.module, "health", side_effect=[True, False]), \
                mock.patch.object(self.module, "call", return_value={"ok": True}) as call, \
                mock.patch.object(self.module, "_reap_child"):
            self.assertEqual(self.module.tool_shutdown({}), {"ok": True})
        call.assert_called_once_with("POST", "/v1/shutdown", timeout=10)

    def test_shutdown_timeout_is_a_jsonrpc_service_error(self):
        with mock.patch.object(self.module, "health", side_effect=[True, False]), \
                mock.patch.object(self.module, "call", side_effect=TimeoutError("slow")), \
                mock.patch.object(self.module, "_reap_child"):
            with self.assertRaises(self.module.DapError) as caught:
                self.module.tool_shutdown({})
        self.assertEqual(caught.exception.code, -32001)
        self.assertEqual(caught.exception.data["timeout"], 10)


class CompactTableTest(unittest.TestCase):
    def setUp(self):
        self.module = load_adapter()
        self.table = {"columns": LONG_TABLE["columns"], "rows": 1,
                      "data": [["north", "x" * 300]]}

    def test_one_call_tables_are_capped_by_default(self):
        result = self.module._tool_table(self.table, {})
        self.assertEqual(len(result["data"][0][1]), 200)
        self.assertTrue(result["data"][0][1].endswith("\u2026"))
        self.assertNotIn("nextOffset", result)

    def test_table_format_and_columns_projection(self):
        result = self.module._tool_table(self.table, {
            "tableFormat": "csv", "columns": ["note"], "maxCellChars": 5,
        })
        self.assertEqual(result["format"], "csv")
        self.assertEqual(result["text"], "note\nxxxx\u2026\n")

    def test_zero_max_cell_chars_blanks_cells(self):
        result = self.module._tool_table(self.table, {"maxCellChars": 0})
        self.assertEqual(result["data"][0][1], "")

    def test_schemas_expose_compact_args_but_profile_has_no_limit(self):
        tools = {tool["name"]: tool for tool in self.module.TOOLS}
        for name in ("dap_analyze", "dap_report", "dap_sql", "dap_recipe_run",
                     "dap_workflow_build"):
            with self.subTest(tool=name):
                props = tools[name]["inputSchema"]["properties"]
                for key in ("tableFormat", "columns", "maxCellChars", "includeColumns"):
                    self.assertIn(key, props)
        self.assertNotIn("limit", tools["dap_profile"]["inputSchema"]["properties"])

    def test_analyzeResult_table_is_formatted(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with mock.patch.object(self.module, "ensure_service"), \
                    mock.patch.object(self.module, "call",
                                      side_effect=self._engine):
                result = self.module.tool_analyze({"dataset": "/cache/x.parquet"})
        self.assertEqual(len(result["table"]["data"][0][1]), 200)

    def _engine(self, method, path, body=None, timeout=None):
        if method == "POST" and path == "/v1/workflows":
            return {"id": "wf-1"}
        if method == "POST" and path.endswith("/nodes"):
            return {"node": "1:1"}
        if method == "POST" and path.endswith("/run"):
            return {"ok": True}
        if method == "GET" and path.endswith("/table?limit=20"):
            return LONG_TABLE
        if method == "GET" and path.endswith("/profile"):
            return {"rows": 1, "columns": []}
        if method == "DELETE" and path == "/v1/workflows/wf-1":
            return {"ok": True}
        raise AssertionError("unexpected call: %s %s" % (method, path))


if __name__ == "__main__":
    unittest.main(verbosity=2)
