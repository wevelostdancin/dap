#!/usr/bin/env python3
"""Unit tests for the native half of tests/parity.py (stdlib unittest).

The engine itself is exercised by the black-box run documented in PARITY.md;
these tests pin the runner contract with a fake /v1 client: dataset shorthand,
binding bodies, error messages and the native-only check semantics.
"""

from __future__ import annotations

import copy
import contextlib
import io
import json
import pathlib
import re
import sys
import unittest
from unittest import mock

TESTS_DIR = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS_DIR))

import parity  # noqa: E402

DATASETS = parity.load_datasets()
CORPUS = {workflow["id"]: workflow for workflow in parity.load_corpus()}
BASELINE = json.loads(parity.BASELINE_PATH.read_text(encoding="utf-8"))


class FakeResponse:
    def __init__(self, status, body):
        self.status = status
        self.body = body


class FakeApi:
    """In-memory stand-in for dap_client.Api used by run_on_native."""

    def __init__(self):
        self.names = {}
        self.nodes = {}
        self.states = {}
        self.tables = {}
        self.profiles = {}
        self.added = []
        self.connected = []
        self.runs = []
        self.run_failure = None
        self.add_failure = None
        self.counter = 0

    # ------------------------------------------------------------ transport
    def health(self):
        return {"ok": True}

    def create_workflow(self, name):
        self.counter += 1
        workflow_id = "wf-%d" % self.counter
        self.names[workflow_id] = name
        self.nodes[workflow_id] = []
        return workflow_id

    def add_node(self, workflow_id, factory, settings=None):
        if self.add_failure is not None:
            return FakeResponse(*self.add_failure)
        node_id = "1:%d" % (len(self.nodes[workflow_id]) + 1)
        self.nodes[workflow_id].append((node_id, factory))
        self.added.append((self.names[workflow_id], factory, settings))
        return FakeResponse(200, {"node": node_id, "name": factory})

    def connect_ok(self, workflow_id, source, target, source_port=0, target_port=0):
        self.connected.append((self.names[workflow_id], source, target, target_port))
        return {"ok": True}

    def delete_workflow(self, workflow_id):
        self.names.pop(workflow_id, None)

    def run(self, workflow_id):
        self.runs.append(self.names[workflow_id])
        if self.run_failure is not None:
            return FakeResponse(*self.run_failure)
        return FakeResponse(200, {"ok": True, "durationMs": 3,
                                  "workflow": workflow_id,
                                  "nodes": self.workflow_nodes(workflow_id)})

    def workflow_nodes(self, workflow_id):
        name = self.names[workflow_id]
        states = self.states.get(name)
        if states is not None:
            return copy.deepcopy(states)
        return [{"node": node_id, "name": factory, "state": "EXECUTED"}
                for node_id, factory in self.nodes[workflow_id]]

    def table(self, workflow_id, node, limit=50, port=0):
        name = self.names[workflow_id]
        factory = dict(self.nodes[workflow_id])[node]
        return self.tables.get((name, factory),
                               {"rows": 0, "columns": [], "data": [], "preview": 0})

    def profile(self, workflow_id, node):
        name = self.names[workflow_id]
        factory = dict(self.nodes[workflow_id])[node]
        return self.profiles.get((name, factory), {"rows": 0, "columns": []})


def column(name, type_name):
    return {"index": 0, "name": name, "type": type_name}


def table_body(rows, columns, data):
    return {"rows": rows, "columns": columns, "data": data, "preview": len(data)}


