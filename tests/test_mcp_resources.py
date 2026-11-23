#!/usr/bin/env python3
"""Unit tests for MCP resources: catalog and the recipe library (stdlib unittest).

The engine is never started: resources/read on dap://catalog patches the adapter's
HTTP call, recipes are plain files under a temporary DAP_HOME.

    python3 -m unittest tests.test_mcp_resources -v
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


def load_adapter(**environment):
    """Import a fresh adapter module with exactly *environment* (plus PATH/HOME)."""
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
    """Send one JSON-RPC request through handle() and return the parsed reply."""
    message = {"jsonrpc": "2.0", "id": msg_id, "method": method}
    if params is not None:
        message["params"] = params
    captured = io.StringIO()
    with contextlib.redirect_stdout(captured):
        module.handle(message)
    replies = [json.loads(line) for line in captured.getvalue().splitlines() if line.strip()]
    assert len(replies) == 1, "expected one reply, got %r" % (replies,)
    return replies[0]


def recipe_document(name, description="Saved pipeline", tags=("demo",)):
    return {
        "format": "dap-native-workflow/1",
        "name": name,
        "nodes": [
            {"id": "src", "factory": "dap.csv_reader", "name": "CSV Reader",
             "settings": {"file": "/data/input.csv"}},
            {"id": "q", "factory": "dap.sql_query", "name": "SQL Query",
             "settings": {"sql": "SELECT * FROM src", "inputs": ["src"]}},
        ],
        "connections": [{"from": "src", "fromPort": 0, "to": "q", "toPort": 0}],
        "recipe": {"name": name, "description": description, "tags": list(tags)},
    }


class ResourcesTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-resources-")
        self.addCleanup(self._tmp.cleanup)
        self.home = pathlib.Path(self._tmp.name)
        self.module = load_adapter(DAP_HOME=str(self.home))

    def write_recipe(self, name, **kwargs):
        directory = self.home / "recipes"
        directory.mkdir(exist_ok=True)
        path = directory / (name + ".json")
        path.write_text(json.dumps(recipe_document(name, **kwargs)), encoding="utf-8")
        return path

    def read_resource(self, uri):
        return rpc(self.module, "resources/read", {"uri": uri})

    def contents(self, reply):
        result = reply.get("result")
        self.assertIsNotNone(result, "resources/read failed: %r" % (reply,))
        contents = result.get("contents")
        self.assertEqual(len(contents), 1, "expected one content entry: %r" % (result,))
        return contents[0]

    # -- initialize and list -------------------------------------------------

    def test_initialize_advertises_resources(self):
        reply = rpc(self.module, "initialize", {"protocolVersion": "2024-11-05"})
        capabilities = reply["result"]["capabilities"]
        self.assertEqual(capabilities.get("resources"), {"listChanged": False})
        self.assertIn("tools", capabilities, "tools capability must survive")

    def test_resources_list_returns_catalog_and_recipes(self):
        reply = rpc(self.module, "resources/list", {})
        resources = reply["result"]["resources"]
        by_uri = {item["uri"]: item for item in resources}
        self.assertIn("dap://catalog", by_uri)
        self.assertIn("dap://recipes", by_uri)
        for item in (by_uri["dap://catalog"], by_uri["dap://recipes"]):
            self.assertTrue(item.get("name"), "resource needs a name: %r" % (item,))
            self.assertEqual(item.get("mimeType"), "application/json")
            self.assertTrue(item.get("description"))

    # -- catalog -------------------------------------------------------------

    def test_read_catalog_returns_node_json(self):
        payload = {
            "count": 2,
            "nodes": [
                {"id": "dap.csv_reader", "name": "CSV Reader", "category": "/IO/Read",
                 "type": "Source", "tier": "core"},
                {"id": "dap.sql_query", "name": "SQL Query", "category": "/Manipulation/Row",
                 "type": "Manipulator", "tier": "core"},
            ],
        }
        with mock.patch.object(self.module, "ensure_service") as ensure, \
                mock.patch.object(self.module, "call", return_value=payload) as call:
            content = self.contents(self.read_resource("dap://catalog"))
        ensure.assert_called_once()
        self.assertEqual(call.call_args.args[0], "GET")
        self.assertTrue(call.call_args.args[1].startswith("/v1/nodes"))
        self.assertEqual(content["uri"], "dap://catalog")
        self.assertEqual(content["mimeType"], "application/json")
        catalog = json.loads(content["text"])
        self.assertEqual([node["id"] for node in catalog["nodes"]],
                         ["dap.csv_reader", "dap.sql_query"])
        self.assertEqual(catalog["nodes"][0]["category"], "/IO/Read")
        self.assertEqual(catalog["count"], 2)

    # -- recipes -------------------------------------------------------------

    def test_read_recipes_lists_saved_names(self):
        self.write_recipe("alpha", description="First")
        self.write_recipe("beta", description="Second")
        content = self.contents(self.read_resource("dap://recipes"))
        self.assertEqual(content["uri"], "dap://recipes")
        payload = json.loads(content["text"])
        self.assertEqual([item["name"] for item in payload["recipes"]], ["alpha", "beta"])
        self.assertEqual(payload["recipes"][0]["description"], "First")

    def test_read_recipe_returns_workflow_with_recipe_block(self):
        self.write_recipe("alpha", description="First pipeline")
        content = self.contents(self.read_resource("dap://recipes/alpha"))
        self.assertEqual(content["uri"], "dap://recipes/alpha")
        document = json.loads(content["text"])
        self.assertEqual(document["format"], "dap-native-workflow/1")
        self.assertEqual(document["recipe"]["name"], "alpha")
        self.assertEqual(document["recipe"]["description"], "First pipeline")
        self.assertEqual([node["id"] for node in document["nodes"]], ["src", "q"])
        self.assertEqual(document["connections"][0]["to"], "q")

    def test_unknown_uri_is_invalid_params(self):
        reply = self.read_resource("dap://unknown")
        self.assertIn("error", reply, "unknown URI must be a JSON-RPC error: %r" % (reply,))
        self.assertEqual(reply["error"]["code"], -32602)
        self.assertIn("dap://unknown", reply["error"]["message"])
        self.assertEqual(reply["error"].get("data", {}).get("uri"), "dap://unknown")

    def test_unknown_recipe_is_invalid_params(self):
        reply = self.read_resource("dap://recipes/missing")
        self.assertIn("error", reply, "unknown recipe must be a JSON-RPC error: %r" % (reply,))
        self.assertEqual(reply["error"]["code"], -32602)
        self.assertIn("missing", reply["error"]["message"])

    def test_missing_uri_is_invalid_params(self):
        reply = rpc(self.module, "resources/read", {})
        self.assertEqual(reply["error"]["code"], -32602)


RECIPE_PROMPTS = (
    "cohort_analysis",
    "ab_test",
    "time_series",
    "segmentation",
    "regression",
    "funnel",
    "exec_report",
)


class PromptsTest(unittest.TestCase):
    def setUp(self):
        self.module = load_adapter(DAP_HOME=str(ROOT))

    def get_prompt(self, name, arguments):
        reply = rpc(self.module, "prompts/get", {"name": name, "arguments": arguments})
        self.assertNotIn("error", reply, "prompts/get failed: %r" % (reply,))
        return reply["result"]

    def message_text(self, result):
        messages = result.get("messages")
        self.assertEqual(len(messages), 1, "expected one message: %r" % (result,))
        message = messages[0]
        self.assertEqual(message["role"], "user")
        self.assertEqual(message["content"]["type"], "text")
        return message["content"]["text"]

    def test_prompts_list_has_ten_prompts(self):
        reply = rpc(self.module, "prompts/list", {})
        prompts = reply["result"]["prompts"]
        by_name = {prompt["name"]: prompt for prompt in prompts}
        self.assertEqual(len(prompts), 10)
        self.assertEqual(set(by_name), {
            "analyze_csv", "quality_check", "profile_report", *RECIPE_PROMPTS,
        })
        for prompt in prompts:
            self.assertTrue(prompt.get("description"))
            self.assertIsInstance(prompt.get("arguments"), list)
        for name in RECIPE_PROMPTS:
            args = {arg["name"]: arg for arg in by_name[name]["arguments"]}
            self.assertEqual(set(args), {"dataset", "report"})
            self.assertTrue(args["dataset"]["required"])
            self.assertFalse(args["report"]["required"])
            self.assertIn(name + ".md", args["report"]["description"])

    def test_recipe_prompt_reads_recipe_and_runs_it(self):
        for name in RECIPE_PROMPTS:
            with self.subTest(prompt=name):
                text = self.message_text(self.get_prompt(name, {"dataset": "demo.parquet"}))
                self.assertIn(name, text)
                self.assertIn("dap://recipes/" + name, text)
                self.assertIn("dap_recipe_run", text)
                self.assertIn('"name": "%s"' % name, text)
                self.assertIn('"dataset": "demo.parquet"', text)
                self.assertIn('"params": {"report": "%s.md"}' % name, text)
                for tool in ("dap_profile", "dap_explore", "dap_add_node", "dap_sql"):
                    self.assertIn(tool, text)
                self.assertIn("4.", text, "must end with the result step")

    def test_recipe_prompt_uses_explicit_report_path(self):
        text = self.message_text(self.get_prompt("ab_test", {
            "dataset": "ab.parquet", "report": "/tmp/ab.md",
        }))
        self.assertIn('"report": "/tmp/ab.md"', text)
        self.assertIn("/tmp/ab.md", text)
        self.assertNotIn("ab_test.md", text)

    def test_recipe_prompt_missing_dataset_is_invalid_params(self):
        for name in RECIPE_PROMPTS:
            with self.subTest(prompt=name):
                reply = rpc(self.module, "prompts/get", {"name": name, "arguments": {}})
                self.assertEqual(reply["error"]["code"], -32602)
                self.assertEqual(reply["error"].get("data", {}).get("missing"), ["dataset"])

    def test_recipe_prompt_empty_dataset_is_invalid_params(self):
        reply = rpc(self.module, "prompts/get", {
            "name": "funnel", "arguments": {"dataset": "  "},
        })
        self.assertEqual(reply["error"]["code"], -32602)


if __name__ == "__main__":
    unittest.main(verbosity=2)
