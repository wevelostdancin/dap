#!/usr/bin/env python3
"""Unit tests for the MCP adapter's engine-restart notice and error-data envelope.

The tests never start Java: the adapter's health probe and HTTP call are replaced with
fakes, so a generation change is simulated exactly as an engine restart would look.

    python3 -m unittest tests.test_mcp_restart_notice -v
    python3 tests/test_mcp_restart_notice.py -v
"""
from __future__ import annotations

import importlib.util
import io
import json
import os
import pathlib
import tempfile
import unittest
import urllib.error
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"


def load_adapter(**environment):
    """Import a fresh adapter module with exactly *environment* (plus PATH/HOME)."""
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_restart", ADAPTER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        os.environ.clear()
        os.environ.update(saved)


class RestartNoticeTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-restart-")
        self.module = load_adapter(DAP_HOME=self._tmp.name)
        self.written = []
        self.module._write = self.written.append

    def tearDown(self):
        self._tmp.cleanup()

    def call_tool(self, name, arguments, probe, call):
        """Run one tools/call and return the single JSON-RPC payload it wrote."""
        # One tools/call probes health twice: ensure_service(), then the warm-up
        # wait. A one-shot side_effect would run dry, so the last body repeats.
        bodies = list(probe)

        def next_probe():
            return bodies[0] if len(bodies) == 1 else bodies.pop(0)

        with mock.patch.object(self.module, "probe", side_effect=next_probe), \
                mock.patch.object(self.module, "call", side_effect=call):
            self.module.handle({
                "jsonrpc": "2.0",
                "id": 1,
                "method": "tools/call",
                "params": {"name": name, "arguments": arguments},
            })
        self.assertEqual(len(self.written), 1, self.written)
        return self.written.pop()

    def test_generation_change_prefixes_the_next_error(self):
        ok = self.call_tool("dap_list_workflows", {},
                            probe=[{"generation": "gen-1"}], call=[{"workflows": []}])
        self.assertIn("result", ok)

        failure = self.call_tool(
            "dap_list_workflows", {},
            probe=[{"generation": "gen-2"}],
            call=[self.module.DapError(
                "unknown workflow: wf-1",
                data={"status": 404, "body": {"error": "unknown workflow: wf-1"}})])
        message = failure["error"]["message"]
        self.assertTrue(message.startswith("engine restarted (idle shutdown or crash); workflows lost: "), message)
        self.assertIn("unknown workflow: wf-1", message)

    def test_same_generation_keeps_the_error_clean(self):
        self.call_tool("dap_list_workflows", {},
                       probe=[{"generation": "gen-1"}], call=[{"workflows": []}])
        failure = self.call_tool(
            "dap_list_workflows", {},
            probe=[{"generation": "gen-1"}],
            call=[self.module.DapError("boom")])
        self.assertEqual(failure["error"]["message"], "boom")

    def test_error_data_surfaces_code_and_hint_at_top_level(self):
        payload = {"error": "node has no table output",
                   "code": "not_materialized", "hint": "run the workflow first"}
        raw = json.dumps(payload).encode("utf-8")
        http_error = urllib.error.HTTPError(
            self.module.BASE + "/v1/workflows/wf-1/nodes/1:1/table", 409, "Conflict", {},
            io.BytesIO(raw))
        with mock.patch.object(self.module.urllib.request, "urlopen", side_effect=http_error):
            with self.assertRaises(self.module.DapError) as caught:
                self.module.call("GET", "/v1/workflows/wf-1/nodes/1:1/table")

        data = caught.exception.data
        self.assertEqual(data["status"], 409)
        self.assertEqual(data["code"], "not_materialized")
        self.assertEqual(data["hint"], "run the workflow first")
        self.assertEqual(data["body"], payload, "the raw body stays for compatibility")
        self.assertEqual(str(caught.exception), "node has no table output")


if __name__ == "__main__":
    unittest.main(verbosity=2)