class BindingTest(unittest.TestCase):
    def test_table_source_expands_dataset_into_csv_reader(self):
        factory, settings = parity.native_binding("table_source", {"dataset": "IRIS"}, DATASETS)
        self.assertEqual("dap.csv_reader", factory)
        self.assertEqual(str((parity.CORPUS_DIR / "data" / "iris.csv").resolve()), settings["file"])
        self.assertEqual(",", settings["delimiter"])
        self.assertIs(True, settings["header"])
        self.assertIs(False, settings["emptyStringIsNull"])
        self.assertEqual({"name": "sepal_length", "type": "Float64"}, settings["columns"][0])
        self.assertEqual({"name": "species", "type": "Utf8"}, settings["columns"][-1])
        self.assertEqual(5, len(settings["columns"]))

    def test_csv_reader_uses_the_same_dataset_shorthand(self):
        factory, settings = parity.native_binding("csv_reader", {"dataset": "IRIS"}, DATASETS)
        self.assertEqual("dap.csv_reader", factory)
        self.assertEqual(str((parity.CORPUS_DIR / "data" / "iris.csv").resolve()), settings["file"])

    def test_excel_reader_switches_to_xlsx_and_omits_sheet(self):
        factory, settings = parity.native_binding("excel_reader", {"dataset": "IRIS"}, DATASETS)
        self.assertEqual("dap.excel_reader", factory)
        self.assertEqual(str((parity.CORPUS_DIR / "data" / "iris.xlsx").resolve()), settings["file"])
        self.assertNotIn("sheet", settings)
        self.assertIs(True, settings["header"])
        self.assertEqual({"name": "species", "type": "Utf8"}, settings["columns"][-1])

    def test_unknown_dataset_is_a_loud_failure(self):
        with self.assertRaisesRegex(parity.NativeError, "NOPE"):
            parity.native_binding("table_source", {"dataset": "NOPE"}, DATASETS)

    def test_missing_fixture_is_a_loud_failure(self):
        datasets = {"IRIS": dict(DATASETS["IRIS"], file="no-such-file.csv")}
        with self.assertRaisesRegex(parity.NativeError, "no-such-file.csv"):
            parity.native_binding("table_source", {"dataset": "IRIS"}, datasets)

    def test_other_nodes_pass_their_config_through(self):
        config = {"where": [{"column": "species", "op": "eq", "value": "setosa"}]}
        factory, settings = parity.native_binding("row_filter", config, DATASETS)
        self.assertEqual("dap.row_filter", factory)
        self.assertEqual(config, settings)


class ExecuteTest(unittest.TestCase):
    def test_nodes_are_bound_with_settings_and_edges_keep_their_ports(self):
        api = FakeApi()
        observation = parity.run_on_native(api, CORPUS["wf-07-adult-join"], CORPUS, DATASETS)
        self.assertEqual("ok", observation["status"])
        self.assertIn(("wf-07-adult-join", "dap.csv_reader",
                       parity.native_binding("table_source", {"dataset": "ADULT"}, DATASETS)[1]), api.added)
        joiner_settings = [settings for name, factory, settings in api.added
                           if name == "wf-07-adult-join" and factory == "dap.joiner"][0]
        self.assertEqual(["education"], joiner_settings["left_keys"])
        ports = [(target, port) for name, source, target, port in api.connected
                 if name == "wf-07-adult-join"]
        self.assertIn(("1:3", 1), ports)
        self.assertEqual(["wf-07-adult-join"], api.runs)

    def test_non_200_run_reports_the_node_and_the_engine_error(self):
        api = FakeApi()
        api.run_failure = (500, {"state": "failed", "error": "dap.group_by is not implemented yet"})
        api.states["wf-05-iris-math"] = [
            {"node": "1:3", "name": "Group By", "state": "ERROR",
             "message": "dap.group_by is not implemented yet"},
        ]
        with self.assertRaises(parity.NativeError) as raised:
            parity.run_on_native(api, CORPUS["wf-05-iris-math"], CORPUS, DATASETS)
        message = str(raised.exception)
        self.assertIn("node g (1:3)", message)
        self.assertIn("ERROR", message)
        self.assertIn("dap.group_by is not implemented yet", message)

    def test_run_with_a_failed_node_state_fails_the_workflow(self):
        api = FakeApi()
        api.states["wf-01-iris-filter-sort"] = [
            {"node": "1:1", "name": "CSV Reader", "state": "EXECUTED"},
            {"node": "1:2", "name": "Row Filter", "state": "EXECUTED"},
            {"node": "1:3", "name": "Sorter", "state": "ERROR", "message": "boom"},
        ]
        with self.assertRaises(parity.NativeError) as raised:
            parity.run_on_native(api, CORPUS["wf-01-iris-filter-sort"], CORPUS, DATASETS)
        self.assertIn("node sort (1:3)", str(raised.exception))
        self.assertIn("boom", str(raised.exception))

    def test_non_200_add_node_reports_the_logical_node(self):
        api = FakeApi()
        api.add_failure = (400, {"error": "unknown setting 'file'"})
        with self.assertRaises(parity.NativeError) as raised:
            parity.run_on_native(api, CORPUS["wf-09-empty-result"], CORPUS, DATASETS)
        self.assertIn("node src", str(raised.exception))
        self.assertIn("unknown setting 'file'", str(raised.exception))


