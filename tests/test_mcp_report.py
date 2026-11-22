#!/usr/bin/env python3
"""Unit tests for dap_report and dap_profile (mocked /v1)."""
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

TABLE_READER = {"node": "1:1", "port": 0, "columns": [{"name": "region", "type": "Utf8"}],
                "rows": 3, "data": [["north"], ["south"], ["north"]], "preview": 3}
TABLE_QUERY = {"node": "1:2", "port": 0,
               "columns": [{"name": "region", "type": "Utf8"},
                           {"name": "total", "type": "Float64"}],
               "rows": 2, "data": [["north", "15.75"], ["south", "20.0"]], "preview": 2}
PROFILE_SOURCE = {"rows": 3, "columns": [
    {"name": "region", "type": "String", "missing": 0, "distinct": 2},
    {"name": "amount", "type": "Number (Float)", "missing": 0, "distinct": 3}]}
PROFILE_QUERY = {"rows": 2, "columns": [
    {"name": "region", "type": "String", "missing": 0, "distinct": 2},
    {"name": "total", "type": "Number (Float)", "missing": 0, "distinct": 2}]}


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_report_under_test", ADAPTER)
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
    """A /v1 double: the report and chart sinks really write their output files."""

    def __init__(self, directory=None, fail_factory=None, fail_run=False, fail_delete=False):
        self.directory = pathlib.Path(directory) if directory else None
        self.calls = []
        self.fail_factory = fail_factory
        self.fail_run = fail_run
        self.fail_delete = fail_delete

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/datasets":
            return {"path": "/cache/sales.parquet", "rows": 3, "cached": False,
                    "columns": [{"name": "region", "type": "Utf8"}]}
        if method == "POST" and path == "/v1/workflows":
            return {"id": "wf-1", "name": "x", "session": "default", "nodes": 0}
        if method == "POST" and path == "/v1/workflows/wf-1/nodes":
            factory = (body or {}).get("factory")
            if factory == self.fail_factory:
                raise RuntimeError("boom: " + str(factory))
            settings = (body or {}).get("settings") or {}
            if factory == "dap.parquet_reader":
                return {"node": "1:1", "name": "Parquet Reader"}
            if factory == "dap.sql_query":
                return {"node": "1:2", "name": "SQL Query"}
            if factory == "dap.markdown_report":
                self.write(settings.get("file"), "# Report\n| a | b |\n| --- | --- |\n")
                return {"node": "1:3", "name": "Markdown Report"}
            if factory == "dap.chart":
                self.write(settings.get("file"), "<svg></svg>\n")
                return {"node": "1:4", "name": "Chart"}
            raise AssertionError("unexpected factory: %r" % (factory,))
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
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:1/profile"):
            return PROFILE_SOURCE
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:2/profile"):
            return PROFILE_QUERY
        if method == "DELETE" and path == "/v1/workflows/wf-1":
            if self.fail_delete:
                raise RuntimeError("delete boom")
            return {"ok": True}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def write(self, path, text):
        target = pathlib.Path(path)
        if not target.is_absolute():
            target = self.directory / target
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def paths(self):
        return [(method, path) for method, path, _ in self.calls]

    def bodies(self, factory):
        return [body for method, path, body in self.calls
                if method == "POST" and path == "/v1/workflows/wf-1/nodes"
                and (body or {}).get("factory") == factory]

    def factories(self):
        return [body["factory"] for body in
                (body for method, path, body in self.calls
                 if method == "POST" and path == "/v1/workflows/wf-1/nodes")]


class ReportTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-report-")
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)
        self.module = load_adapter(DAP_HOME=str(self.dir))
        self.report = str(self.dir / "report.md")
        self.chart = str(self.dir / "chart.svg")

    def run_tool(self, arguments, **engine_options):
        engine = FakeEngine(self.dir, **engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_report", arguments)
        return result, engine

    def run_error(self, arguments, **engine_options):
        engine = FakeEngine(self.dir, **engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, "dap_report", arguments)
        return error, engine

    def test_report_and_chart_are_written_in_one_call(self):
        result, engine = self.run_tool({
            "file": "/data/sales.csv",
            "sql": "SELECT region, SUM(amount) AS total FROM src GROUP BY region",
            "report": self.report,
            "chart": self.chart,
            "x": "region",
            "y": ["total"],
            "title": "Sales summary",
        })
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertFalse(result["cached"])
        self.assertEqual(result["node"], "1:2")
        self.assertEqual(result["table"], TABLE_QUERY)
        self.assertEqual(result["profile"], PROFILE_QUERY)
        self.assertEqual(result["report"], self.report)
        self.assertEqual(result["chart"], self.chart)
        self.assertEqual(set(result["bytes"]), {"report", "chart"})
        self.assertTrue(pathlib.Path(self.report).is_file())
        self.assertTrue(pathlib.Path(self.chart).is_file())
        self.assertGreater(result["bytes"]["report"], 0)
        self.assertGreater(result["bytes"]["chart"], 0)
        self.assertEqual(engine.factories(),
                         ["dap.parquet_reader", "dap.sql_query",
                          "dap.markdown_report", "dap.chart"])
        report_settings = engine.bodies("dap.markdown_report")[0]["settings"]
        self.assertEqual(report_settings["sections"],
                         [{"kind": "table", "limit": 20}, {"kind": "profile"}])
        self.assertEqual(report_settings["title"], "Sales summary")
        chart_settings = engine.bodies("dap.chart")[0]["settings"]
        self.assertEqual(chart_settings, {"file": self.chart, "format": "svg",
                                          "type": "bar", "x": "region",
                                          "y": ["total"], "title": "Sales summary"})
        connects = [body for method, path, body in engine.calls
                    if method == "POST" and path == "/v1/workflows/wf-1/connect"]
        self.assertEqual([(body["from"], body["to"]) for body in connects],
                         [("1:1", "1:2"), ("1:2", "1:3"), ("1:3", "1:4")])
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())

    def test_legacy_input_format_still_selects_the_ingest_format(self):
        result, engine = self.run_tool({"file": "/data/sales.csv", "format": "csv",
                                        "report": self.report})
        self.assertEqual(result["format"], "md")
        self.assertEqual(engine.calls[0][2], {"file": "/data/sales.csv", "format": "csv"})
        settings = engine.bodies("dap.markdown_report")[0]["settings"]
        self.assertEqual(settings["format"], "md")

    def test_png_extension_selects_png_format(self):
        result, engine = self.run_tool({
            "dataset": "/cache/sales.parquet", "report": self.report,
            "chart": str(self.dir / "chart.png"), "x": "region", "y": ["total"],
            "chartType": "histogram",
        })
        self.assertEqual(result["chart"], str(self.dir / "chart.png"))
        self.assertEqual(engine.factories(),
                         ["dap.parquet_reader", "dap.markdown_report", "dap.chart"])
        chart_settings = engine.bodies("dap.chart")[0]["settings"]
        self.assertEqual(chart_settings["format"], "png")
        self.assertEqual(chart_settings["type"], "histogram")

    def test_report_without_chart_has_no_chart_key(self):
        result, engine = self.run_tool({"dataset": "/cache/x.parquet",
                                        "report": self.report, "profile": False})
        self.assertNotIn("chart", result)
        self.assertNotIn("profile", result)
        self.assertEqual(engine.factories(), ["dap.parquet_reader", "dap.markdown_report"])
        sections = engine.bodies("dap.markdown_report")[0]["settings"]["sections"]
        self.assertEqual(sections, [{"kind": "table", "limit": 20}])

    def test_report_honours_limit_and_cache_hit(self):
        engine = FakeEngine(self.dir)
        original = engine.__call__

        def cached_engine(method, path, body=None, timeout=None):
            if method == "POST" and path == "/v1/datasets":
                engine.calls.append((method, path, body))
                return {"path": "/cache/sales.parquet", "rows": 3, "cached": True}
            return original(method, path, body, timeout)

        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=cached_engine):
            result = tool_ok(self.module, "dap_report",
                             {"file": "/data/sales.csv", "report": self.report, "limit": 5})
        self.assertTrue(result["cached"])
        sections = engine.bodies("dap.markdown_report")[0]["settings"]["sections"]
        self.assertEqual(sections, [{"kind": "table", "limit": 5}, {"kind": "profile"}])
        self.assertIn("limit=5", [path for method, path in engine.paths()
                                  if method == "GET" and "/table" in path][0])

    def test_validation_fails_before_http(self):
        cases = [
            ({}, "file"),
            ({"file": "/a.csv", "dataset": "/b.parquet", "report": self.report}, "оба"),
            ({"dataset": "/b.parquet"}, "report"),
            ({"dataset": "/b.parquet", "report": "  "}, "report"),
            ({"dataset": "/b.parquet", "report": self.report, "sql": "DROP TABLE src"},
             "sql_not_allowed"),
            ({"dataset": "/b.parquet", "report": self.report, "limit": 0}, "limit"),
            ({"dataset": "/b.parquet", "report": self.report, "limit": 1001}, "limit"),
            ({"dataset": "/b.parquet", "report": self.report, "chartType": "pie"},
             "chartType"),
            ({"dataset": "/b.parquet", "report": self.report, "y": "total"}, "y"),
            ({"dataset": "/b.parquet", "report": self.report, "format": "docx"},
             "format"),
            ({"dataset": "/b.parquet", "report": self.report, "sql": "SELECT 1",
              "queries": [{"sql": "SELECT 2"}]}, "оба"),
            ({"dataset": "/b.parquet", "report": self.report, "queries": []},
             "queries"),
            ({"dataset": "/b.parquet", "report": self.report,
              "queries": [{"sql": "DROP TABLE src"}]}, "sql_not_allowed"),
            ({"dataset": "/b.parquet", "report": self.report,
              "queries": [{"limit": 5}]}, "sql"),
            ({"dataset": "/b.parquet", "report": self.report,
              "queries": [{"sql": "SELECT 1", "limit": 0}]}, "limit"),
        ]
        for arguments, expected in cases:
            with self.subTest(arguments=arguments):
                with mock.patch.object(self.module, "ensure_service"), \
                        mock.patch.object(self.module, "call") as call:
                    error = tool_error(self.module, "dap_report", arguments)
                self.assertEqual(error["code"], -32602)
                self.assertIn(expected, error["message"] + str(error.get("data", {})))
                call.assert_not_called()

    def test_run_failure_still_deletes_the_workflow(self):
        error, engine = self.run_error({"dataset": "/cache/x.parquet",
                                        "report": self.report}, fail_run=True)
        self.assertEqual(error["code"], -32603)
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())

    def test_chart_failure_still_deletes_the_workflow(self):
        error, engine = self.run_error({
            "dataset": "/cache/x.parquet", "report": self.report,
            "chart": self.chart, "x": "region", "y": ["total"],
        }, fail_factory="dap.chart")
        self.assertEqual(error["code"], -32603)
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())

    def test_cleanup_failure_does_not_hide_the_result(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            result, engine = self.run_tool({"dataset": "/cache/x.parquet",
                                            "report": self.report}, fail_delete=True)
        self.assertEqual(result["report"], self.report)
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())
        self.assertIn("cannot delete report workflow", stderr.getvalue())


