#!/usr/bin/env python3
"""Unit tests for dap_dataset, dap://datasets resources and catalog-name resolution.

    python3 -m unittest tests.test_mcp_dataset -v
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

ENTRY = {
    "name": "sales",
    "description": "Sales by region",
    "owner": "analytics",
    "tags": ["sales"],
    "source": "/data/sales.csv",
    "format": "csv",
    "path": "/cache/sales.parquet",
    "rows": 3,
    "columns": 2,
    "version": 2,
    "contentHash": "abc",
    "createdAt": "2026-11-15T10:00:00Z",
    "updatedAt": "2026-11-15T10:00:00Z",
    "profile": {"rows": 3, "columns": [{"name": "region", "type": "String"}]},
}
TABLE = {"node": "1:1", "port": 0, "columns": [{"name": "region", "type": "Utf8"}],
         "rows": 3, "data": [["north"], ["south"], ["north"]], "preview": 3}
PROFILE = {"rows": 3, "columns": [{"name": "region", "type": "String"}]}


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_dataset_under_test", ADAPTER)
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


class CatalogEngine:
    def __init__(self):
        self.calls = []

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "GET" and path == "/v1/catalog/datasets":
            return {"datasets": [ENTRY]}
        if method == "GET" and path == "/v1/catalog/datasets/sales":
            return ENTRY
        if method == "POST" and path == "/v1/catalog/datasets":
            return dict(ENTRY, name=(body or {}).get("name"), version=1)
        if method == "POST" and path == "/v1/catalog/datasets/sales/refresh":
            return dict(ENTRY, version=3)
        if method == "DELETE" and path == "/v1/catalog/datasets/sales":
            return {"ok": True, "name": "sales"}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def paths(self):
        return [(method, path) for method, path, _ in self.calls]


class AnalyzeEngine:
    """dap_analyze with a catalog name instead of a path."""

    def __init__(self, catalog_status=200, error_type=None):
        self.calls = []
        self.catalog_status = catalog_status
        self.error_type = error_type

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "GET" and path.startswith("/v1/catalog/datasets/"):
            if self.catalog_status != 200 or not path.endswith("/sales"):
                raise self.module_error()
            return ENTRY
        if method == "POST" and path == "/v1/workflows":
            return {"id": "wf-1", "name": "analyze"}
        if method == "POST" and path == "/v1/workflows/wf-1/nodes":
            return {"node": "1:1", "name": "Parquet Reader"}
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            return {"ok": True, "durationMs": 1, "nodes": []}
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:1/table"):
            return TABLE
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:1/profile"):
            return PROFILE
        if method == "DELETE" and path == "/v1/workflows/wf-1":
            return {"ok": True}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def module_error(self):
        return self.error_type("catalog error", data={"status": self.catalog_status})

    def paths(self):
        return [(method, path) for method, path, _ in self.calls]


class DatasetToolTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-dataset-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def run_tool(self, arguments):
        engine = CatalogEngine()
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_dataset", arguments)
        return result, engine

    def run_error(self, arguments):
        engine = CatalogEngine()
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, "dap_dataset", arguments)
        return error, engine

    def test_list_returns_the_catalog(self):
        result, engine = self.run_tool({"action": "list"})
        self.assertEqual(result["datasets"][0]["name"], "sales")
        self.assertEqual(engine.paths(), [("GET", "/v1/catalog/datasets")])

    def test_list_is_the_default_action(self):
        result, engine = self.run_tool({})
        self.assertEqual(result["datasets"][0]["name"], "sales")
        self.assertEqual(engine.paths(), [("GET", "/v1/catalog/datasets")])

    def test_add_registers_a_file(self):
        result, engine = self.run_tool({
            "action": "add", "name": "sales", "file": "/data/sales.csv",
            "format": "csv", "description": "Sales by region",
            "tags": ["sales"], "owner": "analytics",
        })
        self.assertEqual(result["name"], "sales")
        self.assertEqual(engine.calls, [
            ("POST", "/v1/catalog/datasets", {
                "name": "sales", "file": "/data/sales.csv", "format": "csv",
                "description": "Sales by region", "tags": ["sales"],
                "owner": "analytics"}),
        ])

    def test_add_registers_an_existing_dataset(self):
        _result, engine = self.run_tool({
            "action": "add", "name": "sales", "dataset": "/cache/sales.parquet",
        })
        self.assertEqual(engine.calls[0][2],
                         {"name": "sales", "dataset": "/cache/sales.parquet"})

    def test_show_returns_one_entry(self):
        result, engine = self.run_tool({"action": "show", "name": "sales"})
        self.assertEqual(result["path"], "/cache/sales.parquet")
        self.assertEqual(result["version"], 2)
        self.assertEqual(engine.paths(), [("GET", "/v1/catalog/datasets/sales")])

    def test_refresh_returns_the_new_version(self):
        result, engine = self.run_tool({"action": "refresh", "name": "sales"})
        self.assertEqual(result["version"], 3)
        self.assertEqual(engine.paths(),
                         [("POST", "/v1/catalog/datasets/sales/refresh")])

    def test_remove_deletes_the_entry(self):
        result, engine = self.run_tool({"action": "remove", "name": "sales"})
        self.assertTrue(result["ok"])
        self.assertEqual(engine.paths(), [("DELETE", "/v1/catalog/datasets/sales")])

    def test_unknown_action_is_rejected_before_http(self):
        error, engine = self.run_error({"action": "drop", "name": "sales"})
        self.assertEqual(error["code"], -32602)
        self.assertIn("action", error["message"])
        self.assertEqual(engine.calls, [])

    def test_name_is_required_outside_list(self):
        for action in ("add", "show", "refresh", "remove"):
            with self.subTest(action=action):
                error, engine = self.run_error({"action": action})
                self.assertEqual(error["code"], -32602)
                self.assertEqual(engine.calls, [])

    def test_invalid_name_is_rejected_before_http(self):
        for name in ("../evil", "a/b", "", "x" * 65):
            with self.subTest(name=name):
                error, engine = self.run_error({"action": "show", "name": name})
                self.assertEqual(error["code"], -32602)
                self.assertEqual(engine.calls, [])

    def test_add_requires_exactly_one_source(self):
        for arguments in ({"action": "add", "name": "sales"},
                          {"action": "add", "name": "sales", "file": "/a.csv",
                           "dataset": "/b.parquet"}):
            with self.subTest(arguments=arguments):
                error, engine = self.run_error(arguments)
                self.assertEqual(error["code"], -32602)
                self.assertEqual(engine.calls, [])

    def test_tags_must_be_an_array_of_strings(self):
        error, engine = self.run_error({"action": "add", "name": "sales",
                                        "file": "/a.csv", "tags": "sales"})
        self.assertEqual(error["code"], -32602)
        self.assertEqual(engine.calls, [])

    def test_engine_error_is_returned(self):
        failure = self.module.DapError("dataset already exists",
                                       data={"status": 409, "code": "dataset_exists"})
        engine = CatalogEngine()
        original = engine.__call__

        def conflict(method, path, body=None, timeout=None):
            engine.calls.append((method, path, body))
            raise failure

        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=conflict):
            error = tool_error(self.module, "dap_dataset",
                               {"action": "add", "name": "sales", "file": "/a.csv"})
        self.assertEqual(error["code"], -32000)
        self.assertEqual(error["data"]["code"], "dataset_exists")


class DatasetResolutionTest(unittest.TestCase):
    """dap_analyze / dap_profile accept a catalog name in 'dataset'."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-resolve-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def run_tool(self, name, arguments, engine):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            return tool_ok(self.module, name, arguments)

    def make_engine(self, status=200):
        return AnalyzeEngine(status, self.module.DapError)

    def test_analyze_resolves_a_catalog_name_to_its_path(self):
        engine = self.make_engine()
        result = self.run_tool("dap_analyze", {"dataset": "sales"}, engine)
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertEqual(engine.paths()[0], ("GET", "/v1/catalog/datasets/sales"))
        self.assertIn(("POST", "/v1/workflows"), engine.paths())

    def test_analyze_keeps_an_existing_path_untouched(self):
        engine = self.make_engine()
        with mock.patch.object(self.module.os.path, "exists", return_value=True):
            result = self.run_tool("dap_analyze", {"dataset": "/cache/x.parquet"}, engine)
        self.assertEqual(result["source"], "/cache/x.parquet")
        self.assertNotIn(("GET", "/v1/catalog/datasets/x.parquet"), engine.paths())

    def test_unknown_catalog_name_falls_back_to_the_given_value(self):
        engine = self.make_engine(status=404)
        with mock.patch.object(self.module.os.path, "exists", return_value=False):
            result = self.run_tool("dap_profile", {"dataset": "missing"}, engine)
        self.assertEqual(result["source"], "missing")
        self.assertEqual(engine.paths()[0], ("GET", "/v1/catalog/datasets/missing"))

    def test_catalog_error_other_than_404_is_raised(self):
        engine = self.make_engine(status=500)
        with mock.patch.object(self.module.os.path, "exists", return_value=False):
            with mock.patch.object(self.module, "ensure_service"), \
                    mock.patch.object(self.module, "call", side_effect=engine):
                reply = rpc(self.module, "tools/call",
                            {"name": "dap_profile",
                             "arguments": {"dataset": "sales"}})
        self.assertIn("error", reply)


class DatasetResourceTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-dataset-res-")
        self.addCleanup(self._tmp.cleanup)
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def contents(self, uri, engine):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            reply = rpc(self.module, "resources/read", {"uri": uri})
        self.assertNotIn("error", reply, reply)
        return reply["result"]["contents"][0]

    def test_datasets_resource_lists_entries(self):
        content = self.contents("dap://datasets", CatalogEngine())
        self.assertEqual(content["uri"], "dap://datasets")
        self.assertEqual(content["mimeType"], "application/json")
        payload = json.loads(content["text"])
        self.assertEqual(payload["count"], 1)
        entry = payload["datasets"][0]
        self.assertEqual(entry["name"], "sales")
        self.assertEqual(entry["rows"], 3)
        self.assertEqual(entry["version"], 2)
        self.assertNotIn("contentHash", entry, "the list view stays compact")

    def test_dataset_resource_returns_entry_and_profile(self):
        content = self.contents("dap://datasets/sales", CatalogEngine())
        self.assertEqual(content["uri"], "dap://datasets/sales")
        payload = json.loads(content["text"])
        self.assertEqual(payload["name"], "sales")
        self.assertEqual(payload["path"], "/cache/sales.parquet")
        self.assertEqual(payload["profile"]["rows"], 3)

    def test_datasets_resource_is_listed(self):
        reply = rpc(self.module, "resources/list", {})
        by_uri = {item["uri"]: item for item in reply["result"]["resources"]}
        self.assertIn("dap://datasets", by_uri)
        self.assertTrue(by_uri["dap://datasets"]["description"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
