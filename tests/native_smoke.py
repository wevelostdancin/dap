#!/usr/bin/env python3
"""Black-box acceptance checks for dap-native (Phase 2).

Runs against an already running native service, so it can be used by CI, by the
make acceptance flow, or by hand. It only uses the frozen /v1 contract and the
stdlib client in tests/dap_client.py.

    python3 tests/native_smoke.py --base http://127.0.0.1:7800
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "tests"))

import dap_client as dap

SPECS = pathlib.Path(__file__).resolve().parent.parent / "engine-native" / "src" / "main" / "resources" / "specs"


def _specs():
    index = SPECS / "index.txt"
    names = [line.strip() for line in index.read_text(encoding="utf-8").splitlines()
             if line.strip() and not line.strip().startswith("#")]
    return [json.loads((SPECS / name).read_text(encoding="utf-8")) for name in names]


_SPECS = _specs()
NATIVE_NODES = len(_SPECS)
AVAILABLE_NODES = {spec["id"] for spec in _SPECS if spec.get("available", True)}


def columns(body):
    return [(column["name"], column["type"]) for column in body["columns"]]


def check_meta(api):
    body = api.server_info()
    if body.get("name") != "DAP" or body.get("api") != "v1":
        raise AssertionError("unexpected meta: %r" % (body,))
    return "name=%s version=%s" % (body.get("name"), body.get("version"))


def check_health_initial(api):
    body = api.health()
    if body.get("ok") is not True:
        raise AssertionError("health not ok: %r" % (body,))
    if body.get("nodes") != NATIVE_NODES:
        raise AssertionError("nodes=%r, expected %d" % (body.get("nodes"), NATIVE_NODES))
    if body.get("coreNodes") != NATIVE_NODES:
        raise AssertionError("coreNodes=%r, expected %d" % (body.get("coreNodes"), NATIVE_NODES))
    for field in ("workflows", "sessions", "jobs", "memory"):
        if field not in body:
            raise AssertionError("health misses %r" % field)
    return "nodes=%d sessions=%s" % (body["nodes"], body.get("sessions"))


def check_catalog(api):
    body = api.nodes_index(limit=100)
    ids = {node["id"] for node in body["nodes"]}
    if len(ids) != NATIVE_NODES:
        raise AssertionError("catalog has %d nodes, expected %d: %s"
                             % (len(ids), NATIVE_NODES, sorted(ids)))
    # /nodes/{id} has no available flag in the frozen schema; dialog does.
    dialog_available = set()
    for node_id in ids:
        response = api.dialog(node_id)
        if response.status == 200 and response.body.get("available"):
            dialog_available.add(node_id)
    missing = AVAILABLE_NODES - dialog_available
    if missing:
        raise AssertionError("available nodes missing from dialog: %s" % sorted(missing))
    return "nodes=%d available=%d" % (len(ids), len(dialog_available))


def check_catalog_errors(api):
    if api.node("dap.no_such_node").status != 404:
        raise AssertionError("GET unknown catalog node did not return 404")
    if api.dialog("dap.no_such_node").status != 400:
        raise AssertionError("GET unknown dialog did not return 400")
    # Every pilot node is available in Phase 2; only the unknown-id codes remain.
    if api.get("/v1/nodes?limit=not-a-number").status != 400:
        raise AssertionError("non-integer limit did not return 400")
    return "404/400 semantics ok"


def create_workflow(api, source_file):
    workflow = api.create_workflow("native-smoke")
    nodes = {}
    nodes["src"] = api.add_node_ok(workflow, "dap.csv_reader", {
        "file": source_file,
        "delimiter": ",",
        "header": True,
        "columns": [
            {"name": "name", "type": "Utf8"},
            {"name": "age", "type": "Int64"},
            {"name": "score", "type": "Float64"},
            {"name": "city", "type": "Utf8"},
        ],
        "emptyStringIsNull": False,
    })
    nodes["missing"] = api.add_node_ok(workflow, "dap.missing_value", {
        "rules": [{"column": "city", "method": "constant", "value": "UNKNOWN"}],
    })
    nodes["cols"] = api.add_node_ok(workflow, "dap.column_filter", {
        "exclude": ["city"],
    })
    nodes["filter"] = api.add_node_ok(workflow, "dap.row_filter", {
        "logic": "and",
        "where": [{"column": "age", "op": "ge", "value": 30}],
    })
    nodes["sort"] = api.add_node_ok(workflow, "dap.sorter", {
        "keys": [{"column": "score", "direction": "desc"}],
    })
    api.connect_ok(workflow, nodes["src"], nodes["missing"])
    api.connect_ok(workflow, nodes["missing"], nodes["cols"])
    api.connect_ok(workflow, nodes["cols"], nodes["filter"])
    api.connect_ok(workflow, nodes["filter"], nodes["sort"])
    return workflow, nodes


def check_workflow(api, tmpdir):
    source = pathlib.Path(tmpdir) / "native-smoke.csv"
    source.write_text(
        "name,age,score,city\n"
        "alice,34,9.5,Berlin\n"
        "bob,28,7.0,\n"
        "carol,41,,\"\"\n"
        "dave,30,8.25,Paris\n",
        encoding="utf-8",
    )
    workflow, nodes = create_workflow(api, str(source))

    node_list = api.workflow_nodes(workflow)
    if len(node_list) != 5:
        raise AssertionError("workflow has %d nodes, expected 5" % len(node_list))

    # The data nodes are lazy before a run: 409 not_materialized with a hint.
    before = api.get("/v1/workflows/%s/nodes/%s/table" % (workflow, nodes["sort"]))
    if before.status != 409 or before.body.get("code") != "not_materialized":
        raise AssertionError("table before run did not return 409 not_materialized: %s %r"
                             % (before.status, before.body))

    result = api.run_ok(workflow)
    states = {entry["name"]: entry["state"] for entry in result["nodes"]}
    if set(states.values()) != {"EXECUTED"} or len(states) != 5:
        raise AssertionError("unexpected node states: %r" % (states,))

    table = api.table(workflow, nodes["sort"], limit=10)
    if table["rows"] != 3:
        raise AssertionError("rows=%r, expected 3" % (table["rows"],))
    if columns(table) != [("name", "String"), ("age", "Number (Integer)"), ("score", "Number (Float)")]:
        raise AssertionError("unexpected columns: %r" % (columns(table),))
    if table["data"][0][:2] != ["alice", "34"]:
        raise AssertionError("unexpected first row: %r" % (table["data"][0],))
    if table["preview"] != 3 or len(table["data"]) != 3:
        raise AssertionError("preview mismatch: %r" % (table,))

    # NULL vs "": bob's unquoted city is NULL, carol's quoted "" is a value.
    src_profile = api.profile(workflow, nodes["src"])
    city = next(column for column in src_profile["columns"] if column["name"] == "city")
    if city["missing"] != 1 or city["distinct"] != 3:
        raise AssertionError("city profile mismatch: %r" % (city,))
    age = next(column for column in src_profile["columns"] if column["name"] == "age")
    if age["type"] != "Number (Integer)" or age["min"] != 28 or age["max"] != 41:
        raise AssertionError("age profile mismatch: %r" % (age,))

    # constant fill: NULL becomes UNKNOWN, "" stays a value.
    fixed = api.table(workflow, nodes["missing"], limit=10)
    city_index = [column["name"] for column in fixed["columns"]].index("city")
    values = {row[0]: row[city_index] for row in fixed["data"]}
    if values.get("bob") != "UNKNOWN" or values.get("carol") != "":
        raise AssertionError("missing_value/empty-string mismatch: %r" % (values,))

    debug = api.debug(workflow)
    if len(debug.get("nodes", [])) != 5 or len(debug.get("connections", [])) != 4:
        raise AssertionError("unexpected debug payload: %r" % (debug,))

    node_dialog = api.get("/v1/workflows/%s/nodes/%s/dialog" % (workflow, nodes["src"]))
    if node_dialog.status != 200 or not node_dialog.body.get("available"):
        raise AssertionError("workflow node dialog is not available: %s %r"
                             % (node_dialog.status, node_dialog.body))

    save_dir = pathlib.Path(tmpdir) / "saved-workflow"
    saved = api.post("/v1/workflows/%s/save" % workflow, {"dir": str(save_dir)}).require()
    if not saved.get("ok") or not (save_dir / "workflow.json").is_file():
        raise AssertionError("save did not write workflow.json: %r" % (saved,))

    # A failed re-run must clear results from the previous successful run.
    invalid = {"keys": [{"column": "no_such_column", "direction": "asc"}]}
    if api.configure(workflow, nodes["sort"], invalid).status != 200:
        raise AssertionError("could not apply the failing sorter config")
    if api.run(workflow).status != 500:
        raise AssertionError("run with an unknown sort column did not fail")
    stale = api.get("/v1/workflows/%s/nodes/%s/table" % (workflow, nodes["sort"]))
    if stale.status != 409 or stale.body.get("code") != "node_failed":
        raise AssertionError("failed run kept a stale table: %s %r" % (stale.status, stale.body))
    if configure_score_sort(api, workflow, nodes["sort"]).status != 200:
        raise AssertionError("could not restore the sorter config")
    rerun = api.run_ok(workflow)
    if not rerun.get("ok"):
        raise AssertionError("restored workflow did not run: %r" % (rerun,))
    if api.table(workflow, nodes["sort"], limit=10)["rows"] != 3:
        raise AssertionError("restored workflow lost its rows")
    return workflow, nodes, "rows=3 columns=%s" % (columns(table),)


def configure_score_sort(api, workflow, node):
    return api.configure(workflow, node, {
        "keys": [{"column": "score", "direction": "desc"}],
    })


def check_async_job(api, workflow):
    response = api.post("/v1/workflows/%s/jobs" % workflow, {})
    if response.status != 202:
        raise AssertionError("async job submit returned %s: %r" % (response.status, response.body))
    job_id = response.body["id"]
    if not response.body.get("statusUrl"):
        raise AssertionError("async job has no statusUrl")
    job, states = api.wait_job(job_id, timeout=30)
    if job["state"] != "succeeded":
        raise AssertionError("async job state %r (%s)" % (job["state"], states))
    if not job.get("result") or not job["result"].get("ok"):
        raise AssertionError("async job has no successful result")
    listed = api.jobs()
    if not any(item["id"] == job_id for item in listed["jobs"]):
        raise AssertionError("job is missing from GET /v1/jobs")
    return job_id


def check_errors(api):
    wf = api.create_workflow("error-cases")
    try:
        if api.add_node(wf, "dap.no_such_factory").status != 400:
            raise AssertionError("unknown factory did not return 400")
        node = api.add_node_ok(wf, "dap.sorter", {"keys": []})
        if api.configure(wf, node, {"no_such_key": 1}).status != 400:
            raise AssertionError("unknown setting did not return 400")
        if api.connect(wf, node, "999:1").status != 400:
            raise AssertionError("unknown connect target did not return 400")
        if api.connect(wf, node, node).status != 400:
            raise AssertionError("self-connect did not return 400")
        if api.configure(wf, node, {"keys": [{"column": "no_such_column", "direction": "asc"}]}).status != 200:
            raise AssertionError("valid sorter settings were rejected")
        if api.run(wf).status != 500:
            raise AssertionError("unknown-column run did not return 500")
        failed_table = api.get("/v1/workflows/%s/nodes/%s/table" % (wf, node))
        if failed_table.status != 409 or failed_table.body.get("code") != "node_failed":
            raise AssertionError("failed node table did not return 409 node_failed: %s %r"
                                 % (failed_table.status, failed_table.body))
        if api.get("/v1/workflows/wf-999999").status != 404:
            raise AssertionError("unknown workflow did not return 404")
        if api.delete("/v1/workflows/wf-999999").status != 404:
            raise AssertionError("delete unknown workflow did not return 404")
        if api.job("job-999999").status != 404:
            raise AssertionError("unknown job did not return 404")
        if api.request("GET", "/v1/health", session="bad session").status != 400:
            raise AssertionError("invalid session did not return 400")
        if api.request("GET", "/v1/no-such-route").status != 404:
            raise AssertionError("unknown route did not return 404")
        if api.delete_workflow(wf).status != 200:
            raise AssertionError("delete workflow did not return 200")
        if api.get("/v1/workflows/%s/nodes" % wf).status != 404:
            raise AssertionError("deleted workflow is still visible")
    finally:
        if api.get("/v1/workflows/%s" % wf).status != 404:
            api.delete_workflow(wf)
    return "error paths ok"


def check_catalog_node_functional(api):
    body = api.node("dap.csv_reader").body
    for field in ("id", "name", "category", "type", "tier", "inPorts", "outPorts",
                  "contributor", "configurable", "settings"):
        if field not in body:
            raise AssertionError("catalog node misses %r" % field)
    dialog = api.dialog("dap.csv_reader").body
    if not dialog.get("available") or dialog.get("schemaKind") != "annotations":
        raise AssertionError("csv_reader dialog is not an annotations schema: %r" % (dialog,))
    fields = dialog["schema"]["fields"]
    if not any(field.get("configKey") == "file" for field in fields):
        raise AssertionError("csv_reader dialog misses file field: %r" % (fields,))
    return "fields=%d" % len(fields)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", default="http://127.0.0.1:7800")
    parser.add_argument("--session", default="native-smoke")
    args = parser.parse_args(argv)

    api = dap.Api(args.base, session=args.session)
    scenario = dap.Scenario("native smoke")

    scenario.run_check("meta", lambda: check_meta(api))
    scenario.run_check("health", lambda: check_health_initial(api))
    scenario.run_check("catalog", lambda: check_catalog(api))
    scenario.run_check("catalog errors", lambda: check_catalog_errors(api))
    scenario.run_check("catalog node", lambda: check_catalog_node_functional(api))

    with tempfile.TemporaryDirectory(prefix="dap-native-smoke-") as tmpdir:
        holder = {}

        def workflow_check():
            workflow, nodes, detail = check_workflow(api, tmpdir)
            holder["workflow"] = workflow
            holder["nodes"] = nodes
            return detail

        scenario.run_check("workflow end-to-end", workflow_check)
        scenario.run_check("async job", lambda: check_async_job(api, holder["workflow"]))
        scenario.run_check("error paths", lambda: check_errors(api))
        if "workflow" in holder:
            scenario.run_check("delete workflow", lambda: (
                api.delete_workflow(holder["workflow"]).require(),
                "deleted",
            )[1])

    print(scenario.render())
    return 0 if scenario.status() == dap.PASS else 1


if __name__ == "__main__":
    sys.exit(main())