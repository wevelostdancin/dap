#!/usr/bin/env python3
"""Unit tests for dap_get_table: paging, projection, filter and compact formats.

The engine is a fake that answers the /table query string the adapter builds, so
the tests cover both what the adapter sends and what it returns to the agent.

    python3 -m unittest tests.test_mcp_table -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import unittest
import urllib.parse
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"

COLUMNS = [
    {"name": "region", "type": "Utf8"},
    {"name": "total", "type": "Float64"},
    {"name": "note", "type": "Utf8"},
]
ROWS = [
    ["north", "15.75", "plain"],
    ["south", "20.0", "a,b"],
    ["north", "5.25", 'say "hi"'],
]


def load_adapter(**environment):
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_table_under_test", ADAPTER)
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
    """A /v1 double that applies offset/limit/columns/where to a tiny table."""

    def __init__(self, rows=None, metadata=True):
        self.rows = [list(row) for row in (rows if rows is not None else ROWS)]
        self.metadata = metadata
        self.calls = []

    def __call__(self, method, path, body=None, timeout=None):
        self.calls.append((method, path, body))
        if method == "GET" and path.startswith("/v1/workflows/wf-1/nodes/1:1/table?"):
            return self.table(path)
        raise AssertionError("unexpected HTTP call: %s %s %r" % (method, path, body))

    def table(self, path):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query, keep_blank_values=True)
        offset = int(query.get("offset", ["0"])[0])
        limit = int(query.get("limit", ["20"])[0])
        selected = self.filter(query.get("where", [None])[0])
        indexes = list(range(len(COLUMNS)))
        columns = [dict(column) for column in COLUMNS]
        if query.get("columns"):
            wanted = query["columns"][0].split(",")
            indexes = [[column["name"] for column in COLUMNS].index(name) for name in wanted]
            columns = [dict(COLUMNS[index]) for index in indexes]
        page = [[row[index] for index in indexes]
                for row in selected[offset:offset + limit]]
        body = {"node": "1:1", "port": 0, "columns": columns,
                "rows": len(selected), "data": page, "preview": len(page)}
        if self.metadata:
            following = offset + len(page) if offset + len(page) < len(selected) else None
            body.update({"offset": offset, "nextOffset": following,
                         "truncated": following is not None})
        return body

    def filter(self, where):
        if not where:
            return self.rows
        if where == "total > 10":
            return [row for row in self.rows if float(row[1]) > 10]
        if where == "region = 'north'":
            return [row for row in self.rows if row[0] == "north"]
        raise AssertionError("unexpected where: %r" % (where,))

    def paths(self):
        return [(method, path) for method, path, _ in self.calls]

    def query_of(self):
        return urllib.parse.parse_qs(urllib.parse.urlsplit(self.calls[0][1]).query)


class GetTableTest(unittest.TestCase):
    def setUp(self):
        self._tmp = self.make_tempdir()
        self.module = load_adapter(DAP_HOME=self._tmp.name)

    def make_tempdir(self):
        import tempfile
        directory = tempfile.TemporaryDirectory(prefix="dap-mcp-table-")
        self.addCleanup(directory.cleanup)
        return directory

    def run_tool(self, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            result = tool_ok(self.module, "dap_get_table", arguments)
        return result, engine

    def run_error(self, arguments, **engine_options):
        engine = FakeEngine(**engine_options)
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call", side_effect=engine):
            error = tool_error(self.module, "dap_get_table", arguments)
        return error, engine

    # -- paging --------------------------------------------------------------

    def test_paging_passes_offset_and_returns_the_next_offset(self):
        result, engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                        "offset": 0, "limit": 2})
        self.assertEqual(engine.query_of(), {"port": ["0"], "limit": ["2"]})
        self.assertEqual(result["offset"], 0)
        self.assertEqual(result["nextOffset"], 2)
        self.assertTrue(result["truncated"])
        self.assertEqual(result["data"], [["north", "15.75", "plain"],
                                          ["south", "20.0", "a,b"]])

    def test_offset_is_forwarded_to_the_engine(self):
        result, engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                        "offset": 1, "limit": 1})
        self.assertEqual(engine.query_of(), {"port": ["0"], "limit": ["1"],
                                             "offset": ["1"]})
        self.assertEqual(result["offset"], 1)
        self.assertEqual(result["nextOffset"], 2)
        self.assertEqual(result["data"], [["south", "20.0", "a,b"]])

    def test_last_page_has_no_next_offset(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "offset": 2, "limit": 2})
        self.assertEqual(result["offset"], 2)
        self.assertIsNone(result["nextOffset"])
        self.assertFalse(result["truncated"])
        self.assertEqual(result["data"], [["north", "5.25", 'say "hi"']])

    def test_first_page_without_offset_starts_at_zero(self):
        result, engine = self.run_tool({"workflow": "wf-1", "node": "1:1", "limit": 1})
        self.assertNotIn("offset", engine.query_of())
        self.assertEqual(result["offset"], 0)
        self.assertEqual(result["nextOffset"], 1)
        self.assertTrue(result["truncated"])

    def test_metadata_is_computed_when_the_engine_omits_it(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "offset": 0, "limit": 2}, metadata=False)
        self.assertEqual(result["offset"], 0)
        self.assertEqual(result["nextOffset"], 2)
        self.assertTrue(result["truncated"])

    def test_metadata_computation_marks_the_last_page(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "offset": 2, "limit": 2}, metadata=False)
        self.assertIsNone(result["nextOffset"])
        self.assertFalse(result["truncated"])

    # -- projection and filter ----------------------------------------------

    def test_columns_array_projects_and_reorders(self):
        result, engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                        "columns": ["total", "region"]})
        self.assertEqual(engine.query_of()["columns"], ["total,region"])
        self.assertEqual([column["name"] for column in result["columns"]],
                         ["total", "region"])
        self.assertEqual(result["data"][0], ["15.75", "north"])

    def test_columns_accepts_a_comma_separated_string(self):
        result, engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                        "columns": " region , total "})
        self.assertEqual(engine.query_of()["columns"], ["region,total"])
        self.assertEqual([column["name"] for column in result["columns"]],
                         ["region", "total"])

    def test_where_is_forwarded_and_filters_the_rows(self):
        result, engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                        "where": "total > 10"})
        self.assertEqual(engine.query_of()["where"], ["total > 10"])
        self.assertEqual(result["rows"], 2)
        self.assertEqual([row[0] for row in result["data"]], ["north", "south"])

    def test_where_inside_string_literals_is_allowed(self):
        result, engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                        "where": "region = 'north'"})
        self.assertEqual(engine.query_of()["where"], ["region = 'north'"])
        self.assertEqual(result["rows"], 2)

    # -- formats -------------------------------------------------------------

    def test_json_is_the_default_format(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1"})
        self.assertNotIn("format", result)
        self.assertNotIn("text", result)
        self.assertEqual(result["rows"], 3)
        self.assertEqual(len(result["data"]), 3)

    def test_objects_keys_rows_by_column_name(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "format": "objects", "limit": 1})
        self.assertEqual(result["data"], [{"region": "north", "total": "15.75",
                                           "note": "plain"}])
        self.assertEqual(result["rows"], 3)
        self.assertEqual(result["offset"], 0)
        self.assertEqual(result["nextOffset"], 1)

    def test_csv_returns_compact_text_with_quoted_cells(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "format": "csv"})
        self.assertEqual(result["format"], "csv")
        self.assertEqual(result["rows"], 3)
        self.assertNotIn("data", result)
        self.assertEqual(result["text"],
                         "region,total,note\n"
                         "north,15.75,plain\n"
                         'south,20.0,"a,b"\n'
                         'north,5.25,"say ""hi"""\n')

    def test_markdown_returns_a_table(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "format": "markdown", "columns": ["region", "total"],
                                         "limit": 1})
        self.assertEqual(result["format"], "markdown")
        self.assertEqual(result["text"],
                         "| region | total |\n"
                         "| --- | --- |\n"
                         "| north | 15.75 |\n")

    def test_compact_formats_keep_paging_metadata(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "format": "csv", "offset": 2, "limit": 2})
        self.assertEqual(result["offset"], 2)
        self.assertIsNone(result["nextOffset"])
        self.assertFalse(result["truncated"])

    def test_invalid_format_is_rejected_before_http(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_get_table",
                               {"workflow": "wf-1", "node": "1:1", "format": "xml"})
        self.assertEqual(error["code"], -32602)
        self.assertIn("format", error["message"])
        call.assert_not_called()

    # -- truncation and column suppression ----------------------------------

    def test_cells_are_truncated_with_an_ellipsis(self):
        long_row = ["north", "1.0", "x" * 300]
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "maxCellChars": 5}, rows=[long_row])
        cell = result["data"][0][2]
        self.assertEqual(cell, "xxxx\u2026")
        self.assertEqual(len(cell), 5)

    def test_default_cell_limit_is_two_hundred_characters(self):
        long_row = ["north", "1.0", "x" * 300]
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1"}, rows=[long_row])
        cell = result["data"][0][2]
        self.assertEqual(len(cell), 200)
        self.assertTrue(cell.endswith("\u2026"))

    def test_truncation_applies_to_compact_formats(self):
        long_row = ["north", "1.0", "x" * 300]
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "format": "csv", "maxCellChars": 5}, rows=[long_row])
        self.assertEqual(result["text"], "region,total,note\nnorth,1.0,xxxx\u2026\n")

    def test_include_columns_false_drops_the_column_list(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "includeColumns": False})
        self.assertNotIn("columns", result)
        self.assertEqual(len(result["data"]), 3)

    def test_include_columns_false_also_applies_to_objects(self):
        result, _engine = self.run_tool({"workflow": "wf-1", "node": "1:1",
                                         "format": "objects", "includeColumns": False})
        self.assertNotIn("columns", result)
        self.assertEqual(result["data"][0]["region"], "north")

    # -- argument validation -------------------------------------------------

    def test_invalid_filter_is_rejected_before_http(self):
        for where in ("total > 10; DROP TABLE src", "total -- comment", "1 /* x */",
                      "region IN (SELECT region FROM read_csv('/etc/passwd'))",
                      "FROM src"):
            with self.subTest(where=where):
                with mock.patch.object(self.module, "ensure_service"), \
                        mock.patch.object(self.module, "call") as call:
                    error = tool_error(self.module, "dap_get_table",
                                       {"workflow": "wf-1", "node": "1:1", "where": where})
                self.assertEqual(error["code"], -32602)
                self.assertEqual(error.get("data", {}).get("code"), "invalid_filter")
                call.assert_not_called()

    def test_negative_offset_is_rejected_before_http(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_get_table",
                               {"workflow": "wf-1", "node": "1:1", "offset": -1})
        self.assertEqual(error["code"], -32602)
        self.assertIn("offset", error["message"])
        call.assert_not_called()

    def test_negative_max_cell_chars_is_rejected_before_http(self):
        with mock.patch.object(self.module, "ensure_service"), \
                mock.patch.object(self.module, "call") as call:
            error = tool_error(self.module, "dap_get_table",
                               {"workflow": "wf-1", "node": "1:1", "maxCellChars": -1})
        self.assertEqual(error["code"], -32602)
        call.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
