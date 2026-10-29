#!/usr/bin/env python3
"""Golden: IRIS dataset reader (150 rows, sepal_length mean 5.8433)."""

import dap_client as dap

RDS = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
EXPECTED_MEAN = 5.8433
COLUMNS = ["sepal_length", "sepal_width", "petal_length", "petal_width", "species"]


def run(api, sc):
    workflow = api.create_workflow("golden-iris")
    node = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})

    def run_workflow():
        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is True, "workflow reported ok=false: %s" % body)
        states = {n["name"]: n["state"] for n in body.get("nodes", [])}
        dap.expect_eq(states.get("Example Data Reader"), "EXECUTED", "reader state")
        return "executed in %s ms" % body.get("durationMs")

    sc.run_check("iris.run", run_workflow)

    def table_check():
        table = api.table(workflow, node, limit=5)
        dap.expect_eq(table["rows"], 150, "rows")
        dap.expect_eq([c["name"] for c in table["columns"]], COLUMNS, "columns")
        dap.expect_eq([c["type"] for c in table["columns"]][-1], "String", "species type")
        dap.expect_eq(table["preview"], 5, "preview")
        dap.expect_eq(table["data"][0], ["5.1", "3.5", "1.4", "0.2", "setosa"], "first row")
        return "150 rows, columns=%s, first row pinned" % ",".join(COLUMNS)

    sc.run_check("iris.table", table_check)

    def profile_check():
        profile = api.profile(workflow, node)
        dap.expect_eq(profile["rows"], 150, "profile rows")
        columns = {c["name"]: c for c in profile["columns"]}
        sepal = columns["sepal_length"]
        dap.expect_eq(sepal["type"], "Number (Float)", "sepal_length type")
        dap.expect_eq(sepal["missing"], 0, "sepal_length missing")
        dap.expect_eq(sepal["distinct"], 35, "sepal_length distinct")
        dap.expect_close(sepal["mean"], EXPECTED_MEAN, 1e-4, "sepal_length mean")
        dap.expect_close(sepal["min"], 4.3, 1e-9, "sepal_length min")
        dap.expect_close(sepal["max"], 7.9, 1e-9, "sepal_length max")
        dap.expect_eq(columns["species"]["distinct"], 3, "species distinct")
        return "mean(sepal_length)=%.6f (expected %.4f)" % (sepal["mean"], EXPECTED_MEAN)

    sc.run_check("iris.sepal_length.mean", profile_check)