class ExpectTest(unittest.TestCase):
    def _iris_math_api(self):
        api = FakeApi()
        api.tables[("wf-05-iris-math", "dap.sorter")] = table_body(3,
            [column("species", "String"), column("Mean(petal_area)", "Number (Float)")],
            [["setosa", "0.3628"], ["versicolor", "5.7204"], ["virginica", "11.2962"]])
        api.profiles[("wf-05-iris-math", "dap.math_formula")] = {
            "rows": 150,
            "columns": [{"name": "petal_area", "type": "Number (Float)", "missing": 0,
                         "distinct": 101, "min": 0.11, "max": 15.87,
                         "mean": 5.7931333333333335}],
        }
        return api

    def test_expect_and_expect_profile_pass_on_golden_values(self):
        observation = parity.run_on_native(self._iris_math_api(), CORPUS["wf-05-iris-math"],
                                           CORPUS, DATASETS)
        self.assertEqual([], observation["checkProblems"])

    def test_expect_reports_a_wrong_data_cell(self):
        api = self._iris_math_api()
        api.tables[("wf-05-iris-math", "dap.sorter")]["data"][0][1] = "0.5"
        observation = parity.run_on_native(api, CORPUS["wf-05-iris-math"], CORPUS, DATASETS)
        self.assertTrue(any("Mean(petal_area)" in problem and "0.5" in problem
                            for problem in observation["checkProblems"]))

    def test_expect_profile_reports_a_wrong_statistic(self):
        api = self._iris_math_api()
        api.profiles[("wf-05-iris-math", "dap.math_formula")]["columns"][0]["mean"] = 5.8
        observation = parity.run_on_native(api, CORPUS["wf-05-iris-math"], CORPUS, DATASETS)
        self.assertTrue(any("petal_area" in problem and "mean" in problem
                            for problem in observation["checkProblems"]))

    def test_expect_without_data_checks_rows_and_column_types(self):
        api = FakeApi()
        api.tables[("wf-01-iris-filter-sort", "dap.sorter")] = table_body(
            50, [column("species", "String")], [])
        workflow = copy.deepcopy(CORPUS["wf-01-iris-filter-sort"])
        workflow["kind"] = "native-only"
        workflow["checks"] = [{"node": "sort", "kind": "expect", "rows": 50,
                               "columns": [{"name": "species", "type": "String"}]}]
        observation = parity.run_on_native(api, workflow, CORPUS, DATASETS)
        self.assertEqual([], observation["checkProblems"])
        workflow["checks"][0]["columns"][0]["type"] = "Number (Integer)"
        observation = parity.run_on_native(api, workflow, CORPUS, DATASETS)
        self.assertTrue(any("type" in problem for problem in observation["checkProblems"]))

    def test_expect_profile_accepts_name_only_columns(self):
        api = FakeApi()
        api.profiles[("wf-10-iris-pipeline", "dap.column_filter")] = {
            "rows": 122,
            "columns": [{"name": "species", "type": "String", "missing": 0, "distinct": 3},
                        {"name": "sepal_total", "type": "Number (Float)", "missing": 0,
                         "distinct": 122}],
        }
        observation = parity.run_on_native(api, CORPUS["wf-10-iris-pipeline"], CORPUS, DATASETS)
        self.assertFalse(any("expect_profile" in problem
                             for problem in observation["checkProblems"]))
        api.profiles[("wf-10-iris-pipeline", "dap.column_filter")]["rows"] = 121
        observation = parity.run_on_native(api, CORPUS["wf-10-iris-pipeline"], CORPUS, DATASETS)
        self.assertTrue(any("121" in problem for problem in observation["checkProblems"]))

    def test_expect_profile_ignores_extra_response_columns(self):
        api = FakeApi()
        api.profiles[("wf-10-iris-pipeline", "dap.column_filter")] = {
            "rows": 122,
            "columns": [{"name": "species", "type": "String", "missing": 0, "distinct": 3},
                        {"name": "sepal_total", "type": "Number (Float)", "missing": 0,
                         "distinct": 122},
                        {"name": "sepal_length", "type": "Number (Float)", "missing": 0,
                         "distinct": 35}],
        }
        observation = parity.run_on_native(api, CORPUS["wf-10-iris-pipeline"], CORPUS, DATASETS)
        self.assertFalse(any("expect_profile" in problem
                             for problem in observation["checkProblems"]))

    def test_expect_profile_fails_when_a_declared_column_is_missing(self):
        api = FakeApi()
        api.profiles[("wf-10-iris-pipeline", "dap.column_filter")] = {
            "rows": 122,
            "columns": [{"name": "species", "type": "String", "missing": 0, "distinct": 3}],
        }
        observation = parity.run_on_native(api, CORPUS["wf-10-iris-pipeline"], CORPUS, DATASETS)
        self.assertTrue(any("expect_profile" in problem and "sepal_total" in problem
                            for problem in observation["checkProblems"]))


