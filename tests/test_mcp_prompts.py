#!/usr/bin/env python3
"""Unit tests for MCP prompts: analyze_csv, quality_check and profile_report.

    python3 -m unittest tests.test_mcp_prompts -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"
PROMPTS = {"analyze_csv", "quality_check", "profile_report"}


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


class PromptsTest(unittest.TestCase):
    def setUp(self):
        self.module = load_adapter(DAP_HOME=str(ROOT))

    def get(self, name, arguments):
        reply = rpc(self.module, "prompts/get", {"name": name, "arguments": arguments})
        self.assertNotIn("error", reply, "prompts/get failed: %r" % (reply,))
        return reply["result"]

    def message_text(self, result):
        messages = result.get("messages")
        self.assertEqual(len(messages), 1, "expected one message: %r" % (result,))
        message = messages[0]
        self.assertEqual(message["role"], "user")
        self.assertEqual(message["content"]["type"], "text")
        text = message["content"]["text"]
        self.assertTrue(text.strip(), "prompt message must not be empty")
        return text

    def test_initialize_advertises_prompts(self):
        reply = rpc(self.module, "initialize", {"protocolVersion": "2024-11-05"})
        capabilities = reply["result"]["capabilities"]
        self.assertEqual(capabilities.get("prompts"), {"listChanged": False})
        self.assertIn("resources", capabilities, "resources capability must survive")

    def test_prompts_list_returns_three_prompts(self):
        reply = rpc(self.module, "prompts/list", {})
        prompts = reply["result"]["prompts"]
        names = {prompt["name"] for prompt in prompts}
        self.assertEqual(names, PROMPTS)
        by_name = {prompt["name"]: prompt for prompt in prompts}
        for prompt in prompts:
            self.assertTrue(prompt.get("description"))
            self.assertIsInstance(prompt.get("arguments"), list)
        analyze = {arg["name"]: arg for arg in by_name["analyze_csv"]["arguments"]}
        self.assertTrue(analyze["path"]["required"])
        self.assertFalse(analyze["question"]["required"])
        quality = {arg["name"]: arg for arg in by_name["quality_check"]["arguments"]}
        self.assertTrue(quality["path"]["required"])
        profile = {arg["name"]: arg for arg in by_name["profile_report"]["arguments"]}
        self.assertTrue(profile["path"]["required"])
        self.assertFalse(profile["output"]["required"])

    def test_analyze_csv_interpolates_path_and_question(self):
        result = self.get("analyze_csv", {
            "path": "/data/sales.csv",
            "question": "which region grew fastest?",
        })
        text = self.message_text(result)
        self.assertIn("/data/sales.csv", text)
        self.assertIn("which region grew fastest?", text)
        for step in ("dap_analyze", "dap_explore", "dap_profile", "dap_estimate"):
            self.assertIn(step, text)
        for dead_step in ("dap_ingest", "dap_profile_table"):
            self.assertNotIn(dead_step, text, "prompt still suggests a 4-5 step pipeline")
        self.assertIn("1.", text, "pipeline steps must be numbered")
        self.assertNotIn("5.", text, "prompt must not end in a fifth manual step")

    def test_quality_check_interpolates_path(self):
        text = self.message_text(self.get("quality_check", {"path": "/data/clean.csv"}))
        self.assertIn("/data/clean.csv", text)
        for step in ("dap_explore", "dap_profile"):
            self.assertIn(step, text)
        for dead_step in ("dap_ingest", "dap_profile_table", "dap_workflow_create"):
            self.assertNotIn(dead_step, text)

    def test_profile_report_interpolates_path_and_output(self):
        text = self.message_text(self.get("profile_report", {
            "path": "/data/iris.csv",
            "output": "/tmp/iris-report.md",
        }))
        self.assertIn("/data/iris.csv", text)
        self.assertIn("/tmp/iris-report.md", text)
        self.assertIn("dap_report", text)
        self.assertIn("one tool call", text)
        self.assertNotIn("dap_ingest", text)
        self.assertNotIn("3.", text, "report prompt must not promise an impossible step 3")

    def test_profile_report_has_default_output(self):
        text = self.message_text(self.get("profile_report", {"path": "/data/iris.csv"}))
        self.assertIn("profile.md", text)

    def test_missing_required_argument_is_invalid_params(self):
        reply = rpc(self.module, "prompts/get", {"name": "analyze_csv", "arguments": {}})
        self.assertEqual(reply["error"]["code"], -32602)
        self.assertIn("path", reply["error"]["message"])
        self.assertEqual(reply["error"].get("data", {}).get("missing"), ["path"])

    def test_empty_required_argument_is_invalid_params(self):
        reply = rpc(self.module, "prompts/get", {
            "name": "quality_check", "arguments": {"path": "  "},
        })
        self.assertEqual(reply["error"]["code"], -32602)

    def test_unknown_prompt_is_invalid_params(self):
        reply = rpc(self.module, "prompts/get", {"name": "nope", "arguments": {}})
        self.assertEqual(reply["error"]["code"], -32602)
        self.assertIn("nope", reply["error"]["message"])

    def test_non_object_arguments_are_invalid_params(self):
        reply = rpc(self.module, "prompts/get", {"name": "analyze_csv", "arguments": "path"})
        self.assertEqual(reply["error"]["code"], -32602)


if __name__ == "__main__":
    unittest.main(verbosity=2)
