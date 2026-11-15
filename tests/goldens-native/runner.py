#!/usr/bin/env python3
"""Native golden suite for the DAP engine.

Each workflow in tests/goldens-native/workflows/ is executed on the native
engine; observations are compared with tests/goldens-native/expected.json
(frozen expected outputs) using the rules in docs/QUALITY.md:

  * doubles: abs(a - b) <= 1e-9 * max(1, |a|, |b|); integers exact
  * NULL positions must match exactly; "" is a value, not NULL
  * rows: "ordered" compares positionally, "unordered" as a multiset

Usage:
    python3 tests/goldens-native/runner.py --validate
    python3 tests/goldens-native/runner.py --base http://127.0.0.1:7800
    python3 tests/goldens-native/runner.py --list
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import dap_client as dap

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
BASE_DIR = ROOT / "tests" / "goldens-native"
WORKFLOWS_DIR = BASE_DIR / "workflows"
DATA_DIR = BASE_DIR / "data"
DATASETS_PATH = DATA_DIR / "datasets.json"
EXPECTED_PATH = BASE_DIR / "expected.json"

FLOAT_TOL = 1e-9


class NativeError(RuntimeError):
    """A native workflow could not be bound or executed; the workflow fails."""


# --------------------------------------------------------------------- corpus


def load_datasets():
    return json.loads(DATASETS_PATH.read_text(encoding="utf-8"))


def load_corpus(only=None):
    workflows = []
    for path in sorted(WORKFLOWS_DIR.glob("wf-*.json")):
        workflow = json.loads(path.read_text(encoding="utf-8"))
        if only and not any(workflow["id"].startswith(prefix) for prefix in only):
            continue
        workflows.append(workflow)
    return workflows


ROW_FILTER_OPS = {"eq", "ne", "lt", "le", "gt", "ge", "is_null", "not_null"}
AGGREGATIONS = {"mean", "count", "sum"}
LOGICAL_TYPES = {"table_source", "csv_reader", "excel_reader", "row_filter", "sorter",
                 "group_by", "joiner", "column_filter", "column_renamer", "missing_value",
                 "string_manipulation", "math_formula",
                 "parquet_reader", "json_reader", "csv_writer", "parquet_writer", "json_writer",
                 "type_cast", "duplicate_filter", "value_replace", "column_resorter",
                 "outlier_filter", "date_time", "rule_engine", "binner", "normalizer",
                 "encoder", "sample_rows", "row_aggregator", "pivot", "unpivot", "window",
                 "top_n_per_group", "concatenate",
                 "describe", "value_counts", "correlation", "histogram", "quantile",
                 "outlier_detect", "resample", "rolling", "diff", "date_range", "excel_writer",
                 "markdown_report", "chart"}
CHECK_KINDS = {"table", "profile", "equivalent", "expect", "expect_profile", "file"}


def validate_corpus(workflows, datasets):
    """Structural checks that need no engine; safe to run in CI."""
    problems = []
    seen = set()
    for workflow in workflows:
        workflow_id = workflow.get("id", "?")
        if workflow_id in seen:
            problems.append("%s: duplicate workflow id" % workflow_id)
        seen.add(workflow_id)
        kind = workflow.get("kind")
        if kind not in ("baseline", "native"):
            problems.append("%s: unknown kind %r" % (workflow_id, kind))
        # Baseline workflows compare against expected.json; native workflows compare
        # against literal expectations. A check kind from the wrong side would pass
        # silently, so reject the combination here.
        for check in workflow.get("checks") or []:
            check_kind = check.get("kind")
            if kind == "baseline" and check_kind in ("equivalent", "expect", "expect_profile"):
                problems.append("%s/%s: %r checks are only allowed on native workflows"
                                % (workflow_id, check.get("node"), check_kind))
            if kind == "native" and check_kind in ("table", "profile"):
                problems.append("%s/%s: native workflows need expect/equivalent, not %r"
                                % (workflow_id, check.get("node"), check_kind))
            if check_kind == "expect":
                for column in check.get("columns") or []:
                    if not isinstance(column, dict) or not column.get("type"):
                        problems.append("%s/%s: expect columns need name and type"
                                        % (workflow_id, check.get("node")))
        node_ids = {}
        for node in workflow.get("nodes", []):
            node_id = node.get("id")
            if not node_id:
                problems.append("%s: node without id" % workflow_id)
                continue
            if node_id in node_ids:
                problems.append("%s: duplicate node id %r" % (workflow_id, node_id))
            node_ids[node_id] = node
            node_type = node.get("type")
            if node_type not in LOGICAL_TYPES:
                problems.append("%s/%s: unknown type %r" % (workflow_id, node_id, node_type))
                continue
            config = node.get("config") or {}
            if node_type in ("table_source", "csv_reader", "excel_reader"):
                if config.get("dataset") not in datasets:
                    problems.append("%s/%s: unknown dataset %r" % (workflow_id, node_id, config.get("dataset")))
            elif node_type == "row_filter":
                if not config.get("where"):
                    problems.append("%s/%s: row_filter needs where[]" % (workflow_id, node_id))
                for clause in config.get("where") or []:
                    if clause.get("op") not in ROW_FILTER_OPS:
                        problems.append("%s/%s: unknown op %r" % (workflow_id, node_id, clause.get("op")))
                    if clause.get("op") not in ("is_null", "not_null") and "value" not in clause:
                        problems.append("%s/%s: op %r needs value" % (workflow_id, node_id, clause.get("op")))
                    if not clause.get("column"):
                        problems.append("%s/%s: clause without column" % (workflow_id, node_id))
            elif node_type == "sorter":
                if not config.get("keys"):
                    problems.append("%s/%s: sorter needs keys[]" % (workflow_id, node_id))
                for key in config.get("keys") or []:
                    if key.get("direction", "asc") not in ("asc", "desc"):
                        problems.append("%s/%s: bad sort direction %r" % (workflow_id, node_id, key.get("direction")))
            elif node_type == "group_by":
                if not config.get("keys"):
                    problems.append("%s/%s: group_by needs keys[]" % (workflow_id, node_id))
                for aggregation in config.get("aggregations") or []:
                    if aggregation.get("fn") not in AGGREGATIONS:
                        problems.append("%s/%s: unknown aggregation %r" % (workflow_id, node_id, aggregation.get("fn")))
                    if not aggregation.get("column"):
                        problems.append("%s/%s: aggregation without column" % (workflow_id, node_id))
            elif node_type == "math_formula":
                for output in config.get("outputs") or []:
                    if not output.get("name") or not output.get("expression"):
                        problems.append("%s/%s: formula needs name+expression" % (workflow_id, node_id))
            elif node_type == "missing_value":
                for rule in config.get("rules") or []:
                    if not rule.get("column") or not rule.get("method"):
                        problems.append("%s/%s: missing_value rule needs column+method" % (workflow_id, node_id))
            elif node_type == "column_filter":
                if not (config.get("include") or config.get("exclude")):
                    problems.append("%s/%s: column_filter needs include[] or exclude[]" % (workflow_id, node_id))
            elif node_type == "column_renamer":
                renames = config.get("renames")
                if not isinstance(renames, list):
                    problems.append("%s/%s: column_renamer needs renames[]" % (workflow_id, node_id))
                    continue
                for rename in renames:
                    if not isinstance(rename, dict):
                        problems.append("%s/%s: rename entries must be objects with from+to"
                                        % (workflow_id, node_id))
                    elif not rename.get("from") or not rename.get("to"):
                        problems.append("%s/%s: rename needs from+to" % (workflow_id, node_id))
            elif node_type == "joiner":
                if not config.get("left_keys") or not config.get("right_keys"):
                    problems.append("%s/%s: joiner needs left_keys+right_keys" % (workflow_id, node_id))
        for edge in workflow.get("edges", []):
            for side in ("from", "to"):
                if edge.get(side) not in node_ids:
                    problems.append("%s: edge references unknown node %r" % (workflow_id, edge.get(side)))
        for check in workflow.get("checks", []):
            if check.get("node") not in node_ids:
                problems.append("%s: check references unknown node %r" % (workflow_id, check.get("node")))
            if check.get("kind") not in CHECK_KINDS:
                problems.append("%s: unknown check kind %r" % (workflow_id, check.get("kind")))
            if check.get("kind") == "table" and check.get("order", "unordered") not in ("ordered", "unordered"):
                problems.append("%s: bad order %r" % (workflow_id, check.get("order")))
            if check.get("kind") == "equivalent" and not (check.get("workflow") and check.get("node_ref")):
                problems.append("%s: equivalent check needs workflow+node_ref" % workflow_id)
            if check.get("kind") in ("expect", "expect_profile") and "rows" not in check:
                problems.append("%s: %s check needs rows" % (workflow_id, check.get("kind")))
            if check.get("kind") == "file" and not (check.get("path") and check.get("content") is not None):
                problems.append("%s: file check needs path+content" % workflow_id)
        if not workflow.get("checks"):
            problems.append("%s: workflow has no checks" % workflow_id)
    return problems

# The engine reports node success as EXECUTED (contract, docs/API.md); the job
# state for the same run is succeeded. Accept both spellings here.
NATIVE_OK_STATES = ("EXECUTED", "succeeded")


def _dataset_entry(dataset, datasets):
    entry = datasets.get(dataset)
    if entry is None:
        raise NativeError("unknown dataset %r" % (dataset,))
    return entry


def _dataset_file(entry, dataset, suffix=None):
    path = DATA_DIR / entry["file"]
    if suffix is not None:
        path = path.with_suffix(suffix)
    path = path.resolve()
    if not path.is_file():
        raise NativeError("dataset %r fixture missing: %s" % (dataset, path))
    return str(path)


def _dataset_columns(entry):
    return [{"name": column["name"], "type": column["arrow"]} for column in entry["columns"]]


def native_binding(node_type, config, datasets):
    """Map one corpus node onto a dap.* node id plus its JSON settings.

    table_source/csv_reader/excel_reader carry the corpus dataset shorthand;
    the runner expands it here so the engine only ever sees a real file path.
    """
    if node_type in ("table_source", "csv_reader", "excel_reader"):
        dataset = config.get("dataset")
        entry = _dataset_entry(dataset, datasets)
        if node_type == "excel_reader":
            return "dap.excel_reader", {
                "file": _dataset_file(entry, dataset, ".xlsx"),
                "header": entry.get("header", True),
                "columns": _dataset_columns(entry),
            }
        return "dap.csv_reader", {
            "file": _dataset_file(entry, dataset),
            "delimiter": entry.get("delimiter", ","),
            "header": entry.get("header", True),
            "columns": _dataset_columns(entry),
            "emptyStringIsNull": False,
        }
    return "dap." + node_type, config


# ------------------------------------------------------------------- running


def _check_key(check):
    return "%s:%s" % (check["node"], check["kind"])


# --------------------------------------------------------------- native run


def _native_table(body, order):
    return {"kind": "table", "rows": body.get("rows"),
            "columns": body.get("columns") or [], "data": body.get("data") or [],
            "order": order}


def _native_profile(body):
    return {"kind": "profile", "rows": body.get("rows"),
            "columns": body.get("columns") or []}


def _native_run_failure(api, workflow_id, nodes, response):
    """Describe a non-200 run with the engine error and the failing node id."""
    body = response.body if isinstance(response.body, dict) else {}
    error = body.get("error")
    try:
        states = api.workflow_nodes(workflow_id) or []
    except (dap.ApiError, AssertionError, AttributeError):
        states = []
    logical = {native: node for node, native in nodes.items()}
    failed_states = [state for state in states if state.get("state") not in NATIVE_OK_STATES]
    errored = [state for state in failed_states if state.get("state") == "ERROR"]
    details = []
    for state in errored or failed_states:
        details.append("node %s (%s) state %s: %s" % (
            logical.get(state.get("node"), state.get("node")), state.get("node"),
            state.get("state"), state.get("message") or error or "no message"))
    if details:
        return "; ".join(details)
    node_list = ", ".join("%s (%s)" % (node, native) for node, native in nodes.items())
    return "run failed at %s: HTTP %s %s" % (node_list, response.status,
                                             error or _short(response.body))


def _expected_columns(expected, actual_columns):
    """Expected columns; a type omitted by the check is not asserted."""
    actual_types = {column["name"]: column["type"] for column in actual_columns}
    columns = []
    for entry in expected:
        if isinstance(entry, str):
            columns.append({"name": entry, "type": actual_types.get(entry)})
            continue
        column = dict(entry)
        column["type"] = entry.get("type") or actual_types.get(entry.get("name"))
        columns.append(column)
    return columns


def _expect_limit(check):
    return check.get("limit") or len(check.get("data") or []) or 50


def _native_check(api, workflow_id, workflow, nodes, check, observation,
                  tables, corpus, datasets, needed, running):
    kind = check["kind"]
    if kind not in ("table", "profile") and workflow.get("kind") != "native":
        return  # baseline workflows only compare with expected.json
    logical = check["node"]
    api_node = nodes[logical]
    key = _check_key(check)
    label = "%s/%s" % (workflow["id"], key)
    problems = observation["checkProblems"]

    if kind == "file":
        # Output files are not served by /v1; the runner runs on the engine host, so it
        # compares the written bytes with the pinned golden content.
        path = pathlib.Path(check["path"])
        if not path.is_file():
            problems.append("%s: file %s was not written" % (label, path))
            return
        actual = path.read_text(encoding="utf-8")
        expected = check.get("content")
        if actual != expected:
            problems.append("%s: file %s differs from the pinned content (%d vs %d bytes)"
                            % (label, path, len(actual.encode("utf-8")), len(expected.encode("utf-8"))))
        observation["checks"][key] = {"kind": "file", "path": str(path)}
        return
    if kind == "table":
        body = api.table(workflow_id, api_node, limit=check.get("limit", 50))
        observation["checks"][key] = _native_table(body, check.get("order", "unordered"))
        tables[(workflow["id"], logical)] = observation["checks"][key]
        return
    if kind == "profile":
        observation["checks"][key] = _native_profile(api.profile(workflow_id, api_node))
        return

    if kind == "expect":
        order = check.get("order", "unordered")
        body = api.table(workflow_id, api_node, limit=_expect_limit(check))
        actual_columns = body.get("columns") or []
        if check.get("data") is None:
            if check.get("rows") != body.get("rows"):
                problems.append("%s: rows %r != %r"
                                % (label, body.get("rows"), check.get("rows")))
            wanted = check.get("columns") or []
            if len(wanted) != len(actual_columns):
                problems.append("%s: columns %r" % (label, [c.get("name") for c in actual_columns]))
            else:
                for entry, actual in zip(wanted, actual_columns):
                    name = entry if isinstance(entry, str) else entry.get("name")
                    if name != actual.get("name"):
                        problems.append("%s: column %r != %r" % (label, actual.get("name"), name))
                    elif isinstance(entry, dict) and entry.get("type") and entry["type"] != actual.get("type"):
                        problems.append("%s: column %r type %r != %r"
                                        % (label, name, actual.get("type"), entry["type"]))
        else:
            expected = {"rows": check.get("rows"),
                        "columns": _expected_columns(check.get("columns") or [], actual_columns),
                        "data": check.get("data") or [], "order": order}
            compare_table(label, expected, _native_table(body, order), problems)
        stats = [entry for entry in check.get("columns") or []
                 if isinstance(entry, dict)
                 and any(field in entry for field in ("missing", "distinct", "min", "max", "mean"))]
        if stats:
            body = api.profile(workflow_id, api_node)
            expected = {"rows": check.get("rows"),
                        "columns": _expected_columns(stats, body.get("columns") or [])}
            compare_profile(label, expected, _native_profile(body), problems, declared_only=True)
        return

    if kind == "expect_profile":
        body = api.profile(workflow_id, api_node)
        expected = {"rows": check.get("rows"),
                    "columns": _expected_columns(check.get("columns") or [],
                                                 body.get("columns") or [])}
        compare_profile(label, expected, _native_profile(body), problems, declared_only=True)
        return

    if kind == "equivalent":
        reference_id = check["workflow"]
        reference_node = check["node_ref"]
        reference = corpus.get(reference_id)
        if reference is None:
            raise NativeError("equivalent check references unknown workflow %r" % reference_id)
        current = _native_table(api.table(workflow_id, api_node, limit=100000), "unordered")
        tables[(workflow["id"], logical)] = current
        reference_key = (reference_id, reference_node)
        if reference_key not in tables:
            if reference_id in running:
                raise NativeError("equivalent check cycle: %s -> %s"
                                  % (workflow["id"], reference_id))
            needed.add(reference_key)
            run_on_native(api, reference, corpus, datasets, tables, needed, running)
        if reference_key not in tables:
            raise NativeError("equivalent check: %s/%s has no table"
                              % (reference_id, reference_node))
        compare_table(label, tables[reference_key], current, problems)
        return

    raise NativeError("unknown native check kind %r" % kind)


def run_on_native(api, workflow, corpus, datasets, tables=None, needed=None, running=None):
    """Execute one corpus workflow on dap-native and collect its observations."""
    tables = {} if tables is None else tables
    needed = set() if needed is None else needed
    running = set() if running is None else running
    workflow_name = workflow["id"]
    if workflow_name in running:
        raise NativeError("equivalent check cycle through %r" % workflow_name)
    running.add(workflow_name)
    observation = {"status": "ok", "nodes": {}, "checks": {}, "durationMs": None,
                   "checkProblems": []}
    workflow_id = None
    try:
        workflow_id = api.create_workflow(workflow_name)
        nodes = {}
        for node in workflow["nodes"]:
            factory, settings = native_binding(node["type"], node.get("config") or {}, datasets)
            response = api.add_node(workflow_id, factory, settings)
            if response.status != 200:
                raise NativeError("node %s: add %s failed: HTTP %s %s"
                                  % (node["id"], factory, response.status, _short(response.body)))
            if not isinstance(response.body, dict) or "node" not in response.body:
                raise NativeError("node %s: add %s returned %s"
                                  % (node["id"], factory, _short(response.body)))
            nodes[node["id"]] = response.body["node"]
        for edge in workflow.get("edges", []):
            api.connect_ok(workflow_id, nodes[edge["from"]], nodes[edge["to"]],
                           edge.get("fromPort", 0), edge.get("toPort", 0))
        response = api.run(workflow_id)
        if response.status != 200:
            raise NativeError(_native_run_failure(api, workflow_id, nodes, response))
        body = response.body or {}
        observation["durationMs"] = body.get("durationMs")
        logical = {native: node for node, native in nodes.items()}
        for state in body.get("nodes") or []:
            native_node = state.get("node")
            node_name = logical.get(native_node, native_node)
            observation["nodes"][node_name] = state.get("state")
            if state.get("state") not in NATIVE_OK_STATES:
                raise NativeError("node %s (%s) state %s: %s" % (
                    node_name, native_node, state.get("state"),
                    state.get("message") or "no message"))
        for check in workflow.get("checks", []):
            _native_check(api, workflow_id, workflow, nodes, check, observation,
                          tables, corpus, datasets, needed, running)
        for needed_workflow, needed_node in sorted(needed):
            if needed_workflow != workflow_name or (needed_workflow, needed_node) in tables:
                continue
            if needed_node not in nodes:
                raise NativeError("equivalent check references unknown node %r in %s"
                                  % (needed_node, workflow_name))
            tables[(needed_workflow, needed_node)] = _native_table(
                api.table(workflow_id, nodes[needed_node], limit=100000), "unordered")
    finally:
        running.discard(workflow_name)
        if workflow_id is not None:
            api.delete_workflow(workflow_id)
    return observation


# ---------------------------------------------------------------- comparison


def _short(value, limit=400):
    text = json.dumps(value, ensure_ascii=False, default=str)
    return text if len(text) <= limit else text[:limit] + "..."


def _is_number_type(type_name):
    return type_name in ("Number (Float)", "Number (Integer)", "Number (Long Integer)")


def _numbers_close(a, b):
    if a is None or b is None:
        return a is None and b is None
    return abs(a - b) <= FLOAT_TOL * max(1.0, abs(a), abs(b))


def _as_number(value):
    if value is None:
        return None
    text = str(value).strip()
    if text == "":
        return None
    return float(text)


def _canon(value, type_name):
    if value is None:
        return "\x00null"
    if _is_number_type(type_name):
        number = _as_number(value)
        return "\x00num:%r" % (round(number, 9),) if number is not None else "\x00null"
    return "s:" + str(value)


def _cells_equal(got, want, type_name):
    if _is_number_type(type_name):
        got_number, want_number = _as_number(got), _as_number(want)
        if got_number is None or want_number is None:
            return got_number is None and want_number is None
        return _numbers_close(got_number, want_number)
    return got == want


def compare_table(key, expected, actual, problems):
    if expected["rows"] != actual["rows"]:
        problems.append("%s: rows %r != %r" % (key, actual["rows"], expected["rows"]))
    expected_columns = [(column["name"], column["type"]) for column in expected["columns"]]
    actual_columns = [(column["name"], column["type"]) for column in actual["columns"]]
    if expected_columns != actual_columns:
        problems.append("%s: columns %r != %r" % (key, actual_columns, expected_columns))
        return
    types = [column["type"] for column in expected["columns"]]
    if expected.get("order") == "ordered":
        for row_index, (got, want) in enumerate(zip(actual["data"], expected["data"])):
            for column_index, type_name in enumerate(types):
                if not _cells_equal(got[column_index], want[column_index], type_name):
                    problems.append("%s: row %d column %r: %r != %r"
                                    % (key, row_index, expected_columns[column_index][0],
                                       got[column_index], want[column_index]))
                    if len(problems) > 20:
                        return
    else:
        got_rows = sorted(tuple(_canon(cell, types[i]) for i, cell in enumerate(row))
                          for row in actual["data"])
        want_rows = sorted(tuple(_canon(cell, types[i]) for i, cell in enumerate(row))
                           for row in expected["data"])
        if got_rows != want_rows:
            for row_index, (got, want) in enumerate(zip(got_rows, want_rows)):
                if got != want:
                    problems.append("%s: unordered row %d: %r != %r" % (key, row_index, got, want))
                    break
            else:
                problems.append("%s: row count differs (%d vs %d)"
                                % (key, len(got_rows), len(want_rows)))


def compare_profile(key, expected, actual, problems, declared_only=False):
    if expected["rows"] != actual["rows"]:
        problems.append("%s: rows %r != %r" % (key, actual["rows"], expected["rows"]))
    expected_columns = {column["name"]: column for column in expected["columns"]}
    actual_columns = {column["name"]: column for column in actual["columns"]}
    if not declared_only:
        if sorted(expected_columns) != sorted(actual_columns):
            problems.append("%s: column set %r != %r"
                            % (key, sorted(actual_columns), sorted(expected_columns)))
            return
    for name, want in expected_columns.items():
        got = actual_columns.get(name)
        if got is None:
            problems.append("%s: declared column %r missing from the response" % (key, name))
            continue
        if got.get("type") != want.get("type"):
            problems.append("%s: column %r type %r != %r" % (key, name, got.get("type"), want.get("type")))
        for field in ("missing", "distinct"):
            if want.get(field) is not None and got.get(field) != want.get(field):
                problems.append("%s: column %r %s %r != %r" % (key, name, field, got.get(field), want.get(field)))
        for field in ("min", "max", "mean"):
            if want.get(field) is None:
                continue
            if not _numbers_close(_as_number(got.get(field)), _as_number(want.get(field))):
                problems.append("%s: column %r %s %r != %r" % (key, name, field, got.get(field), want.get(field)))


def compare_observation(workflow_id, expected, actual):
    problems = []
    if expected.get("status") == "skipped" or actual.get("status") == "skipped":
        if expected.get("status") != actual.get("status"):
            problems.append("%s: status %r != %r"
                            % (workflow_id, actual.get("status"), expected.get("status")))
        return problems
    expected_checks = expected.get("checks", {})
    actual_checks = actual.get("checks", {})
    if sorted(expected_checks) != sorted(actual_checks):
        problems.append("%s: check set %r != %r"
                        % (workflow_id, sorted(actual_checks), sorted(expected_checks)))
    for key, want in expected_checks.items():
        got = actual_checks.get(key)
        if got is None:
            continue
        if want["kind"] == "table":
            compare_table("%s/%s" % (workflow_id, key), want, got, problems)
        else:
            compare_profile("%s/%s" % (workflow_id, key), want, got, problems)
    return problems


def _table_cells(expected, actual):
    """Matching/total scalar comparisons of one table check, by docs/QUALITY.md rules."""
    total = 1
    matched = 0
    if actual is not None and actual.get("rows") == expected.get("rows"):
        matched += 1
    expected_columns = expected.get("columns") or []
    actual_columns = (actual or {}).get("columns") or []
    for index in range(max(len(expected_columns), len(actual_columns))):
        for field in ("name", "type"):
            total += 1
            if (index < len(expected_columns) and index < len(actual_columns)
                    and expected_columns[index].get(field) == actual_columns[index].get(field)):
                matched += 1
    schema_ok = len(expected_columns) == len(actual_columns) and all(
        expected_columns[index].get("name") == actual_columns[index].get("name")
        and expected_columns[index].get("type") == actual_columns[index].get("type")
        for index in range(len(expected_columns)))
    expected_data = expected.get("data") or []
    actual_data = (actual or {}).get("data") or []
    total += len(expected_data) * len(expected_columns)
    if actual is None or not schema_ok:
        return matched, total
    types = [column.get("type") for column in expected_columns]
    if expected.get("order") == "ordered":
        for got_row, want_row in zip(actual_data, expected_data):
            for index, type_name in enumerate(types):
                if (index < len(got_row) and index < len(want_row)
                        and _cells_equal(got_row[index], want_row[index], type_name)):
                    matched += 1
    else:
        def canon(row):
            return tuple(_canon(cell, types[index])
                         for index, cell in enumerate(row) if index < len(types))

        try:
            got_rows = sorted(canon(row) for row in actual_data)
            want_rows = sorted(canon(row) for row in expected_data)
        except (TypeError, ValueError):
            return matched, total
        for got_row, want_row in zip(got_rows, want_rows):
            for got_cell, want_cell in zip(got_row, want_row):
                if got_cell == want_cell:
                    matched += 1
    return matched, total


def _profile_cells(expected, actual):
    total = 1
    matched = 0
    if actual is not None and actual.get("rows") == expected.get("rows"):
        matched += 1
    expected_columns = {column["name"]: column for column in expected.get("columns") or []}
    actual_columns = {column["name"]: column for column in (actual or {}).get("columns") or []}
    for name, want in expected_columns.items():
        got = actual_columns.get(name)
        for field in ("type", "missing", "distinct"):
            if want.get(field) is None:
                continue
            total += 1
            if got is not None and got.get(field) == want.get(field):
                matched += 1
        for field in ("min", "max", "mean"):
            if want.get(field) is None:
                continue
            total += 1
            if got is not None and _numbers_close(_as_number(got.get(field)),
                                                  _as_number(want.get(field))):
                matched += 1
    total += sum(1 for name in actual_columns if name not in expected_columns)
    return matched, total


def count_cells(expected_checks, actual_checks):
    """Scalar comparisons the baseline prescribes: rows, columns, cells, stats."""
    matched = total = 0
    for key, expected in expected_checks.items():
        actual = actual_checks.get(key)
        if expected.get("kind") == "table":
            workflow_cells = _table_cells(expected, actual)
        else:
            workflow_cells = _profile_cells(expected, actual)
        matched += workflow_cells[0]
        total += workflow_cells[1]
    return matched, total


def run_goldens(api, workflows, verbose=False):
    datasets = load_datasets()
    corpus = {workflow["id"]: workflow for workflow in load_corpus()}
    baseline = json.loads(EXPECTED_PATH.read_text(encoding="utf-8"))
    expected_workflows = baseline.get("workflows", {})
    tables = {}
    problems = []
    matched = total = 0
    ok_count = failed_count = 0
    for workflow in workflows:
        workflow_id = workflow["id"]
        expected = expected_workflows.get(workflow_id)
        native_only = workflow.get("kind") == "native"
        observation = None
        workflow_problems = []
        try:
            observation = run_on_native(api, workflow, corpus, datasets, tables)
        except (NativeError, dap.ApiError, AssertionError) as exc:
            workflow_problems.append("%s: %s" % (workflow_id, exc))
        if observation is not None:
            if native_only:
                workflow_problems.extend("%s: %s" % (workflow_id, problem)
                                         for problem in observation.get("checkProblems", []))
            elif expected is None:
                workflow_problems.append("%s: no baseline observation" % workflow_id)
            else:
                expected_view = dict(expected)
                actual_view = {"status": observation.get("status"),
                               "checks": observation.get("checks", {}),
                               "durationMs": observation.get("durationMs")}
                workflow_problems.extend(
                    compare_observation(workflow_id, expected_view, actual_view))
                matched_cells, workflow_cells = count_cells(
                    expected.get("checks", {}), observation.get("checks", {}))
                matched += matched_cells
                total += workflow_cells
        elif not native_only and expected is not None:
            total += count_cells(expected.get("checks", {}), {})[1]
        if workflow_problems:
            failed_count += 1
            problems.extend(workflow_problems)
            print("FAIL  %-28s %s" % (workflow_id, workflow_problems[0]))
        else:
            ok_count += 1
            print("ok    %-28s %sms  checks=%d"
                  % (workflow_id, observation.get("durationMs"), len(workflow.get("checks", []))))
        if verbose:
            for problem in workflow_problems:
                print("  " + problem)
    if problems:
        print("\n%d golden problem(s):" % len(problems))
        for problem in problems[:60]:
            print("  " + problem)
    percent = 100.0 * matched / total if total else 0.0
    print("\nnative goldens: %d workflows ok, %d failed; "
          "expected cells %d/%d (%.1f%%)"
          % (ok_count, failed_count, matched, total, percent))
    return 1 if failed_count else 0




# ----------------------------------------------------------------------- CLI


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", default="http://127.0.0.1:7800", help="engine base URL")
    parser.add_argument("--session", default="dap-goldens", help="X-DAP-Session value")
    parser.add_argument("--only", action="append", metavar="PREFIX",
                        help="limit to workflow ids starting with PREFIX")
    parser.add_argument("--list", action="store_true", help="list workflows and exit")
    parser.add_argument("--validate", action="store_true",
                        help="check workflow structure without an engine")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    workflows = load_corpus(args.only)
    if args.list:
        for workflow in workflows:
            print("%-28s %-11s %s" % (workflow["id"], workflow["kind"], workflow["title"]))
        return 0
    if not workflows:
        print("no workflows matched", file=sys.stderr)
        return 2
    if args.validate:
        problems = validate_corpus(load_corpus(), load_datasets())
        for line in problems:
            print("FAIL  " + line)
        print("goldens: %d workflows, %d problem(s)" % (len(workflows), len(problems)))
        return 1 if problems else 0
    api = dap.Api(args.base, session=args.session)
    api.health()
    return run_goldens(api, workflows, args.verbose)


if __name__ == "__main__":
    sys.exit(main())