class EquivalentTest(unittest.TestCase):
    GROUP_BY = table_body(3,
        [column("species", "String"), column("Mean(sepal_length)", "Number (Float)")],
        [["setosa", "5.006"], ["versicolor", "5.936"], ["virginica", "6.588"]])

    def _api(self):
        api = FakeApi()
        for name in ("wf-02-iris-groupby", "wf-n1-csv-source", "wf-n2-excel-source"):
            api.tables[(name, "dap.group_by")] = copy.deepcopy(self.GROUP_BY)
        return api

    def test_equivalent_runs_the_referenced_workflow(self):
        api = self._api()
        observation = parity.run_on_native(api, CORPUS["wf-n1-csv-source"], CORPUS, DATASETS)
        self.assertEqual([], observation["checkProblems"])
        self.assertIn("wf-02-iris-groupby", api.runs)

    def test_equivalent_reports_a_mismatch(self):
        api = self._api()
        api.tables[("wf-n1-csv-source", "dap.group_by")]["data"][1][1] = "5.0"
        observation = parity.run_on_native(api, CORPUS["wf-n1-csv-source"], CORPUS, DATASETS)
        self.assertTrue(any("equivalent" in problem for problem in observation["checkProblems"]))

    def test_equivalent_chain_fetches_the_referenced_node_before_deleting(self):
        api = self._api()
        observation = parity.run_on_native(api, CORPUS["wf-n2-excel-source"], CORPUS, DATASETS)
        self.assertEqual([], observation["checkProblems"])
        self.assertIn("wf-n1-csv-source", api.runs)

    def test_equivalent_fetches_a_referenced_node_without_its_own_check(self):
        api = self._api()
        corpus = dict(CORPUS)
        reference = copy.deepcopy(CORPUS["wf-02-iris-groupby"])
        reference["checks"] = [check for check in reference["checks"] if check["node"] != "g"]
        corpus["wf-02-iris-groupby"] = reference
        observation = parity.run_on_native(api, CORPUS["wf-n1-csv-source"], corpus, DATASETS)
        self.assertEqual([], observation["checkProblems"])

    def test_equivalent_of_an_unknown_workflow_fails_loudly(self):
        api = FakeApi()
        api.tables[("wf-n1-csv-source", "dap.group_by")] = copy.deepcopy(self.GROUP_BY)
        workflow = copy.deepcopy(CORPUS["wf-n1-csv-source"])
        workflow["checks"][0]["workflow"] = "wf-missing"
        with self.assertRaisesRegex(parity.NativeError, "wf-missing"):
            parity.run_on_native(api, workflow, CORPUS, DATASETS)


