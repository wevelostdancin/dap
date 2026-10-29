#!/usr/bin/env python3
"""Contract tests for the DAP /v1 HTTP API.

Runnable through tests/run_all.py (preferred, it owns a disposable service on
127.0.0.1:7810) or standalone against an already running instance:

    python3 tests/contract/test_api.py [base-url]

Default base URL: DAP_BASE env var, else http://127.0.0.1:7810.
"""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import dap_client as dap  # noqa: E402

READ_DATASET = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
GROUPBY = "org.knime.base.node.preproc.groupby.GroupByNodeFactory"
EXPECTED_NODES = 832
API_VERSION = "1"
# The catalog has 832 entries but only 827 distinct factory ids: five factories
# are registered twice under different category paths (one of them empty).
EXPECTED_UNIQUE_IDS = 827
EXPECTED_DUPLICATE_IDS = {
    "org.knime.base.filehandling.filemetainfo.FileMetaInfoNodeFactory",
    "org.knime.base.node.preproc.filter.column.FilterColumnNodeFactory",
    "org.knime.base.node.preproc.joiner.NewJoinerNodeFactory",
    "org.knime.base.node.preproc.split.SplitNodeFactory",
    "org.knime.distmatrix.knn.KnnDistNodeFactory",
}


def run(api, sc):
    def health():
        body = api.health()
        dap.expect_eq(body.get("ok"), True, "health.ok")
        dap.expect_eq(body.get("name"), "DAP", "health.name")
        dap.expect_eq(body.get("version"), "1.0.0", "health.version")
        dap.expect_eq(body.get("api"), API_VERSION, "health.api")
        dap.expect_eq(body.get("nodes"), EXPECTED_NODES, "health.nodes")
        dap.expect(isinstance(body.get("coreNodes"), int), "health.coreNodes must be an int")
        dap.expect(0 < body["coreNodes"] <= body["nodes"],
                   "health.coreNodes out of range (curated tier list not applied?): %r"
                   % body.get("coreNodes"))
        for key in ("workflows", "sessions", "jobs"):
            dap.expect(isinstance(body.get(key), int) and body[key] >= 0,
                       "health.%s must be a non-negative int, got %r" % (key, body.get(key)))
        memory = body.get("memory") or {}
        dap.expect(isinstance(memory.get("usedMb"), int), "health.memory.usedMb")
        dap.expect(isinstance(memory.get("maxMb"), int), "health.memory.maxMb")
        return "nodes=%d core=%d jobs=%d" % (body["nodes"], body["coreNodes"], body["jobs"])

    sc.run_check("contract.health", health)

    def headers():
        paths = ["/v1/health", "/v1/nodes?limit=1", "/v1/jobs", "/v1/workflows"]
        for path in paths:
            response = api.get(path)
            dap.expect_eq(response.status, 200, "GET %s status" % path)
            dap.expect_eq(response.header("X-DAP-Api-Version"), API_VERSION,
                          "X-DAP-Api-Version on %s" % path)
            dap.expect_eq(response.header("X-DAP-Session"), api.session,
                          "X-DAP-Session echo on %s" % path)
        root = api.get("/v1")
        dap.expect_eq(root.header("X-DAP-Api-Version"), None,
                      "root /v1 is not versioned (documented)")
        return "X-DAP-Api-Version=1 on /v1/health, /v1/nodes, /v1/jobs, /v1/workflows"

    sc.run_check("contract.api_version_header", headers)

    def root():
        body = api.server_info()
        dap.expect_eq(body.get("name"), "DAP", "root.name")
        dap.expect_eq(body.get("api"), "v1", "root.api")
        endpoints = str(body.get("endpoints", ""))
        for expected in ("/v1/nodes", "/v1/workflows", "/v1/jobs", "/v1/health"):
            dap.expect_contains(endpoints, expected, "root.endpoints")
        return "root document lists %s" % endpoints

    sc.run_check("contract.root_document", root)

    def catalog():
        body = api.nodes_index(limit=1000)
        nodes = body["nodes"]
        dap.expect_eq(body["count"], EXPECTED_NODES, "catalog count")
        dap.expect_eq(len(nodes), EXPECTED_NODES, "catalog payload length")
        ids = [n["id"] for n in nodes]
        duplicates = {node_id for node_id in ids if ids.count(node_id) > 1}
        dap.expect_eq(len(set(ids)), EXPECTED_UNIQUE_IDS,
                      "unique factory ids (832 entries, 5 factories registered twice)")
        dap.expect_eq(duplicates, EXPECTED_DUPLICATE_IDS,
                      "duplicated factory ids changed: %s" % sorted(duplicates))
        tiers = {}
        for node in nodes:
            for key in ("id", "name", "category", "type", "deprecated", "tier",
                        "inPorts", "outPorts"):
                dap.expect(key in node, "node %r misses key %r" % (node.get("id"), key))
            dap.expect(node["tier"] in ("core", "experimental"),
                       "unexpected tier %r on %r" % (node["tier"], node["id"]))
            dap.expect(isinstance(node["deprecated"], bool), "deprecated is not bool")
            dap.expect(isinstance(node["inPorts"], list), "inPorts is not a list")
            dap.expect(isinstance(node["outPorts"], list), "outPorts is not a list")
            tiers[node["tier"]] = tiers.get(node["tier"], 0) + 1
        core_nodes = api.health()["coreNodes"]
        dap.expect_eq(tiers.get("core"), core_nodes,
                      "catalog core tier count vs /v1/health.coreNodes")
        return ("%d entries / %d unique ids (%d core, %d experimental); "
                "registered twice: %s" % (EXPECTED_NODES, EXPECTED_UNIQUE_IDS,
                                          tiers["core"], tiers.get("experimental", 0),
                                          ", ".join(sorted(duplicates))))

    sc.run_check("contract.nodes_catalog", catalog)

    def default_limit():
        body = api.nodes_index()
        dap.expect_eq(body["count"], 50, "default page size")
        dap.expect_eq(len(body["nodes"]), 50, "default page payload")
        limited = api.nodes_index(limit=3)
        dap.expect_eq(limited["count"], 3, "explicit limit")
        return "default limit=50, limit=3 honoured"

    sc.run_check("contract.nodes_default_limit", default_limit)

    def search():
        body = api.nodes_index(query="groupby", limit=100)
        ids = {n["id"] for n in body["nodes"]}
        dap.expect(GROUPBY in ids, "q=groupby misses %s" % GROUPBY)
        match = [n for n in body["nodes"] if n["id"] == GROUPBY][0]
        dap.expect_eq(match["name"], "GroupBy", "search result name")
        dap.expect_eq(match["tier"], "core", "search result tier")
        category = api.nodes_index(category="/io", limit=500)
        dap.expect(category["count"] > 0, "category filter returned nothing")
        for node in category["nodes"]:
            dap.expect(node["category"].startswith("/io"),
                       "category filter leaked %r" % node["category"])
        return "q=groupby -> GroupBy (core); category=/io -> %d nodes" % category["count"]

    sc.run_check("contract.nodes_search_and_category", search)

    def node_detail():
        body = api.node(READ_DATASET).require()
        dap.expect_eq(body.get("id"), READ_DATASET, "node.id")
        dap.expect_eq(body.get("name"), "Example Data Reader", "node.name")
        dap.expect_eq(body.get("tier"), "core", "node.tier")
        dap.expect("dataset" in (body.get("settings") or []),
                   "node.settings misses 'dataset': %s" % body.get("settings"))
        dap.expect(len(body.get("settingsXml") or "") > 100, "node.settingsXml is empty")
        unknown = api.node("org.example.NoSuchNodeFactory")
        dap.expect_eq(unknown.status, 404, "unknown node status")
        dap.expect_contains(str(unknown.body.get("error")), "unknown node", "unknown node text")
        return "detail exposes settings+settingsXml; unknown node -> 404"

    sc.run_check("contract.node_detail", node_detail)

    def dialog():
        body = api.dialog(READ_DATASET).require()
        dap.expect_eq(body.get("available"), True, "dialog.available")
        dap.expect_eq(body.get("schemaKind"), "annotations", "dialog.schemaKind")
        dap.expect_eq(body.get("tier"), "core", "dialog.tier")
        fields = {f.get("configKey"): f for f in (body.get("schema") or {}).get("fields", [])}
        dap.expect("dataset" in fields, "dialog schema misses configKey dataset")
        dap.expect_eq(fields["dataset"].get("type"), "enum", "dataset field type")
        choices = {(c.get("value"), c.get("label")) for c in fields["dataset"].get("choices", [])}
        dap.expect(("IRIS", "Iris data") in choices, "dataset choices: %s" % sorted(choices))
        unknown = api.dialog("org.example.NoSuchNodeFactory")
        dap.expect_eq(unknown.status, 400, "unknown dialog status (documented)")
        dap.expect_contains(str(unknown.body.get("error")), "unknown node", "unknown dialog text")
        return "available=true, schemaKind=annotations, dataset enum includes IRIS"

    sc.run_check("contract.node_dialog_schema", dialog)

    workflow = {"id": None}

    def workflow_lifecycle():
        created = api.post("/v1/workflows", {"name": "contract-workflow"}).require()
        dap.expect(created.get("id", "").startswith("wf-"), "workflow id: %r" % created.get("id"))
        dap.expect_eq(created.get("session"), api.session, "workflow session")
        workflow["id"] = created["id"]
        listed = api.workflows()
        dap.expect(any(item["id"] == created["id"] for item in listed),
                   "workflow not listed by GET /v1/workflows")
        deleted = api.delete_workflow(created["id"]).require()
        dap.expect_eq(deleted.get("ok"), True, "workflow delete body")
        gone = api.get("/v1/workflows/%s/nodes" % created["id"])
        dap.expect_eq(gone.status, 404, "deleted workflow status")
        return "POST/list/DELETE ok, deleted workflow -> 404"

    sc.run_check("contract.workflow_lifecycle", workflow_lifecycle)

    def session_isolation():
        owned = api.post("/v1/workflows", {"name": "contract-session-a"}).require()["id"]
        other = dap.Api(api.base, "contract-other")
        response = other.get("/v1/workflows/%s/nodes" % owned)
        dap.expect_eq(response.status, 404, "foreign workflow status")
        dap.expect_eq(other.health().get("ok"), True, "foreign session health")
        dap.expect(not any(item["id"] == owned for item in other.workflows()),
                   "foreign session sees another session workflow")
        api.delete_workflow(owned).require()
        return "workflow from session %r is invisible to session 'contract-other'" % api.session

    sc.run_check("contract.session_isolation", session_isolation)

    def jobs_endpoint():
        fresh = dap.Api(api.base, "contract-jobs")
        body = fresh.jobs()
        dap.expect_eq(body.get("count"), 0, "fresh session job count")
        dap.expect_eq(body.get("jobs"), [], "fresh session job list")
        unknown = fresh.job("job-999999")
        dap.expect_eq(unknown.status, 404, "unknown job status")
        dap.expect_contains(str(unknown.body.get("error")), "unknown job", "unknown job text")
        return "GET /v1/jobs -> count+list; unknown job -> 404"

    sc.run_check("contract.jobs", jobs_endpoint)

    def error_codes():
        no_route = api.get("/v1/nope")
        dap.expect_eq(no_route.status, 404, "unknown route status")
        dap.expect_contains(str(no_route.body.get("error")), "no route", "unknown route text")
        wrong_method = api.post("/v1/health", {})
        dap.expect_eq(wrong_method.status, 404, "POST /v1/health status")
        dap.expect_contains(str(wrong_method.body.get("error")), "no route", "wrong method text")
        unknown_wf = api.get("/v1/workflows/wf-999999/nodes")
        dap.expect_eq(unknown_wf.status, 404, "unknown workflow status")
        dap.expect_contains(str(unknown_wf.body.get("error")), "unknown workflow",
                            "unknown workflow text")
        return "404 for unknown route/method/workflow, error text pinned"

    sc.run_check("contract.error_codes_404", error_codes)

    def settings_error():
        workflow = api.post("/v1/workflows", {"name": "contract-settings"}).require()["id"]
        node = api.add_node_ok(workflow, READ_DATASET, {"dataset": "IRIS"})
        response = api.configure(workflow, node, {"no_such_key": 1})
        dap.expect_eq(response.status, 400, "bad setting status")
        error = str(response.body.get("error", ""))
        dap.expect_contains(error, "unknown setting", "bad setting text")
        dap.expect_contains(error, "available", "bad setting must list available keys")
        dap.expect_contains(error, "dataset", "available keys")
        api.delete_workflow(workflow).require()
        return "400 for unknown setting key, lists available: [dataset]"

    sc.run_check("contract.error_codes_400", settings_error)


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    base = argv[0] if argv else os.environ.get("DAP_BASE", "http://127.0.0.1:7810")
    api = dap.Api(base, "contract")
    sc = dap.Scenario("contract")
    try:
        api.health()
    except dap.ConnectionFailed as err:
        print("cannot reach DAP at %s: %s" % (base, err))
        print("start a disposable instance with: python3 tests/run_all.py")
        return 2
    run(api, sc)
    print(sc.render())
    return 0 if sc.status() != dap.FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
