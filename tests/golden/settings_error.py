#!/usr/bin/env python3
"""Golden: settings validation errors are explicit, actionable and typed."""

import dap_client as dap

RDS = "org.knime.base.node.io.filehandling.csv.sample.ReadDatasetNodeFactory"


def run(api, sc):
    workflow = api.create_workflow("golden-settings")
    node = api.add_node_ok(workflow, RDS, {"dataset": "IRIS"})

    def unknown_key():
        response = api.configure(workflow, node, {"definitely_unknown_key": "x"})
        dap.expect_eq(response.status, 400, "status")
        error = str(response.body.get("error", ""))
        dap.expect_contains(error, "unknown setting", "error text")
        dap.expect_contains(error, "'definitely_unknown_key'", "error text")
        dap.expect_contains(error, "available", "error text must list available keys")
        dap.expect_contains(error, "dataset", "available keys must include dataset")
        return "HTTP 400: %s" % error

    sc.run_check("settings_error.unknown_key_400_with_available", unknown_key)

    def wrong_shape():
        response = api.configure(workflow, node, {"dataset": {"nested": True}})
        dap.expect_eq(response.status, 400, "status")
        error = str(response.body.get("error", ""))
        dap.expect_contains(error, "is a value, not a structure", "error text")
        return "HTTP 400: %s" % error

    sc.run_check("settings_error.scalar_value_rejects_object", wrong_shape)

    def valid_patch():
        api.configure(workflow, node, {"dataset": "IRIS"}).require()
        body = api.run_ok(workflow)
        dap.expect(body.get("ok") is True, "run after PATCH ok=false: %s" % body)
        dap.expect_eq(api.profile(workflow, node)["rows"], 150, "rows after PATCH")
        return "PATCH accepted, workflow still produces 150 rows"

    sc.run_check("settings_error.valid_patch_still_works", valid_patch)

    def missing_factory():
        response = api.post("/v1/workflows/%s/nodes" % workflow, {})
        dap.expect_eq(response.status, 400, "status")
        dap.expect_contains(str(response.body.get("error", "")), "'factory' is required",
                            "error text")
        return "HTTP 400: factory is required"

    sc.run_check("settings_error.missing_factory_400", missing_factory)

    def unknown_factory():
        response = api.post("/v1/workflows/%s/nodes" % workflow,
                            {"factory": "org.example.NoSuchNodeFactory"})
        dap.expect_eq(response.status, 400, "status")
        dap.expect_contains(str(response.body.get("error", "")), "unknown node", "error text")
        return "HTTP 400: unknown node"

    sc.run_check("settings_error.unknown_factory_400", unknown_factory)

    def unknown_objects_404():
        dap.expect_eq(api.node("org.example.NoSuchNode").status, 404, "unknown node detail")
        dap.expect_eq(api.get("/v1/workflows/wf-999999/nodes").status, 404, "unknown workflow")
        dap.expect_eq(api.job("job-999999").status, 404, "unknown job")
        return "unknown node/workflow/job -> 404"

    sc.run_check("settings_error.unknown_objects_404", unknown_objects_404)

    def invalid_session():
        response = api.get("/v1/health", session="bad session!")
        dap.expect_eq(response.status, 400, "status")
        dap.expect_contains(str(response.body.get("error", "")), "invalid session id",
                            "error text")
        return "HTTP 400: invalid session id"

    sc.run_check("settings_error.invalid_session_400", invalid_session)
