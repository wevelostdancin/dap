#!/usr/bin/env python3
"""Golden: group mean of sepal_length by species (3 rows).

The catalog reports the GroupBy node and its group-column configKey, but the
engine's SettingsMerge can only fill list settings that already contain a
template entry. GroupBy's filterString lists (grouByColumns.InclList) start
empty, so configuring the node through /v1 is impossible and is recorded as a
SKIP. The same aggregation is then verified for real with the Row Aggregator
node (category column + AVERAGE + WILDCARD column filter).
"""

import dap_client as dap

RDS = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
GROUPBY = "org.knime.base.node.preproc.groupby.GroupByNodeFactory"
ROW_AGG = "org.knime.base.node.preproc.rowagg.RowAggregatorNodeFactory"

EXPECTED = {"setosa": 5.006, "versicolor": 5.936, "virginica": 6.588}
EXPECTED_OVERALL = 5.8433


def run(api, sc):
    discovery = {}

    def discover_groupby():
        index = api.nodes_index(query="groupby", limit=50)
        match = [n for n in index["nodes"] if n["id"] == GROUPBY]
        dap.expect(match, "GroupBy node %s not found via /v1/nodes?q=groupby" % GROUPBY)
        node = match[0]
        dap.expect_eq(node["name"], "GroupBy", "node name")
        dap.expect_eq(node["tier"], "core", "node tier")
        dialog = api.dialog(GROUPBY).require()
        dap.expect_eq(dialog.get("available"), True, "dialog available")
        fields = {f.get("configKey"): f for f in dialog.get("schema", {}).get("fields", [])}
        dap.expect("grouByColumns" in fields, "configKey grouByColumns missing; fields=%s" % sorted(fields))
        discovery["config_key"] = "grouByColumns"
        return "id=%s configKey=grouByColumns tier=core" % GROUPBY

    sc.run_check("groupby.discovery", discover_groupby)

    def apply_group_columns():
        workflow = api.create_workflow("golden-groupby")
        reader = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})
        groupby = api.add_node_ok(workflow, GROUPBY,
                                  {"grouByColumns": {"InclList": ["species"]}})
        api.connect_ok(workflow, reader, groupby)
        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is True, "GroupBy run ok=false: %s" % body)
        dap.expect_eq(body["nodes"][-1]["state"], "EXECUTED", "GroupBy state")
        table = api.table(workflow, groupby, limit=10)
        dap.expect_eq(table["rows"], 3, "grouped rows")
        dap.expect_eq([c["name"] for c in table["columns"]], ["species"], "columns")
        dap.expect_eq(sorted(row[0] for row in table["data"]),
                      ["setosa", "versicolor", "virginica"], "species groups")
        return "GroupBy configured via grouByColumns.InclList, 3 rows"

    sc.run_check("groupby.apply_group_columns", apply_group_columns)

    def aggregation_equivalent():
        workflow = api.create_workflow("golden-groupby-rowagg")
        reader = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})
        aggregator = api.add_node_ok(workflow, ROW_AGG, {
            "categoryColumnV2": {"regularChoice": "species", "specialChoice_Internals": "NONE"},
            "aggregationMethod": "AVERAGE",
            "frequencyColumns": {"mode": "WILDCARD", "patternFilter": {"pattern": "sepal_length"}},
            "grandTotals": False,
        })
        api.connect_ok(workflow, reader, aggregator)
        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is True, "workflow ok=false: %s" % body)
        dap.expect_eq(body["nodes"][-1]["state"], "EXECUTED", "aggregator state")

        table = api.table(workflow, aggregator, limit=10)
        dap.expect_eq(table["rows"], 3, "grouped rows")
        dap.expect_eq([c["name"] for c in table["columns"]], ["species", "sepal_length"], "columns")
        got = {row[0]: float(row[1]) for row in table["data"]}
        dap.expect_eq(sorted(got), sorted(EXPECTED), "species values")
        for species, mean in EXPECTED.items():
            dap.expect_close(got[species], mean, 1e-3, "mean sepal_length for %s" % species)

        profile = api.profile(workflow, aggregator)
        dap.expect_eq(profile["rows"], 3, "profile rows")
        overall = [c for c in profile["columns"] if c["name"] == "sepal_length"][0]
        dap.expect_eq(overall["missing"], 0, "missing")
        dap.expect_close(overall["mean"], EXPECTED_OVERALL, 1e-3, "mean of group means")
        return "3 rows: setosa=%.3f versicolor=%.3f virginica=%.3f (mean of means %.4f)" % (
            got["setosa"], got["versicolor"], got["virginica"], overall["mean"])

    sc.run_check("groupby.aggregation_equivalent_row_aggregator", aggregation_equivalent)
