#!/usr/bin/env python3
"""End-to-end check of the adaptability layer against the native engine.

The adapter starts "engine-native/target/dap-native.jar" on a free loopback port
on the first tool call (as in tests/mcp_e2e.py). The scenario covers MCP
resources, prompts, recipes and dap_sql:

    initialize (resources + prompts advertised) -> tools/list
    -> resources/list, resources/read dap://recipes and dap://recipes/<name>
    -> prompts/get analyze_csv
    -> dap_ingest (1 tool call)
    -> dap_recipe_run with dataset + params (2 tool calls, table + Markdown report)
    -> dap_recipe_list / dap_recipe_save
    -> dap_sql over the ingested table

The measured scenario is two tool calls (the acceptance budget is three); the
extra checks run afterwards. The whole run is bounded by --timeout.

    python3 tests/mcp_recipe_e2e.py [--port 7924] [--timeout 180]
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import traceback

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import mcp_e2e as shared  # noqa: E402 - sibling e2e helpers (client, cleanup, ports)

RECIPE_NAME = "sales_summary"
REQUIRED_TOOLS = {
    "dap_ingest", "dap_recipe_list", "dap_recipe_save", "dap_recipe_run", "dap_sql", "dap_analyze",
}


def step(message):
    print("[mcp-recipe-e2e] " + message, flush=True)


def write_csv(directory):
    source = pathlib.Path(directory) / "sales.csv"
    source.write_text(
        "region,amount\n"
        "north,10.5\n"
        "south,20.0\n"
        "north,5.25\n",
        encoding="utf-8",
    )
    return source


def recipe_document():
    return {
        "format": "dap-native-workflow/1",
        "name": RECIPE_NAME,
        "nodes": [
            {"id": "src", "factory": "dap.parquet_reader", "name": "Parquet Reader",
             "settings": {"file": "/data/sales.parquet"}},
            {"id": "q", "factory": "dap.sql_query", "name": "SQL Query",
             "settings": {
                 "sql": ("SELECT region, SUM(amount) AS total FROM src "
                         "GROUP BY region ORDER BY region"),
                 "inputs": ["src"],
             }},
            {"id": "report", "factory": "dap.markdown_report", "name": "Markdown Report",
             "settings": {
                 "file": "report.md",
                 "title": "Sales summary",
                 "sections": [{"kind": "table"}, {"kind": "metrics"}],
             }},
        ],
        "connections": [
            {"from": "src", "fromPort": 0, "to": "q", "toPort": 0},
            {"from": "q", "fromPort": 0, "to": "report", "toPort": 0},
        ],
        "recipe": {
            "name": RECIPE_NAME,
            "description": "Aggregate sales by region and write a Markdown report",
            "tags": ["sales", "report"],
            "dataset": {"node": "src", "setting": "file"},
            "params": {"report": {"node": "report", "setting": "file"}},
            "output": "report",
        },
    }


def write_recipe(home):
    directory = pathlib.Path(home) / "recipes"
    directory.mkdir(parents=True)
    path = directory / (RECIPE_NAME + ".json")
    path.write_text(json.dumps(recipe_document(), ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")
    return path


def contents(client, uri):
    result = client.request("resources/read", {"uri": uri}, 10)
    entries = result.get("contents") or []
    shared.check(len(entries) == 1, "resources/read %s returned %r" % (uri, entries))
    shared.check(entries[0].get("uri") == uri, "unexpected resource uri: %r" % (entries[0],))
    return json.loads(entries[0]["text"])


def scenario(client, source, home, report_path):
    deadline = client.deadline
    step("initialize + capabilities")
    init = client.request("initialize", {
        "protocolVersion": shared.PROTOCOL,
        "capabilities": {},
        "clientInfo": {"name": "mcp-recipe-e2e", "version": "1.0"},
    }, 10)
    capabilities = init.get("capabilities") or {}
    shared.check(capabilities.get("resources") == {"listChanged": False},
                 "initialize does not advertise resources: %r" % (capabilities,))
    shared.check(capabilities.get("prompts") == {"listChanged": False},
                 "initialize does not advertise prompts: %r" % (capabilities,))
    client.notify("notifications/initialized")

    step("tools/list")
    tools = client.request("tools/list", {}, 10).get("tools") or []
    names = {tool.get("name") for tool in tools}
    shared.check(len(tools) >= 26, "tools/list returned %d tools, expected >= 26" % len(tools))
    missing = REQUIRED_TOOLS - names
    shared.check(not missing, "tools/list misses %s" % sorted(missing))

    step("resources/list + resources/read")
    resources = client.request("resources/list", {}, 10).get("resources") or []
    uris = {item.get("uri") for item in resources}
    shared.check({"dap://catalog", "dap://recipes"} <= uris,
                 "resources/list misses the catalog or recipes: %s" % sorted(uris))
    catalog = contents(client, "dap://catalog")
    shared.check(catalog.get("count") == len(catalog.get("nodes") or []),
                 "catalog count does not match nodes: %r" % (catalog.get("count"),))
    shared.check(any(node.get("id") == "dap.sql_query" for node in catalog["nodes"]),
                 "catalog has no dap.sql_query node")
    step("catalog: %d nodes" % catalog["count"])

    recipes = contents(client, "dap://recipes").get("recipes") or []
    shared.check([entry.get("name") for entry in recipes] == [RECIPE_NAME],
                 "dap://recipes does not list %s: %r" % (RECIPE_NAME, recipes))
    document = contents(client, "dap://recipes/" + RECIPE_NAME)
    shared.check(document.get("format") == "dap-native-workflow/1",
                 "recipe is not a workflow document: %r" % (document.get("format"),))
    shared.check((document.get("recipe") or {}).get("name") == RECIPE_NAME,
                 "recipe block missing the name")
    shared.check((document.get("recipe") or {}).get("params", {}).get("report"),
                 "recipe block missing the report parameter")

    step("prompts/list + prompts/get")
    prompts = client.request("prompts/list", {}, 10).get("prompts") or []
    prompt_names = {prompt.get("name") for prompt in prompts}
    shared.check({"analyze_csv", "quality_check", "profile_report"} <= prompt_names,
                 "prompts/list misses a prompt: %s" % sorted(prompt_names))
    prompt = client.request("prompts/get", {
        "name": "analyze_csv", "arguments": {"path": str(source)},
    }, 10)
    messages = prompt.get("messages") or []
    shared.check(messages and messages[0].get("role") == "user",
                 "prompts/get returned no user message: %r" % (prompt,))
    text = messages[0]["content"]["text"]
    shared.check(str(source) in text and "dap_ingest" in text,
                 "analyze_csv prompt does not mention the path and pipeline")

    tool_calls = 0
    step("tool call 1/3: dap_ingest")
    ingested = client.tool("dap_ingest", {"file": str(source)}, 60)
    tool_calls += 1
    shared.check(ingested.get("rows") == 3, "ingest rows=%r, expected 3" % (ingested.get("rows"),))
    cached = ingested.get("path")
    shared.check(cached and pathlib.Path(cached).is_file(), "ingest returned no cache path")

    step("tool call 2/3: dap_recipe_run (dataset + params)")
    run = client.tool("dap_recipe_run", {
        "name": RECIPE_NAME,
        "dataset": cached,
        "params": {"report": str(report_path)},
    }, 90)
    tool_calls += 1
    table = run.get("table") or {}
    shared.check(table.get("rows") == 2, "recipe table rows=%r, expected 2" % (table.get("rows"),))
    columns = [column.get("name") for column in table.get("columns") or []]
    shared.check(columns == ["region", "total"], "unexpected recipe columns: %r" % (columns,))
    rows = table.get("data") or []
    shared.check([row[0] for row in rows] == ["north", "south"],
                 "unexpected recipe rows: %r" % (rows,))
    shared.check(abs(float(rows[0][1]) - 15.75) < 1e-9,
                 "north total=%r, expected 15.75" % (rows[0][1],))
    shared.check(run.get("applied", {}).get("dataset", {}).get("value") == cached,
                 "recipe run did not report the bound dataset: %r" % (run.get("applied"),))
    shared.check(tool_calls <= 3, "scenario used %d tool calls, budget is 3" % tool_calls)

    step("Markdown report written by dap.markdown_report")
    shared.check(report_path.is_file(), "markdown report not written: %s" % report_path)
    report = report_path.read_text(encoding="utf-8")
    for expected in ("# Sales summary", "north", "south", "|"):
        shared.check(expected in report, "report is missing %r" % (expected,))
    step("scenario: %d tool calls, table + report ok" % tool_calls)

    step("dap_recipe_list + dap_recipe_save")
    listed = client.tool("dap_recipe_list", {}, 10).get("recipes") or []
    shared.check([entry.get("name") for entry in listed] == [RECIPE_NAME],
                 "dap_recipe_list returned %r" % (listed,))
    copy_name = RECIPE_NAME + "_copy"
    saved = client.tool("dap_recipe_save", {
        "name": copy_name,
        "workflow": document,
        "description": "Copy saved by the e2e",
        "tags": ["copy"],
    }, 10)
    shared.check(saved.get("name") == copy_name, "dap_recipe_save returned %r" % (saved,))
    shared.check((pathlib.Path(home) / "recipes" / (copy_name + ".json")).is_file(),
                 "dap_recipe_save wrote no file")
    listed = client.tool("dap_recipe_list", {}, 10).get("recipes") or []
    shared.check(copy_name in [entry.get("name") for entry in listed],
                 "dap_recipe_list misses the saved copy")

    step("dap_sql over the ingested table")
    workflow = client.tool("dap_workflow_create", {"name": "mcp-recipe-sql"}, 10)["id"]
    try:
        reader = client.tool("dap_add_node", {
            "workflow": workflow,
            "factory": "dap.parquet_reader",
            "settings": {"file": cached},
        }, 15)["node"]
        result = client.tool("dap_sql", {
            "workflow": workflow,
            "node": reader,
            "sql": 'SELECT region, SUM(amount) AS total FROM "%s" '
                   "GROUP BY region ORDER BY region" % reader,
        }, 90)
        sql_table = result.get("table") or {}
        shared.check(sql_table.get("rows") == 2,
                     "dap_sql rows=%r, expected 2" % (sql_table.get("rows"),))
        sql_rows = sql_table.get("data") or []
        shared.check([row[0] for row in sql_rows] == ["north", "south"],
                     "dap_sql rows: %r" % (sql_rows,))
        shared.check(abs(float(sql_rows[1][1]) - 20.0) < 1e-9,
                     "dap_sql south total=%r, expected 20.0" % (sql_rows[1][1],))
    finally:
        client.tool("dap_delete_workflow", {"workflow": workflow}, 10)
    step("dap_sql table ok")

    step("dap_analyze over the ingested parquet")
    analyzed = client.tool("dap_analyze", {
        "dataset": cached,
        "sql": "SELECT region, SUM(amount) AS total FROM src GROUP BY region ORDER BY region",
    }, 90)
    analyzed_table = analyzed.get("table") or {}
    shared.check(analyzed_table.get("rows") == 2,
                 "dap_analyze rows=%r, expected 2" % (analyzed_table.get("rows"),))
    shared.check([row[0] for row in (analyzed_table.get("data") or [])] == ["north", "south"],
                 "dap_analyze rows: %r" % (analyzed_table.get("data"),))
    shared.check((analyzed.get("profile") or {}).get("rows") == 3,
                 "dap_analyze profile rows=%r, expected 3"
                 % ((analyzed.get("profile") or {}).get("rows"),))
    shared.check(analyzed.get("cached") is False,
                 "dap_analyze cached=%r, expected False for a dataset source"
                 % (analyzed.get("cached"),))
    step("dap_analyze ok")


def run(port, timeout):
    deadline = time.monotonic() + timeout
    shared.check(shared.ADAPTER.is_file(), "adapter not found: %s" % shared.ADAPTER)
    shared.check(shared.JAR.is_file(),
                 "native jar not found: %s (run make build-native)" % shared.JAR)
    chosen = shared.pick_port(port)
    base = "http://127.0.0.1:%d" % chosen
    shared.check(not shared.health(base),
                 "port %d already serves a DAP engine; refusing to reuse it" % chosen)
    step("adapter=%s port=%d timeout=%ss"
                % (shared.ADAPTER.relative_to(shared.ROOT), chosen, timeout))

    home = tempfile.mkdtemp(prefix="dap-recipe-e2e-")
    try:
        jar_dir = pathlib.Path(home) / "engine-native" / "target"
        jar_dir.mkdir(parents=True)
        (jar_dir / "dap-native.jar").symlink_to(shared.JAR)
        source = write_csv(home)
        write_recipe(home)
        report_path = pathlib.Path(home) / "report.md"
        env = dict(os.environ)
        env.update({
            "DAP_HOME": home,
            "DAP_PORT": str(chosen),
            "DAP_BIND": "127.0.0.1",
            "DAP_SESSION": "mcp-recipe-e2e",
        })
        java_home = shared.find_java_home()
        if java_home:
            env["JAVA_HOME"] = java_home
        else:
            step("JAVA_HOME not found; relying on java from PATH")

        log_path = pathlib.Path(home) / "adapter-stderr.log"
        log = open(log_path, "w+", encoding="utf-8")
        process = subprocess.Popen(
            [sys.executable, str(shared.ADAPTER)],
            cwd=home,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=log,
            text=True,
            encoding="utf-8",
            bufsize=1,
            env=env,
        )
        failed = True
        try:
            client = shared.Mcp(process, deadline)
            scenario(client, source, home, report_path)
            shared.check(shared.health(base), "engine health check failed after the scenario")
            failed = False
        finally:
            step("shutting the engine down")
            cleanup_error = None
            log.flush()
            launched = shared.launched_engine_pids(log_path)
            stopped, body = shared.shutdown(base)
            if stopped:
                step("shutdown ok: %s" % (body,))
            else:
                step("WARNING: /v1/shutdown failed: %s" % (body,))
            terminated = []
            if not stopped:
                for pid in launched:
                    if shared.terminate_engine(pid):
                        terminated.append(pid)
                        step("terminated launched engine pid %d" % pid)
            down = shared.wait_engine_down(base, 10)
            if not down and launched:
                for pid in launched:
                    if pid not in terminated and shared.terminate_engine(pid):
                        terminated.append(pid)
                        step("terminated launched engine pid %d" % pid)
                down = shared.wait_engine_down(base, 5)
            if not down:
                if failed:
                    print("mcp-recipe-e2e: WARNING: engine is still running after cleanup",
                          file=sys.stderr)
                else:
                    cleanup_error = shared.Failure(
                        "engine still answers /v1/health after cleanup")
            elif not stopped and not terminated and not failed:
                cleanup_error = shared.Failure("/v1/shutdown failed: %s" % (body,))
            shared.stop_adapter(process)
            if shared.health(base):
                shared.shutdown(base)
                shared.wait_engine_down(base, 10)
            if shared.health(base) and failed:
                print("mcp-recipe-e2e: WARNING: engine is still running after cleanup",
                      file=sys.stderr)
            elif shared.health(base) and cleanup_error is None:
                cleanup_error = shared.Failure("engine still answers /v1/health after cleanup")
            log.flush()
            log.close()
            if failed:
                text = log_path.read_text(encoding="utf-8", errors="replace")
                if text.strip():
                    print("--- adapter stderr ---", file=sys.stderr)
                    print(text[-4000:], file=sys.stderr)
                    print("----------------------", file=sys.stderr)
            log_path.unlink(missing_ok=True)
            if cleanup_error is not None:
                raise cleanup_error
    finally:
        shutil.rmtree(home, ignore_errors=True)
    step("PASS")


def main(argv):
    parser = argparse.ArgumentParser(
        description="MCP resources/prompts/recipes/dap_sql end-to-end check on dap-native")
    parser.add_argument("--port", type=int, default=7924,
                        help="preferred loopback port (default: 7924; a free port is used if busy)")
    parser.add_argument("--timeout", type=float, default=180.0,
                        help="whole-run timeout in seconds (default: 180)")
    args = parser.parse_args(argv)
    try:
        run(args.port, args.timeout)
    except shared.Failure as error:
        print("mcp-recipe-e2e: FAIL: %s" % (error,), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("mcp-recipe-e2e: interrupted", file=sys.stderr)
        return 130
    except Exception:  # noqa: BLE001 - a crash is still a test failure
        traceback.print_exc()
        print("mcp-recipe-e2e: FAIL: unexpected exception", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
