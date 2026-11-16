#!/usr/bin/env python3
"""Unit tests for the recipe library tools: list, save and run.

The engine is never started: HTTP calls are patched, recipes are files under a
temporary DAP_HOME.

    python3 -m unittest tests.test_mcp_recipes -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import tempfile
import threading
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"

TABLE = {
    "node": "1:2",
    "port": 0,
    "columns": [{"name": "n", "type": "Int64"}],
    "rows": 1,
    "data": [["7"]],
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
    content = reply["result"]["content"]
    return json.loads(content[0]["text"])


def tool_error(module, name, arguments):
    reply = rpc(module, "tools/call", {"name": name, "arguments": arguments})
    assert "error" in reply, "%s unexpectedly succeeded: %r" % (name, reply)
    return reply["error"]


def recipe_document(name="alpha"):
    return {
        "format": "dap-native-workflow/1",
        "id": "wf-saved",
        "name": name,
        "nodes": [
            {"id": "src", "factory": "dap.csv_reader", "name": "CSV Reader",
             "settings": {"file": "/data/input.csv"}},
            {"id": "q", "factory": "dap.sql_query", "name": "SQL Query",
             "settings": {"sql": "SELECT * FROM src", "inputs": ["src"]}},
        ],
        "connections": [{"from": "src", "fromPort": 0, "to": "q", "toPort": 0}],
        "recipe": {
            "name": name,
            "description": "Saved pipeline",
            "tags": ["demo"],
            "dataset": {"node": "src", "setting": "file"},
            "params": {"query": {"node": "q", "setting": "sql"}},
        },
    }


class FakeEngine:
    """Minimal /v1 double for one recipe run; records calls and the loaded workflow."""

    def __init__(self, workflow="wf-9", nodes=("1:1", "1:2"), table=TABLE, loaded_documents=None):
        self.workflow = workflow
        self.nodes = list(nodes)
        self.table = table
        self.calls = []
        self.loaded_documents = loaded_documents if loaded_documents is not None else []
        self._lock = threading.Lock()

    def __call__(self, method, path, body=None, timeout=None):
        with self._lock:
            self.calls.append((method, path, body))
        if path == "/v1/workflows/load":
            directory = pathlib.Path(body["dir"])
            document = json.loads((directory / "workflow.json").read_text(encoding="utf-8"))
            with self._lock:
                self.loaded_documents.append(document)
            return {"id": self.workflow, "name": body.get("name"),
                    "session": "default", "nodes": len(self.nodes)}
        if path == "/v1/workflows/%s/nodes" % self.workflow:
            return {"nodes": [{"node": node, "name": "node", "state": "IDLE"}
                              for node in self.nodes]}
        if path == "/v1/workflows/%s/run" % self.workflow:
            return {"ok": True, "durationMs": 1, "nodes": []}
        if path.startswith("/v1/workflows/%s/nodes/" % self.workflow) and "/table" in path:
            return self.table
        if method == "DELETE" and path == "/v1/workflows/%s" % self.workflow:
            return {"ok": True}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def paths(self):
        return [(method, path) for method, path, _body in self.calls]


class RecipeToolsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-recipes-")
        self.addCleanup(self._tmp.cleanup)
        self.home = pathlib.Path(self._tmp.name)
        self.module = load_adapter(DAP_HOME=str(self.home))
        # tools/call starts the engine before the handler; these tests are local
        patcher = mock.patch.object(self.module, "ensure_service")
        patcher.start()
        self.addCleanup(patcher.stop)

    def save(self, name, workflow=None, **kwargs):
        arguments = {"name": name, "workflow": workflow or recipe_document(name)}
        arguments.update(kwargs)
        return tool_ok(self.module, "dap_recipe_save", arguments)

    def stored(self, name):
        return json.loads((self.home / "recipes" / (name + ".json")).read_text(encoding="utf-8"))

    # -- tools/list ----------------------------------------------------------

    def test_tools_list_declares_recipe_tools_and_sql(self):
        reply = rpc(self.module, "tools/list", {})
        names = {tool["name"] for tool in reply["result"]["tools"]}
        for name in ("dap_recipe_list", "dap_recipe_save", "dap_recipe_run", "dap_sql"):
            self.assertIn(name, names)
        by_name = {tool["name"]: tool for tool in reply["result"]["tools"]}
        self.assertEqual(by_name["dap_recipe_save"]["inputSchema"]["required"],
                         ["name", "workflow"])
        self.assertEqual(by_name["dap_recipe_run"]["inputSchema"]["required"], ["name"])
        sql_schema = by_name["dap_sql"]["inputSchema"]
        # sql and queries are mutually exclusive, so only workflow is required statically.
        self.assertEqual(sql_schema["required"], ["workflow"])
        self.assertIn("sql", sql_schema["properties"])
        self.assertIn("queries", sql_schema["properties"])

    # -- save ----------------------------------------------------------------

    def test_save_writes_recipe_file(self):
        document = recipe_document("alpha")
        result = self.save("alpha", document, description="First", tags=["demo", "csv"])
        path = self.home / "recipes" / "alpha.json"
        self.assertTrue(path.is_file())
        self.assertEqual(result["name"], "alpha")
        self.assertEqual(result["path"], str(path))
        stored = self.stored("alpha")
        self.assertEqual(stored["format"], "dap-native-workflow/1")
        self.assertEqual(stored["recipe"]["name"], "alpha")
        self.assertEqual(stored["recipe"]["description"], "First")
        self.assertEqual(stored["recipe"]["tags"], ["demo", "csv"])
        self.assertEqual(stored["recipe"]["params"], {"query": {"node": "q", "setting": "sql"}})
        self.assertEqual([node["id"] for node in stored["nodes"]], ["src", "q"])
        self.assertNotIn("First", json.dumps(document), "save must not mutate the caller's workflow")

    def test_save_rejects_path_separators(self):
        for name in ("../evil", "a/b", "a\\b", ".", "..", ""):
            with self.subTest(name=name):
                error = tool_error(self.module, "dap_recipe_save",
                                   {"name": name, "workflow": recipe_document("alpha")})
                self.assertEqual(error["code"], -32602)
        self.assertFalse((self.home / "evil.json").exists())
        self.assertFalse((self.home.parent / "evil.json").exists())

    def test_save_rejects_invalid_workflow(self):
        error = tool_error(self.module, "dap_recipe_save", {
            "name": "bad", "workflow": {"nodes": [], "connections": []},
        })
        self.assertEqual(error["code"], -32602)
        self.assertIn("format", error["message"])
        self.assertFalse((self.home / "recipes" / "bad.json").exists())

    def test_save_accepts_a_workflow_id(self):
        document = recipe_document("alpha")

        def fake(method, path, body=None, timeout=None):
            if method == "POST" and path == "/v1/workflows/wf-1/save":
                directory = pathlib.Path(body["dir"])
                (directory / "workflow.json").write_text(json.dumps(document), encoding="utf-8")
                return {"ok": True, "dir": body["dir"]}
            raise AssertionError("unexpected HTTP call: %s %s" % (method, path))

        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=fake):
            result = tool_ok(self.module, "dap_recipe_save",
                             {"name": "from-id", "workflow": "wf-1"})
        self.assertEqual(result["name"], "from-id")
        self.assertEqual(self.stored("from-id")["recipe"]["name"], "from-id")

    # -- list ----------------------------------------------------------------

    def test_list_returns_saved_recipes(self):
        self.save("alpha", description="First")
        self.save("beta", description="Second")
        result = tool_ok(self.module, "dap_recipe_list", {})
        names = [entry["name"] for entry in result["recipes"]]
        self.assertEqual(names, ["alpha", "beta"])
        self.assertEqual(result["recipes"][0]["description"], "First")
        self.assertEqual(result["recipes"][0]["params"], ["query"])

    def test_list_is_empty_without_library(self):
        result = tool_ok(self.module, "dap_recipe_list", {})
        self.assertEqual(result["recipes"], [])

    # -- run -----------------------------------------------------------------

    def test_run_applies_dataset_and_params_without_touching_the_recipe(self):
        self.save("alpha")
        before = (self.home / "recipes" / "alpha.json").read_text(encoding="utf-8")
        engine = FakeEngine()
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_recipe_run", {
                "name": "alpha",
                "dataset": "/cache/input.parquet",
                "params": {"query": "SELECT COUNT(*) AS n FROM src"},
            })
        self.assertEqual(result["recipe"], "alpha")
        self.assertEqual(result["workflow"], "wf-9")
        self.assertEqual(result["node"], "1:2", "the last recipe node is the output")
        self.assertEqual(result["table"], TABLE)
        self.assertEqual(result["applied"]["dataset"],
                         {"node": "src", "setting": "file", "value": "/cache/input.parquet"})
        loaded = engine.loaded_documents[0]
        settings = {node["id"]: node["settings"] for node in loaded["nodes"]}
        self.assertEqual(settings["src"]["file"], "/cache/input.parquet")
        self.assertEqual(settings["q"]["sql"], "SELECT COUNT(*) AS n FROM src")
        self.assertEqual((self.home / "recipes" / "alpha.json").read_text(encoding="utf-8"),
                         before, "the stored recipe must not be edited by a run")
        self.assertEqual(engine.paths()[-1], ("DELETE", "/v1/workflows/wf-9"))

    def test_run_without_arguments_uses_stored_settings(self):
        self.save("alpha")
        engine = FakeEngine()
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            tool_ok(self.module, "dap_recipe_run", {"name": "alpha"})
        settings = {node["id"]: node["settings"] for node in engine.loaded_documents[0]["nodes"]}
        self.assertEqual(settings["src"]["file"], "/data/input.csv")
        self.assertEqual(settings["q"]["sql"], "SELECT * FROM src")

    def test_run_binds_dataset_to_the_first_reader_without_a_binding(self):
        document = recipe_document("plain")
        del document["recipe"]["dataset"]
        self.save("plain", document)
        engine = FakeEngine()
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            tool_ok(self.module, "dap_recipe_run",
                    {"name": "plain", "dataset": "/cache/plain.parquet"})
        settings = {node["id"]: node["settings"] for node in engine.loaded_documents[0]["nodes"]}
        self.assertEqual(settings["src"]["file"], "/cache/plain.parquet")

    def test_run_unknown_recipe_is_invalid_params(self):
        error = tool_error(self.module, "dap_recipe_run", {"name": "missing"})
        self.assertEqual(error["code"], -32602)
        self.assertIn("missing", error["message"])

    def test_run_unknown_param_is_invalid_params_and_does_not_run(self):
        self.save("alpha")
        engine = FakeEngine()
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, "dap_recipe_run",
                               {"name": "alpha", "params": {"nope": 1}})
        self.assertEqual(error["code"], -32602)
        self.assertIn("query", error["message"], "the error should list declared parameters")
        self.assertEqual(engine.calls, [], "an unknown parameter must fail before the HTTP call")

    def test_run_cleans_up_when_the_engine_fails(self):
        self.save("alpha")

        def failing(method, path, body=None, timeout=None):
            if path == "/v1/workflows/load":
                return {"id": "wf-9", "name": "alpha", "session": "default", "nodes": 2}
            if path == "/v1/workflows/wf-9/nodes":
                return {"nodes": [{"node": "1:1"}, {"node": "1:2"}]}
            if path == "/v1/workflows/wf-9/run":
                raise self.module.DapError("run failed", data={"status": 500})
            if method == "DELETE" and path == "/v1/workflows/wf-9":
                return {"ok": True}
            raise AssertionError("unexpected HTTP call: %s %s" % (method, path))

        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=failing):
            error = tool_error(self.module, "dap_recipe_run", {"name": "alpha"})
        self.assertEqual(error["code"], -32000)
        self.assertIn("run failed", error["message"])

    def test_concurrent_runs_do_not_share_state(self):
        self.save("alpha")
        documents = []
        engine = FakeEngine(loaded_documents=documents)
        results = []
        failures = []

        def run(dataset):
            try:
                results.append(self.module.tool_recipe_run(
                    {"name": "alpha", "dataset": dataset}))
            except Exception as failure:  # noqa: BLE001 - collected for the assertion
                failures.append(failure)

        with mock.patch.object(self.module, "call", side_effect=engine):
            threads = [threading.Thread(target=run, args=("/cache/%d.parquet" % index,))
                       for index in range(2)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(timeout=10)
        self.assertEqual(failures, [])
        self.assertEqual(len(results), 2)
        files = sorted(
            {node["settings"]["file"] for document in documents
             for node in document["nodes"] if node["id"] == "src"}
        )
        self.assertEqual(files, ["/cache/0.parquet", "/cache/1.parquet"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
