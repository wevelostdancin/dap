#!/usr/bin/env python3
"""Golden: two IRIS readers -> Concatenate (300 rows) and Joiner3 (7500 rows).

Both results are re-run and compared by digest to prove determinism.
The legacy Joiner (Joiner2) is documented: it needs join predicates that live
in list settings without templates, so it cannot be configured via /v1 and
fails with an explicit node message instead of running.
"""

import dap_client as dap

RDS = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"
CONCAT = "org.knime.base.node.preproc.append.row.AppendedRowsNodeFactory"
JOINER3 = "org.knime.base.node.preproc.joiner3.Joiner3NodeFactory"
JOINER2 = "org.knime.base.node.preproc.joiner.Joiner2NodeFactory"

EXPECTED_JOIN_ROWS = 3 * 50 * 50  # species x left(50) x right(50)


def _two_readers(api, name):
    workflow = api.create_workflow(name)
    left = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})
    right = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})
    return workflow, left, right


def run(api, sc):
    def concatenate():
        workflow, left, right = _two_readers(api, "golden-join-concat")
        node = api.add_node_ok(workflow, CONCAT)
        api.connect_ok(workflow, left, node, 0, 0)
        api.connect_ok(workflow, right, node, 0, 1)

        first = api.run_ok(workflow)
        dap.expect(first.get("ok") is True, "concat run ok=false: %s" % first)
        profile = api.profile(workflow, node)
        dap.expect_eq(profile["rows"], 300, "concatenated rows")
        dap.expect_eq(len(profile["columns"]), 5, "columns")
        sepal = [c for c in profile["columns"] if c["name"] == "sepal_length"][0]
        dap.expect_close(sepal["mean"], 5.8433, 1e-4, "mean sepal_length")

        table_a = api.table(workflow, node, limit=300)
        dap.expect_eq(table_a["rows"], 300, "table rows")
        dap.expect_eq(len(table_a["data"]), 300, "table payload rows")
        dap.expect_eq(table_a["data"][0], ["5.1", "3.5", "1.4", "0.2", "setosa"], "first row")
        dap.expect_eq(table_a["data"][150], table_a["data"][0], "second block starts at row 150")

        api.run_ok(workflow)
        table_b = api.table(workflow, node, limit=300)
        dap.expect_eq(dap.digest(table_b["data"]), dap.digest(table_a["data"]),
                      "concatenated rows changed between runs")
        return "300 rows, stable digest %s" % dap.digest(table_a["data"])[:16]

    sc.run_check("join.concatenate_300_rows_deterministic", concatenate)

    def joiner3():
        workflow, left, right = _two_readers(api, "golden-join-joiner3")
        node = api.add_node_ok(workflow, JOINER3)
        api.connect_ok(workflow, left, node, 0, 0)
        api.connect_ok(workflow, right, node, 0, 1)
        settings = {
            "matchingCriteria": [{
                "leftTableColumnV2": {"regularChoice": "species"},
                "rightTableColumnV2": {"regularChoice": "species"},
            }],
            # Manual column selection starts empty; select everything by pattern.
            "leftColumnSelectionConfigV2": {"mode": "WILDCARD", "patternFilter": {"pattern": "*"}},
            "rightColumnSelectionConfigV2": {"mode": "WILDCARD", "patternFilter": {"pattern": "*"}},
        }
        api.configure(workflow, node, settings).require()

        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is True, "joiner run ok=false: %s" % body)
        dap.expect_eq(body["nodes"][-1]["state"], "EXECUTED", "joiner state")

        profile = api.profile(workflow, node)
        dap.expect_eq(profile["rows"], EXPECTED_JOIN_ROWS,
                      "inner join on species rows (3 x 50 x 50)")
        names = [c["name"] for c in profile["columns"]]
        dap.expect_eq(len(names), 10, "joined columns (5 left + 5 right)")
        dap.expect("species (Right)" in names, "right table suffix column missing: %s" % names)

        table_a = api.table(workflow, node, limit=EXPECTED_JOIN_ROWS)
        dap.expect_eq(len(table_a["data"]), EXPECTED_JOIN_ROWS, "joined rows in payload")
        dap.expect_eq(len(table_a["data"][0]), 10, "cells per joined row")
        dap.expect_eq(table_a["data"][0][4], "setosa", "join key of first row")

        api.run_ok(workflow)
        table_b = api.table(workflow, node, limit=EXPECTED_JOIN_ROWS)
        dap.expect_eq(dap.digest(table_b["data"]), dap.digest(table_a["data"]),
                      "joined rows changed between runs")
        return "inner join on species: %d rows, stable digest %s" % (
            EXPECTED_JOIN_ROWS, dap.digest(table_a["data"])[:16])

    sc.run_check("join.joiner3_species_inner_join_deterministic", joiner3)

    def legacy_joiner():
        workflow, left, right = _two_readers(api, "golden-join-joiner2")
        node = api.add_node_ok(workflow, JOINER2)
        api.connect_ok(workflow, left, node, 0, 0)
        api.connect_ok(workflow, right, node, 0, 1)
        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is False, "expected Joiner2 to refuse without predicates")
        states = {n["name"]: n for n in body.get("nodes", [])}
        joiner = states.get("Joiner", {})
        dap.expect_eq(joiner.get("state"), "IDLE", "Joiner2 state")
        dap.expect_contains(joiner.get("message"), "joining column pair", "Joiner2 message")
        return "documented: Joiner2 cannot be configured (left/rightTableJoinPredicate lists " \
               "have no template) and reports %r" % joiner.get("message")

    sc.run_check("join.legacy_joiner_requires_unconfigurable_predicates", legacy_joiner)