class MultiReportEngine:
    """A /v1 double with two sql nodes feeding two report input ports."""

    def __init__(self, directory):
        self.directory = pathlib.Path(directory)
        self.calls = []
        self.sql_nodes = []

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "POST" and path == "/v1/workflows":
            return {"id": "wf-1", "name": "report", "session": "default", "nodes": 0}
        if method == "POST" and path == "/v1/workflows/wf-1/nodes":
            factory = (body or {}).get("factory")
            if factory == "dap.parquet_reader":
                return {"node": "1:1", "name": "Parquet Reader"}
            if factory == "dap.sql_query":
                node = "1:%d" % (2 + len(self.sql_nodes))
                self.sql_nodes.append(node)
                return {"node": node, "name": "SQL Query"}
            if factory == "dap.markdown_report":
                settings = (body or {}).get("settings") or {}
                self.write(settings.get("file"), "# Report\n")
                return {"node": "1:4", "name": "Markdown Report"}
            if factory == "dap.chart":
                settings = (body or {}).get("settings") or {}
                self.write(settings.get("file"), "<svg></svg>\n")
                return {"node": "1:5", "name": "Chart"}
            raise AssertionError("unexpected factory: %r" % (factory,))
        if method == "POST" and path == "/v1/workflows/wf-1/connect":
            return {"ok": True}
        if method == "POST" and path == "/v1/workflows/wf-1/run":
            return {"ok": True, "durationMs": 1, "nodes": []}
        for node in self.sql_nodes:
            if method == "GET" and path.startswith(
                    f"/v1/workflows/wf-1/nodes/{node}/table"):
                return {"node": node, "port": 0,
                        "columns": [{"name": "n", "type": "Int64"}],
                        "rows": 1, "data": [["1"]], "preview": 1}
            if method == "GET" and path.startswith(
                    f"/v1/workflows/wf-1/nodes/{node}/profile"):
                return {"rows": 1, "columns": [{"name": "n", "type": "Number"}]}
        if method == "DELETE" and path == "/v1/workflows/wf-1":
            return {"ok": True}
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def write(self, path, text):
        target = pathlib.Path(path)
        if not target.is_absolute():
            target = self.directory / target
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def paths(self):
        return [(method, path) for method, path, _ in self.calls]

    def bodies(self, factory):
        return [body for method, path, body in self.calls
                if method == "POST" and path == "/v1/workflows/wf-1/nodes"
                and (body or {}).get("factory") == factory]

    def connects(self):
        return [body for method, path, body in self.calls
                if method == "POST" and path == "/v1/workflows/wf-1/connect"]


class MultiQueryReportTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-multireport-")
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)
        self.module = load_adapter(DAP_HOME=str(self.dir))
        self.report = str(self.dir / "report.md")

    def run_tool(self, arguments):
        engine = MultiReportEngine(self.dir)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_report", arguments)
        return result, engine

    def test_queries_build_one_sql_node_per_table_on_its_own_port(self):
        result, engine = self.run_tool({
            "dataset": "/cache/sales.parquet",
            "report": self.report,
            "queries": [
                {"sql": "SELECT COUNT(*) AS n FROM src", "title": "Totals", "limit": 5},
                {"sql": "SELECT MAX(n) AS top FROM src"},
            ],
        })
        self.assertEqual([body["factory"] for body in
                          (body for method, path, body in engine.calls
                           if method == "POST" and path == "/v1/workflows/wf-1/nodes")],
                         ["dap.parquet_reader", "dap.sql_query", "dap.sql_query",
                          "dap.markdown_report"])
        self.assertEqual([(body["from"], body["to"], body["toPort"])
                          for body in engine.connects()],
                         [("1:1", "1:2", 0), ("1:1", "1:3", 0),
                          ("1:2", "1:4", 0), ("1:3", "1:4", 1)])
        sections = engine.bodies("dap.markdown_report")[0]["settings"]["sections"]
        self.assertEqual(sections, [
            {"kind": "table", "input": 0, "limit": 5, "title": "Totals"},
            {"kind": "table", "input": 1, "limit": 20},
            {"kind": "profile", "input": 0},
        ])
        self.assertEqual(result["tables"],
                         [{"index": 0, "node": "1:2", "table": {"node": "1:2", "port": 0,
                           "columns": [{"name": "n", "type": "Int64"}], "rows": 1,
                           "data": [["1"]], "preview": 1}},
                          {"index": 1, "node": "1:3", "table": {"node": "1:3", "port": 0,
                           "columns": [{"name": "n", "type": "Int64"}], "rows": 1,
                           "data": [["1"]], "preview": 1}}])
        self.assertEqual(result["report"], self.report)
        self.assertEqual(result["format"], "md")
        self.assertIn("bytes", result)
        self.assertTrue(pathlib.Path(self.report).is_file())
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())

    def test_queries_without_profile_have_no_profile_section(self):
        result, engine = self.run_tool({
            "dataset": "/cache/sales.parquet", "report": self.report,
            "profile": False,
            "queries": [{"sql": "SELECT 1 AS n"}],
        })
        self.assertNotIn("profile", result)
        sections = engine.bodies("dap.markdown_report")[0]["settings"]["sections"]
        self.assertEqual(sections, [{"kind": "table", "input": 0, "limit": 20}])

    def test_queries_with_chart_connect_the_report_pass_through(self):
        chart = str(self.dir / "chart.svg")
        result, engine = self.run_tool({
            "dataset": "/cache/sales.parquet", "report": self.report,
            "chart": chart, "x": "n", "y": ["n"],
            "queries": [{"sql": "SELECT 1 AS n"}],
        })
        self.assertEqual(result["chart"], chart)
        self.assertEqual(engine.connects()[-1],
                         {"from": "1:4", "fromPort": 0, "to": "1:5", "toPort": 0})
        self.assertEqual(set(result["bytes"]), {"report", "chart"})

    def test_format_is_passed_to_the_report_node(self):
        for fmt in ("md", "html", "pdf", "pptx"):
            with self.subTest(format=fmt):
                report = str(self.dir / ("report." + fmt))
                result, engine = self.run_tool({
                    "dataset": "/cache/sales.parquet", "report": report,
                    "format": fmt,
                    "queries": [{"sql": "SELECT 1 AS n"}],
                })
                self.assertEqual(result["format"], fmt)
                settings = engine.bodies("dap.markdown_report")[0]["settings"]
                self.assertEqual(settings["format"], fmt)
                self.assertEqual(settings["file"], report)

    def test_single_sql_report_still_works_with_a_format(self):
        result, engine = self.run_tool({
            "dataset": "/cache/sales.parquet", "report": self.report,
            "format": "html",
            "sql": "SELECT 1 AS n",
        })
        self.assertEqual(result["node"], "1:2")
        self.assertEqual(result["format"], "html")
        settings = engine.bodies("dap.markdown_report")[0]["settings"]
        self.assertEqual(settings["format"], "html")
        self.assertEqual(settings["sections"],
                         [{"kind": "table", "limit": 20}, {"kind": "profile"}])
        self.assertEqual([(body["from"], body["to"]) for body in engine.connects()],
                         [("1:1", "1:2"), ("1:2", "1:4")])


class ProfileTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-profile-")
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)
        self.module = load_adapter(DAP_HOME=str(self.dir))

    def run_tool(self, arguments, **engine_options):
        engine = FakeEngine(self.dir, **engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_profile", arguments)
        return result, engine

    def run_error(self, arguments, **engine_options):
        engine = FakeEngine(self.dir, **engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, "dap_profile", arguments)
        return error, engine

    def test_profile_only_returns_no_table(self):
        result, engine = self.run_tool({"dataset": "/cache/sales.parquet"})
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertFalse(result["cached"])
        self.assertEqual(result["profile"], PROFILE_SOURCE)
        self.assertEqual(result["rows"], 3)
        self.assertEqual(result["columns"], 2)
        self.assertNotIn("table", result)
        self.assertEqual(engine.factories(), ["dap.parquet_reader"])
        profile_urls = [path for method, path in engine.paths()
                        if method == "GET" and path.endswith("/profile")]
        self.assertEqual(len(profile_urls), 1)
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())

    def test_profile_schema_has_no_limit_argument(self):
        tools = {tool["name"]: tool for tool in self.module.TOOLS}
        self.assertNotIn("limit", tools["dap_profile"]["inputSchema"]["properties"])

    def test_profile_for_file_ingests_and_reads_profile(self):
        result, engine = self.run_tool({"file": "/data/sales.csv"})
        self.assertEqual(result["source"], "/cache/sales.parquet")
        self.assertIn(("POST", "/v1/datasets"), engine.paths())
        profile_urls = [path for method, path in engine.paths()
                        if method == "GET" and path.endswith("/profile")]
        self.assertEqual(len(profile_urls), 1)

    def test_validation_fails_before_http(self):
        for arguments, expected in (
            ({}, "file"),
            ({"file": "/a.csv", "dataset": "/b.parquet"}, "оба"),
        ):
            with self.subTest(arguments=arguments):
                with mock.patch.object(self.module, "ensure_service"), \
                        mock.patch.object(self.module, "call") as call:
                    error = tool_error(self.module, "dap_profile", arguments)
                self.assertEqual(error["code"], -32602)
                self.assertIn(expected, error["message"])
                call.assert_not_called()

    def test_run_failure_still_deletes_the_workflow(self):
        error, engine = self.run_error({"dataset": "/cache/x.parquet"}, fail_run=True)
        self.assertEqual(error["code"], -32603)
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())

    def test_cleanup_failure_does_not_hide_the_result(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            result, engine = self.run_tool({"dataset": "/cache/x.parquet"},
                                           fail_delete=True)
        self.assertEqual(result["rows"], 3)
        self.assertIn(("DELETE", "/v1/workflows/wf-1"), engine.paths())
        self.assertIn("cannot delete profile workflow", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
