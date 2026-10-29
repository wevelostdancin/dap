#!/usr/bin/env python3
"""Golden: empty list settings (array-size=0) can be filled through /v1.

Column Filter ships with an empty <config key="exclude"><entry key="array-size"
value="0"/></config>. SettingsMerge creates the missing entry with the right XML
type; the node excludes sepal_length and the table keeps 4 columns.
"""

import dap_client as dap

RDS = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
COLUMN_FILTER = "org.knime.base.node.preproc.filter.column.FilterColumnNodeFactory"


def run(api, sc):
    workflow = api.create_workflow("golden-empty-array")
    reader = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})
    filt = api.add_node_ok(workflow, COLUMN_FILTER, {
        "selectionOption": "EnforceExclusion",
        "exclude": ["sepal_length"],
    })
    api.connect_ok(workflow, reader, filt)

    def execute():
        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is True, "run ok=false: %s" % body)
        dap.expect_eq(body["nodes"][-1]["state"], "EXECUTED", "filter state")
        return "empty exclude[] filled, run ok in %s ms" % body.get("durationMs")

    sc.run_check("empty_array.run", execute)

    def columns():
        table = api.table(workflow, filt, limit=1)
        names = [c["name"] for c in table["columns"]]
        dap.expect_eq(table["rows"], 150, "rows")
        dap.expect_eq(names, ["sepal_width", "petal_length", "petal_width", "species"],
                      "columns after exclusion")
        return "150 rows, columns=%s" % ",".join(names)

    sc.run_check("empty_array.exclude_column", columns)