class CellMetricTest(unittest.TestCase):
    def test_table_cells_are_counted_per_field(self):
        check = {"kind": "table", "rows": 1,
                 "columns": [column("a", "Number (Integer)")],
                 "data": [["1"]], "order": "ordered"}
        expected = {"x:table": check}
        self.assertEqual((4, 4), parity.count_cells(expected, expected))
        wrong = {"x:table": dict(check, data=[["2"]])}
        self.assertEqual((3, 4), parity.count_cells(expected, wrong))
        self.assertEqual((0, 4), parity.count_cells(expected, {}))

    def test_profile_cells_cover_rows_type_and_statistics(self):
        check = {"kind": "profile", "rows": 1,
                 "columns": [{"name": "a", "type": "Number (Integer)",
                              "missing": 0, "distinct": 1}]}
        expected = {"x:profile": check}
        self.assertEqual((4, 4), parity.count_cells(expected, expected))
        wrong = {"x:profile": {"rows": 2, "columns": [
            {"name": "a", "type": "Number (Integer)", "missing": 0, "distinct": 2}]}}
        self.assertEqual((2, 4), parity.count_cells(expected, wrong))


class CliTest(unittest.TestCase):
    def _api(self):
        expected = BASELINE["workflows"]["wf-09-empty-result"]["checks"]
        api = FakeApi()
        table = expected["filter:table"]
        api.tables[("wf-09-empty-result", "dap.row_filter")] = {
            "rows": table["rows"], "columns": table["columns"],
            "data": table["data"], "preview": len(table["data"])}
        profile = expected["filter:profile"]
        api.profiles[("wf-09-empty-result", "dap.row_filter")] = {
            "rows": profile["rows"], "columns": profile["columns"]}
        return api

    def _run(self, api, argv):
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.object(parity.dap, "Api", lambda base, session="default": api):
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = parity.main(argv)
        return code, stdout.getvalue(), stderr.getvalue()

    def test_native_run_prints_cells_and_exits_zero_when_the_baseline_matches(self):
        code, stdout, _ = self._run(self._api(),
                                    ["--engine", "native", "--only", "wf-09",
                                     "--base", "http://fake"])
        self.assertEqual(0, code)
        self.assertRegex(stdout, r"ok\s+wf-09-empty-result")
        self.assertRegex(stdout, r"matching cells 17/17 \(100\.0%\)")
        self.assertNotIn("FAIL", stdout)

    def test_native_run_exits_one_and_names_the_workflow_on_mismatch(self):
        api = self._api()
        api.tables[("wf-09-empty-result", "dap.row_filter")]["rows"] = 3
        code, stdout, _ = self._run(api, ["--engine", "native", "--only", "wf-09",
                                          "--base", "http://fake"])
        self.assertEqual(1, code)
        self.assertRegex(stdout, r"FAIL\s+wf-09-empty-result")
        self.assertIn("parity problem", stdout)

    def test_native_run_refuses_to_write_a_baseline(self):
        before = parity.BASELINE_PATH.read_bytes()
        code, _, stderr = self._run(FakeApi(), ["--engine", "native", "--write-baseline"])
        self.assertEqual(2, code)
        self.assertIn("baseline", stderr)
        self.assertEqual(before, parity.BASELINE_PATH.read_bytes())


if __name__ == "__main__":
    unittest.main(verbosity=2)
