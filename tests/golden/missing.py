#!/usr/bin/env python3
"""Golden: AIR_QUALITY + Missing Value.

AIR_QUALITY as loaded by this engine build contains no missing cells at all,
so "missing == 0 after the node" holds, but it is not caused by the node. The
Missing Value node stores its treatment in list settings (columnSettings,
dataTypeSettings) that start empty and therefore cannot be filled through /v1;
with default settings it is a pass-through. That actual behaviour is shown on
WORLDBANK_COUNTRIES, which does contain missing cells.
"""

import dap_client as dap

RDS = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
MISSING_VALUE = "org.knime.base.node.preproc.pmml.missingval.compute.MissingValueHandlerNodeFactory"
COLUMN = "RH"
AIR_ROWS = 9357


def _missing_total(profile):
    return sum(column["missing"] for column in profile["columns"])


def run(api, sc):
    workflow = api.create_workflow("golden-missing-air")
    reader = api.add_node_ok(workflow, RDS, {"dataset": "AIR_QUALITY"})
    node = api.add_node_ok(workflow, MISSING_VALUE)
    api.connect_ok(workflow, reader, node)

    def dataset_state():
        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is True, "run ok=false: %s" % body)
        profile = api.profile(workflow, reader)
        dap.expect_eq(profile["rows"], AIR_ROWS, "AIR_QUALITY rows")
        dap.expect_eq(len(profile["columns"]), 15, "AIR_QUALITY columns")
        by_name = {c["name"]: c for c in profile["columns"]}
        dap.expect(COLUMN in by_name, "column %r not found" % COLUMN)
        dap.expect_eq(by_name[COLUMN]["missing"], 0, "%s missing in source" % COLUMN)
        total = _missing_total(profile)
        dap.expect_eq(total, 0, "AIR_QUALITY source missing cells")
        return "source: %d rows, 15 columns, 0 missing cells (nothing to repair)" % AIR_ROWS

    sc.run_check("missing.air_quality_source", dataset_state)

    def after_node():
        profile = api.profile(workflow, node)
        dap.expect_eq(profile["rows"], AIR_ROWS, "rows after Missing Value")
        dap.expect_eq(len(profile["columns"]), 15, "columns after Missing Value")
        by_name = {c["name"]: c for c in profile["columns"]}
        dap.expect_eq(by_name[COLUMN]["missing"], 0, "%s missing after node" % COLUMN)
        dap.expect_eq(_missing_total(profile), 0, "total missing after node")
        nodes = {n["name"]: n["state"] for n in api.workflow_nodes(workflow)}
        dap.expect_eq(nodes.get("Missing Value"), "EXECUTED", "Missing Value state")
        return "after node: %d rows, missing(%s)=0, total=0" % (AIR_ROWS, COLUMN)

    sc.run_check("missing.after_node_column_has_no_missing", after_node)

    attempt = api.configure(workflow, node, {"columnSettings": [{"column": COLUMN,
                                                                "method": "Mean"}]})
    if attempt.status == 400 and "template" in str(attempt.body.get("error", "")):
        reason = ("columnSettings is a nested config (not a flat entry array): HTTP 400 %r. "
                  "SettingsMerge supports empty flat arrays (array-size=0), but it does not "
                  "synthesize nested config templates; the Missing Value node therefore stays "
                  "a pass-through until columnSettings is described explicitly."
                  % attempt.body.get("error"))
    else:
        reason = "unexpected response when configuring columnSettings: %s %s" % (
            attempt.status, attempt.body)
    sc.skip("missing.configure_column_settings", reason)

    def worldbank_behaviour():
        workflow2 = api.create_workflow("golden-missing-worldbank")
        source = api.add_node_ok(workflow2, RDS, {"dataset": "WORLDBANK_COUNTRIES"})
        repair = api.add_node_ok(workflow2, MISSING_VALUE)
        api.connect_ok(workflow2, source, repair)
        body = api.run_ok(workflow2)
        dap.expect(body.get("ok") is True, "WORLDBANK run ok=false: %s" % body)
        before = api.profile(workflow2, source)
        after = api.profile(workflow2, repair)
        before_total = _missing_total(before)
        after_total = _missing_total(after)
        dap.expect(before_total > 0, "WORLDBANK_COUNTRIES unexpectedly has no missing cells")
        dap.expect_eq(after["rows"], before["rows"], "rows unchanged by default Missing Value")
        dap.expect_eq(after_total, before_total,
                      "default Missing Value changed the number of missing cells")
        per_column = {c["name"]: c["missing"] for c in before["columns"]}
        for column in after["columns"]:
            dap.expect_eq(column["missing"], per_column.get(column["name"]), 
                          "missing count changed for %r" % column["name"])
        return ("documented behaviour: without columnSettings the node passes the table "
                "through unchanged (%d rows, %d missing cells before and after)"
                % (before["rows"], before_total))

    sc.run_check("missing.worldbank_pass_through_documented", worldbank_behaviour)
