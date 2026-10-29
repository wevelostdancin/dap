#!/usr/bin/env python3
"""Dual-run parity harness for the DAP corpus.

The corpus (tests/corpus/*.json) is engine-neutral: each workflow lists logical
nodes (row_filter, group_by, ...) with a small config vocabulary. This file
binds those logical nodes to two backends:

    oracle  -> the frozen KNIME engine (factory + settings XML delta)
    native  -> dap-native (node id + configSchema JSON)

Phase 0 ships the oracle side: it records tests/baseline.json and proves the
baseline is reproducible. Observations are stored in the *native vocabulary*:
the oracle binding normalises names and types (KNIME GroupBy naming, count
column type), so the baseline is the semantic contract native has to meet.

Usage:
    python3 tests/parity.py --validate
    python3 tests/parity.py --engine oracle --write-baseline
    python3 tests/parity.py --engine oracle --check-baseline
    python3 tests/parity.py --engine oracle --check-baseline --only wf-02
    python3 tests/parity.py --list

Comparison rules live in docs/PARITY.md and are implemented here:
  * doubles: abs(a - b) <= 1e-9 * max(1, |a|, |b|); integers exact
  * NULL positions must match exactly; "" is a value, not NULL
  * rows: "ordered" compares positionally, "unordered" as a multiset after
    canonicalising numbers to 9 decimal places
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

import dap_client as dap

ROOT = pathlib.Path(__file__).resolve().parent.parent
CORPUS_DIR = ROOT / "tests" / "corpus"
BASELINE_PATH = ROOT / "tests" / "baseline.json"
DATASETS_PATH = CORPUS_DIR / "data" / "datasets.json"

FLOAT_TOL = 1e-9
CORPUS_VERSION = 1

# oracle factories (KNIME 5.12); see docs/PARITY.md for why these and not the
# "obvious" ones: row3.RowFilter predicates by column are broken in this build,
# GroupBy aggregations are unreachable through the settings merge, Missing
# Value and the formula nodes cannot be configured at all.
READ_DATASET = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
RULE_ENGINE_FILTER = "org.knime.base.node.rules.engine.RuleEngineFilterNodeFactory"
RULE_ENGINE = "org.knime.base.node.rules.engine.RuleEngineNodeFactory"
SORTER = "org.knime.base.node.preproc.sorter.SorterNodeFactory"
ROW_AGGREGATOR = "org.knime.base.node.preproc.rowagg.RowAggregatorNodeFactory"
JOINER3 = "org.knime.base.node.preproc.joiner3.Joiner3NodeFactory"
COLUMN_FILTER = "org.knime.base.node.preproc.filter.column.FilterColumnNodeFactory"
STRING_MANIPULATION = "org.knime.base.node.preproc.stringmanipulation.StringManipulationNodeFactory"

AGGREGATION_METHOD = {"mean": "AVERAGE", "count": "COUNT", "sum": "SUM"}
AGGREGATION_NAME = {"mean": "Mean(%s)", "count": "Count(%s)", "sum": "Sum(%s)"}
COUNT_COLUMN = "OCCURRENCE_COUNT"


class Unsupported(Exception):
    """The oracle cannot express this corpus node; the workflow is skipped."""


# --------------------------------------------------------------------- corpus


def load_datasets():
    return json.loads(DATASETS_PATH.read_text(encoding="utf-8"))


def load_corpus(only=None):
    workflows = []
    for path in sorted(CORPUS_DIR.glob("wf-*.json")):
        workflow = json.loads(path.read_text(encoding="utf-8"))
        if only and not any(workflow["id"].startswith(prefix) for prefix in only):
            continue
        workflows.append(workflow)
    return workflows


ROW_FILTER_OPS = {"eq", "ne", "lt", "le", "gt", "ge", "is_null", "not_null"}
AGGREGATIONS = {"mean", "count", "sum"}
LOGICAL_TYPES = {"table_source", "csv_reader", "excel_reader", "row_filter", "sorter",
                 "group_by", "joiner", "column_filter", "missing_value",
                 "string_manipulation", "math_formula"}
CHECK_KINDS = {"table", "profile", "equivalent", "expect", "expect_profile"}


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
        if kind not in ("dual-run", "native-only"):
            problems.append("%s: unknown kind %r" % (workflow_id, kind))
        if kind == "native-only" and not workflow.get("oracle_skip_reason"):
            problems.append("%s: native-only workflow needs oracle_skip_reason" % workflow_id)
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
        if not workflow.get("checks"):
            problems.append("%s: workflow has no checks" % workflow_id)
    return problems


# ----------------------------------------------------------------- bindings


class Binding:
    """One oracle node plus the logical schema native is expected to produce.

    logical is the column list in the *native* vocabulary (KNIME type names,
    KNIME GroupBy naming). column_map translates native column names back to
    the physical names the oracle node expects, which is how corpus configs stay
    engine-neutral.
    """

    def __init__(self, factory, settings, logical=None, column_map=None):
        self.factory = factory
        self.settings = settings or {}
        self.logical = logical
        self.column_map = column_map or {}

    def translate(self, column):
        return self.column_map.get(column, column)

    def normalize(self, columns):
        """Map observed oracle columns onto the logical schema (by position)."""
        if self.logical is None or len(self.logical) != len(columns):
            return columns
        result = []
        for observed, logical in zip(columns, self.logical):
            result.append({"index": observed.get("index"), "name": logical["name"],
                           "type": logical["type"]})
        return result


def _dataset_columns(dataset):
    return [{"name": column["name"], "type": column["knime"]}
            for column in load_datasets()[dataset]["columns"]]


def _identity_map(columns):
    return {column["name"]: column["name"] for column in columns}


def _scalar_literal(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return repr(value) if isinstance(value, float) else str(value)
    return '"%s"' % str(value).replace('"', '""')


def _rule_clause(clause, translate):
    column = translate(clause["column"])
    op = clause["op"]
    if op == "is_null":
        # KNIME rule engine has no MISSING operator; "*" matches every
        # non-missing string, so NOT LIKE "*" selects NULLs.
        return 'NOT $%s$ LIKE "*"' % column
    if op == "not_null":
        return '$%s$ LIKE "*"' % column
    operators = {"eq": "=", "lt": "<", "le": "<=", "gt": ">", "ge": ">="}
    if op not in operators:
        raise Unsupported("KNIME rule engine has no operator for %r" % op)
    return "$%s$ %s %s" % (column, operators[op], _scalar_literal(clause["value"]))


def bind_table_source(config, upstream):
    columns = _dataset_columns(config["dataset"])
    return Binding(READ_DATASET, {"dataset": config["dataset"]},
                   logical=columns, column_map=_identity_map(columns))


def _passthrough(config, upstream):
    source = upstream.get(0)
    if source is None:
        raise Unsupported("pass-through node without an input binding")
    return {"logical": source.logical, "column_map": dict(source.column_map)}


def bind_row_filter(config, upstream):
    inherited = _passthrough(config, upstream)
    translate = upstream[0].translate
    clauses = [_rule_clause(clause, translate) for clause in config["where"]]
    logic = (config.get("logic") or "and").upper()
    rule = (" %s " % logic).join(clauses) + " => TRUE"
    return Binding(RULE_ENGINE_FILTER, {"rules": [rule]}, **inherited)


def bind_sorter(config, upstream):
    inherited = _passthrough(config, upstream)
    translate = upstream[0].translate
    criteria = []
    for key in config["keys"]:
        criteria.append({
            "columnV2": {"regularChoice": translate(key["column"]),
                         "specialChoice_Internals": "ROW_ID"},
            "sortingOrder": "DESCENDING" if key.get("direction") == "desc" else "ASCENDING",
            "stringComparison": "NATURAL",
        })
    return Binding(SORTER, {"sortingCriteria": criteria}, **inherited)


def bind_group_by(config, upstream):
    source = upstream.get(0)
    if source is None:
        raise Unsupported("group_by without an input binding")
    keys = config["keys"]
    aggregations = config.get("aggregations") or []
    if len(keys) != 1:
        raise Unsupported("oracle Row Aggregator takes exactly one category column")
    if len(aggregations) != 1:
        raise Unsupported("oracle cannot express multiple GroupBy aggregations through /v1")
    aggregation = aggregations[0]
    fn, column = aggregation["fn"], aggregation["column"]
    settings = {
        "categoryColumnV2": {"regularChoice": keys[0], "specialChoice_Internals": "NONE"},
        "aggregationMethod": AGGREGATION_METHOD[fn],
        "frequencyColumns": {"mode": "WILDCARD", "patternFilter": {"pattern": column}},
    }
    logical = []
    for key in keys:
        match = [c for c in (source.logical or []) if c["name"] == key]
        logical.append({"name": key, "type": match[0]["type"] if match else "String"})
    if fn == "count":
        output_type = "Number (Integer)"
        physical = COUNT_COLUMN
    else:
        output_type = "Number (Float)" if fn == "mean" else _aggregation_type(source, column)
        physical = column
    logical.append({"name": AGGREGATION_NAME[fn] % column, "type": output_type})
    return Binding(ROW_AGGREGATOR, settings, logical=logical,
                   column_map={AGGREGATION_NAME[fn] % column: physical})


def _aggregation_type(source, column):
    match = [c for c in (source.logical or []) if c["name"] == column]
    return match[0]["type"] if match else "Number (Float)"


def bind_joiner(config, upstream):
    left, right = upstream.get(0), upstream.get(1)
    if left is None or right is None:
        raise Unsupported("joiner needs both input branches bound")
    criteria = []
    for left_key, right_key in zip(config["left_keys"], config["right_keys"]):
        criteria.append({
            "leftTableColumnV2": {"regularChoice": left.translate(left_key),
                                  "specialChoice_Internals": "ROW_ID"},
            "rightTableColumnV2": {"regularChoice": right.translate(right_key),
                                   "specialChoice_Internals": "ROW_ID"},
        })
    settings = {
        "matchingCriteria": criteria,
        "compositionMode": "MATCH_ALL",
        "includeMatchesInOutput": True,
        "includeLeftUnmatchedInOutput": config.get("how") == "left",
        "includeRightUnmatchedInOutput": False,
        "mergeJoinColumns": bool(config.get("merge_keys")),
        "dataCellComparisonMode": "STRICT",
        "duplicateHandling": "APPEND_SUFFIX",
        "suffix": " (Right)",
        "leftColumnSelectionConfigV2": {"mode": "MANUAL",
                                        "manualFilter": {"includeUnknownColumns": True}},
        "rightColumnSelectionConfigV2": {"mode": "MANUAL",
                                         "manualFilter": {"includeUnknownColumns": True}},
    }
    logical = list(left.logical or [])
    column_map = dict(left.column_map)
    right_keys = set(config["right_keys"])
    for column in right.logical or []:
        if column["name"] in right_keys:
            continue
        logical.append({"name": column["name"], "type": column["type"]})
        column_map.setdefault(column["name"], column["name"])
    return Binding(JOINER3, settings, logical=logical, column_map=column_map)


def bind_column_filter(config, upstream):
    inherited = _passthrough(config, upstream)
    if config.get("include"):
        raise Unsupported("FilterColumnNodeFactory can only exclude columns")
    translate = upstream[0].translate
    excluded = {translate(column) for column in config["exclude"]}
    logical = [column for column in (inherited["logical"] or [])
               if column["name"] not in excluded]
    column_map = {name: physical for name, physical in inherited["column_map"].items()
                  if name not in excluded}
    return Binding(COLUMN_FILTER, {"exclude": sorted(excluded)},
                   logical=logical, column_map=column_map)


def bind_missing_value(config, upstream):
    inherited = _passthrough(config, upstream)
    translate = upstream[0].translate
    rules = config.get("rules") or []
    if len(rules) != 1 or rules[0].get("method") != "constant":
        raise Unsupported("oracle Rule Engine can only fill NULLs with a constant, one column at a time")
    column = translate(rules[0]["column"])
    literal = _scalar_literal(rules[0]["value"])
    settings = {
        "rules": ['$%s$ LIKE "*" => $%s$' % (column, column), "TRUE => %s" % literal],
        "replace-column-name": column,
        "append-column": False,
    }
    return Binding(RULE_ENGINE, settings, **inherited)


def bind_string_manipulation(config, upstream):
    inherited = _passthrough(config, upstream)
    translate = upstream[0].translate
    if config.get("op") != "upper" or config.get("mode", "replace") != "replace":
        raise Unsupported("oracle string binding covers upper+replace only")
    column = translate(config["column"])
    return Binding(STRING_MANIPULATION, {
        "expression": "upperCase($%s$)" % column,
        "append_column": True,
        "replaced_column": column,
    }, **inherited)


ORACLE_BINDINGS = {
    "table_source": bind_table_source,
    "row_filter": bind_row_filter,
    "sorter": bind_sorter,
    "group_by": bind_group_by,
    "joiner": bind_joiner,
    "column_filter": bind_column_filter,
    "missing_value": bind_missing_value,
    "string_manipulation": bind_string_manipulation,
}


def oracle_binding(node_type, config, upstream):
    if node_type not in ORACLE_BINDINGS:
        raise Unsupported("no oracle binding for node type %r" % node_type)
    return ORACLE_BINDINGS[node_type](config, upstream)


# ------------------------------------------------------------------- running


def _check_key(check):
    return "%s:%s" % (check["node"], check["kind"])


def _input_ports(workflow):
    ports = {node["id"]: {} for node in workflow["nodes"]}
    for edge in workflow.get("edges", []):
        ports[edge["to"]][edge.get("toPort", 0)] = edge["from"]
    return ports


def run_on_oracle(api, workflow):
    """Execute one corpus workflow on the oracle and return its observation."""
    observation = {"status": "ok", "nodes": {}, "checks": {}, "durationMs": None}
    workflow_id = api.create_workflow(workflow["id"])
    try:
        ports = _input_ports(workflow)
        nodes = {}
        bindings = {}
        for node in workflow["nodes"]:
            upstream = {}
            for port, source_id in ports[node["id"]].items():
                upstream[port] = bindings.get(source_id)
            binding = oracle_binding(node["type"], node.get("config") or {}, upstream)
            response = api.add_node(workflow_id, binding.factory, binding.settings)
            if response.status != 200:
                raise dap.ApiError("add node %s: HTTP %s %s"
                                   % (node["id"], response.status, _short(response.body)))
            nodes[node["id"]] = response.body["node"]
            bindings[node["id"]] = binding
        for edge in workflow.get("edges", []):
            api.connect_ok(workflow_id, nodes[edge["from"]], nodes[edge["to"]],
                           edge.get("fromPort", 0), edge.get("toPort", 0))
        result = api.run_ok(workflow_id)
        observation["durationMs"] = result.get("durationMs")
        for state in result.get("nodes", []):
            observation["nodes"][state.get("name")] = state.get("state")
        for check in workflow.get("checks", []):
            kind = check["kind"]
            if kind not in ("table", "profile"):
                continue  # expect/expect_profile/equivalent are native-side checks
            binding = bindings[check["node"]]
            key = _check_key(check)
            if kind == "table":
                body = api.table(workflow_id, nodes[check["node"]], limit=check.get("limit", 50))
                observation["checks"][key] = {
                    "kind": "table",
                    "rows": body["rows"],
                    "columns": binding.normalize(body["columns"]),
                    "data": body["data"],
                    "order": check.get("order", "unordered"),
                }
            else:
                body = api.profile(workflow_id, nodes[check["node"]])
                observation["checks"][key] = {
                    "kind": "profile",
                    "rows": body["rows"],
                    "columns": binding.normalize(body["columns"]),
                }
    finally:
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


def compare_profile(key, expected, actual, problems):
    if expected["rows"] != actual["rows"]:
        problems.append("%s: rows %r != %r" % (key, actual["rows"], expected["rows"]))
    expected_columns = {column["name"]: column for column in expected["columns"]}
    actual_columns = {column["name"]: column for column in actual["columns"]}
    if sorted(expected_columns) != sorted(actual_columns):
        problems.append("%s: column set %r != %r"
                        % (key, sorted(actual_columns), sorted(expected_columns)))
        return
    for name, want in expected_columns.items():
        got = actual_columns[name]
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
    if expected.get("nodes") != actual.get("nodes"):
        problems.append("%s: node states %r != %r" % (workflow_id, actual.get("nodes"), expected.get("nodes")))
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


# ----------------------------------------------------------------------- CLI


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--engine", choices=("oracle", "native"), default="oracle")
    parser.add_argument("--base", default="http://127.0.0.1:7800", help="engine base URL")
    parser.add_argument("--session", default="dap-parity", help="X-DAP-Session value")
    parser.add_argument("--write-baseline", action="store_true", help="record the oracle baseline")
    parser.add_argument("--check-baseline", action="store_true", help="re-run and compare with the baseline")
    parser.add_argument("--only", action="append", metavar="PREFIX", help="limit to workflow ids starting with PREFIX")
    parser.add_argument("--list", action="store_true", help="list corpus workflows and exit")
    parser.add_argument("--validate", action="store_true", help="check corpus structure without an engine")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    workflows = load_corpus(args.only)
    if args.list:
        for workflow in workflows:
            print("%-28s %-11s %s" % (workflow["id"], workflow["kind"], workflow["title"]))
        return 0
    if not workflows:
        print("no corpus workflows matched", file=sys.stderr)
        return 2
    if args.validate:
        problems = validate_corpus(load_corpus(), load_datasets())
        for line in problems:
            print("FAIL  " + line)
        print("corpus: %d workflows, %d problem(s)" % (len(workflows), len(problems)))
        return 1 if problems else 0
    if args.engine == "native":
        print("--engine native lands with dap-native in Phase 1/2", file=sys.stderr)
        return 2
    if not (args.write_baseline or args.check_baseline):
        print("choose --write-baseline or --check-baseline", file=sys.stderr)
        return 2

    api = dap.Api(args.base, session=args.session)
    api.health()
    observations = {}
    failures = []
    for workflow in workflows:
        if workflow.get("kind") == "native-only":
            reason = workflow.get("oracle_skip_reason", "native-only workflow")
            observations[workflow["id"]] = {"status": "skipped", "reason": reason}
            print("SKIP  %-28s %s" % (workflow["id"], reason[:110]))
            continue
        try:
            observation = run_on_oracle(api, workflow)
        except (dap.ApiError, AssertionError, Unsupported) as exc:
            observation = {"status": "error", "reason": str(exc)}
        observations[workflow["id"]] = observation
        if observation.get("status") == "skipped":
            print("SKIP  %-28s %s" % (workflow["id"], observation.get("reason", "")[:110]))
        elif observation.get("status") == "error":
            failures.append("%s: %s" % (workflow["id"], observation.get("reason")))
            print("FAIL  %-28s %s" % (workflow["id"], observation.get("reason")))
        else:
            print("ok    %-28s %sms  checks=%d" % (workflow["id"], observation.get("durationMs"),
                                                   len(observation.get("checks", {}))))

    if args.write_baseline:
        payload = {
            "corpusVersion": CORPUS_VERSION,
            "engine": {"name": "DAP", "version": "1.0.0", "api": "v1"},
            "workflows": observations,
        }
        BASELINE_PATH.write_text(json.dumps(payload, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        print("\nbaseline written: %s (%d workflows, %.1f KiB)"
              % (BASELINE_PATH, len(observations), BASELINE_PATH.stat().st_size / 1024))
        return 0 if not failures else 1

    baseline = json.loads(BASELINE_PATH.read_text(encoding="utf-8"))
    if baseline.get("corpusVersion") != CORPUS_VERSION:
        print("baseline corpusVersion %r != %r" % (baseline.get("corpusVersion"), CORPUS_VERSION), file=sys.stderr)
        return 1
    problems = []
    for workflow_id, expected in baseline["workflows"].items():
        actual = observations.get(workflow_id)
        if actual is None:
            problems.append("%s: missing from this run" % workflow_id)
            continue
        workflow_problems = compare_observation(workflow_id, expected, actual)
        problems.extend(workflow_problems)
        if args.verbose and workflow_problems:
            print("\n".join("  " + line for line in workflow_problems))
    problems.extend(failures)
    if problems:
        print("\n%d parity problem(s):" % len(problems))
        for line in problems[:60]:
            print("  " + line)
        return 1
    print("\nparity OK: %d workflows match %s" % (len(baseline["workflows"]), BASELINE_PATH))
    return 0


if __name__ == "__main__":
    sys.exit(main())
