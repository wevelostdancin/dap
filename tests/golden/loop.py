#!/usr/bin/env python3
"""Golden: Chunk Loop Start + Loop End.

A real loop is assembled (reader -> Chunk Loop Start -> Loop End); the loop
connection is accepted by the engine and the loop collects all 150 rows with
one Iteration value per chunk.
"""

import dap_client as dap

RDS = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
LOOP_START = "org.knime.base.node.meta.looper.chunk.LoopStartChunkNodeFactory"
LOOP_END = "org.knime.base.node.meta.looper.LoopEndNodeFactory"
ROWS_PER_CHUNK = 50


def run(api, sc):
    workflow = api.create_workflow("golden-loop")
    reader = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})
    start = api.add_node_ok(workflow, LOOP_START, {"mode": "RowsPerChunk",
                                                   "nrRowsPerChunk": ROWS_PER_CHUNK})
    end = api.add_node_ok(workflow, LOOP_END)
    api.connect_ok(workflow, reader, start)
    api.connect_ok(workflow, start, end)

    def topology():
        nodes = api.workflow_nodes(workflow)
        names = {n["name"] for n in nodes}
        dap.expect_eq(len(nodes), 3, "workflow node count")
        dap.expect({"Example Data Reader", "Chunk Loop Start", "Loop End"} <= names,
                   "missing loop nodes: %s" % sorted(names))
        debug = api.debug(workflow)
        dap.expect(len(debug.get("connections", [])) >= 2,
                   "expected at least 2 connections, got %s" % debug.get("connections"))
        return "3 nodes, %d connections, loop connection accepted" % len(debug["connections"])

    sc.run_check("loop.topology", topology)

    def execute():
        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is True, "loop run ok=false: %s" % body)
        states = {n["name"]: n["state"] for n in body.get("nodes", [])}
        dap.expect_eq(states.get("Chunk Loop Start"), "EXECUTED", "loop start state")
        dap.expect_eq(states.get("Loop End"), "EXECUTED", "loop end state")
        return "executed in %s ms" % body.get("durationMs")

    sc.run_check("loop.run", execute)

    def collected():
        table = api.table(workflow, end, limit=200)
        dap.expect_eq(table["rows"], 150, "collected rows")
        columns = [c["name"] for c in table["columns"]]
        dap.expect("Iteration" in columns, "Loop End output misses Iteration column: %s" % columns)
        iteration_index = columns.index("Iteration")
        counts = {}
        for row in table["data"]:
            counts[row[iteration_index]] = counts.get(row[iteration_index], 0) + 1
        dap.expect_eq(sorted(counts), ["0", "1", "2"], "iteration values")
        dap.expect_eq(sorted(counts.values()), [ROWS_PER_CHUNK, ROWS_PER_CHUNK, ROWS_PER_CHUNK],
                      "rows per chunk")
        profile = api.profile(workflow, end)
        sepal = [c for c in profile["columns"] if c["name"] == "sepal_length"][0]
        dap.expect_close(sepal["mean"], 5.8433, 1e-4, "mean sepal_length after loop")
        return "150 rows collected, 3 chunks x %d rows, mean sepal_length=%.6f" % (
            ROWS_PER_CHUNK, sepal["mean"])

    sc.run_check("loop.collected_rows", collected)
