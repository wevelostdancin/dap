#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""End-to-end check of the checked-in recipe library on the demo CSV files.

The MCP adapter is started over stdio; on its first tool call it launches the
native engine from engine-native/target/dap-native.jar. Every case ingests one
CSV with dap_ingest and runs the matching recipe with dap_recipe_run:

    dap_ingest(file) -> dap_recipe_run(name, dataset=<parquet>,
                                      params={"report": <temp.md>})

A case passes when the tool has no MCP error, the recipe table has rows and
columns, and the Markdown report file was written. All eight cases must pass.

    python3 tests/recipe_library_e2e.py [--port 7932] [--timeout 600]
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

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = ROOT / "examples" / "data"
RECIPES = ROOT / "recipes"

# (recipe, demo CSV, expected output node columns)
CASES = [
    ("data_quality", "quality.csv", {"check", "status", "failed_rows"}),
    ("cohort_analysis", "cohort.csv", {"cohort", "period", "users"}),
    ("ab_test", "ab_test.csv", {"control_rate", "treatment_rate", "p_value"}),
    ("time_series", "timeseries.csv", {"time", "forecast", "method"}),
    ("segmentation", "segmentation.csv", {"cluster", "Mean(age)"}),
    ("regression", "regression.csv", {"term", "coefficient"}),
    ("funnel", "funnel.csv", {"step", "users", "conversion_pct"}),
    ("exec_report", "sales.csv", {"region", "Sum(amount)"}),
]


def step(message):
    print("[recipe-library-e2e] " + message, flush=True)


def call_tool(client, name, arguments, timeout):
    """A tools/call that also checks the MCP isError flag before parsing JSON."""
    result = client.request("tools/call", {"name": name, "arguments": arguments},
                            timeout, what="%s %s" % (name, arguments))
    shared.check(result.get("isError") is not True,
                 "%s returned isError: %r" % (name, result))
    content = result.get("content")
    shared.check(isinstance(content, list) and content and content[0].get("type") == "text",
                 "%s returned no text content: %r" % (name, result))
    try:
        return json.loads(content[0]["text"])
    except json.JSONDecodeError as error:
        raise shared.Failure("%s returned invalid JSON: %s" % (name, error))


def scenario(client, workdir, deadline):
    remaining = lambda reserve: max(5.0, min(120.0, deadline - time.monotonic() - reserve))

    init = client.request("initialize", {
        "protocolVersion": shared.PROTOCOL,
        "capabilities": {},
        "clientInfo": {"name": "recipe-library-e2e", "version": "1.0"},
    }, 10)
    shared.check(init.get("protocolVersion") == shared.PROTOCOL,
                 "unexpected protocol version: %r" % (init.get("protocolVersion"),))
    client.notify("notifications/initialized")

    tools = client.request("tools/list", {}, 10).get("tools") or []
    names = {tool.get("name") for tool in tools}
    shared.check({"dap_ingest", "dap_recipe_run"} <= names,
                 "tools/list misses dap_ingest or dap_recipe_run")
    listed = call_tool(client, "dap_recipe_list", {}, 10).get("recipes") or []
    listed_names = {entry.get("name") for entry in listed}
    expected = {name for name, _, _ in CASES}
    shared.check(expected <= listed_names,
                 "dap_recipe_list misses %s; got %s"
                 % (sorted(expected - listed_names), sorted(listed_names)))
    step("library: %d recipes listed" % len(listed))

    for index, (name, csv_name, expected_columns) in enumerate(CASES, 1):
        source = DATA / csv_name
        shared.check(source.is_file(), "demo CSV not found: %s" % source)
        recipe_file = RECIPES / (name + ".json")
        shared.check(recipe_file.is_file(), "recipe not found: %s" % recipe_file)
        report = workdir / (name + ".md")
        step("case %d/%d: %s <- %s" % (index, len(CASES), name, csv_name))

        ingested = call_tool(client, "dap_ingest", {"file": str(source)}, remaining(120))
        shared.check(ingested.get("rows", 0) > 0,
                     "%s: dap_ingest returned no rows: %r" % (name, ingested))
        dataset = ingested.get("path")
        shared.check(dataset and pathlib.Path(dataset).is_file(),
                     "%s: dap_ingest returned no parquet path: %r" % (name, ingested))

        arguments = {"name": name, "dataset": dataset, "params": {"report": str(report)}}
        if name == "exec_report":
            arguments["params"]["chart"] = str(workdir / "chart.svg")
        run = call_tool(client, "dap_recipe_run", arguments, remaining(120))
        shared.check(run.get("ok") is not False,
                     "%s: recipe run reported ok=false: %r" % (name, run))
        table = run.get("table") or {}
        rows = table.get("rows")
        columns = [column.get("name") for column in table.get("columns") or []]
        shared.check(isinstance(rows, int) and rows > 0,
                     "%s: recipe table rows=%r, expected > 0" % (name, rows))
        shared.check(expected_columns <= set(columns),
                     "%s: recipe table misses %s; got %s"
                     % (name, sorted(expected_columns - set(columns)), columns))
        applied = run.get("applied") or {}
        shared.check((applied.get("dataset") or {}).get("value") == dataset,
                     "%s: dataset binding not reported: %r" % (name, applied))
        shared.check((applied.get("report") or {}).get("value") == str(report),
                     "%s: report binding not reported: %r" % (name, applied))
        shared.check(report.is_file() and report.stat().st_size > 0,
                     "%s: Markdown report not written: %s" % (name, report))
        if name == "exec_report":
            shared.check((workdir / "chart.svg").is_file(),
                         "exec_report: chart not written")
        step("  ok: table %d rows, columns %s; report %d bytes"
             % (rows, ",".join(columns), report.stat().st_size))
    step("all %d recipes passed" % len(CASES))


def run(port, timeout):
    deadline = time.monotonic() + timeout
    shared.check(shared.ADAPTER.is_file(), "adapter not found: %s" % shared.ADAPTER)
    shared.check(shared.JAR.is_file(),
                 "native jar not found: %s (run make build-native)" % shared.JAR)
    for _, csv_name, _ in CASES:
        shared.check((DATA / csv_name).is_file(), "missing demo CSV: %s" % csv_name)
    for name, _, _ in CASES:
        shared.check((RECIPES / (name + ".json")).is_file(), "missing recipe: %s" % name)
    chosen = shared.pick_port(port)
    base = "http://127.0.0.1:%d" % chosen
    shared.check(not shared.health(base),
                 "port %d already serves a DAP engine; refusing to reuse it" % chosen)
    step("adapter=%s port=%d DAP_HOME=%s timeout=%ss"
         % (shared.ADAPTER.relative_to(ROOT), chosen, ROOT, timeout))

    workdir = pathlib.Path(tempfile.mkdtemp(prefix="dap-recipe-library-e2e-"))
    log_path = workdir / "adapter-stderr.log"
    env = dict(os.environ)
    env.update({
        "DAP_HOME": str(ROOT),
        "DAP_PORT": str(chosen),
        "DAP_BIND": "127.0.0.1",
        "DAP_SESSION": "mcp-e2e",
    })
    java_home = shared.find_java_home()
    shared.check(java_home is not None, "JDK 21 not found (set JAVA_HOME)")
    env["JAVA_HOME"] = java_home
    step("JAVA_HOME=%s" % java_home)

    log = open(log_path, "w+", encoding="utf-8")
    process = subprocess.Popen(
        [sys.executable, str(shared.ADAPTER)],
        cwd=str(ROOT), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=log, text=True, encoding="utf-8", bufsize=1, env=env,
    )
    failed = True
    try:
        client = shared.Mcp(process, deadline)
        scenario(client, workdir, deadline)
        shared.check(shared.health(base), "engine health check failed after the scenario")
        failed = False
    finally:
        step("shutting the engine down")
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
        cleanup_error = None
        if not down:
            if failed:
                print("recipe-library-e2e: WARNING: engine still running after cleanup",
                      file=sys.stderr)
            else:
                cleanup_error = shared.Failure("engine still answers /v1/health after cleanup")
        shared.stop_adapter(process)
        if shared.health(base):
            shared.shutdown(base)
            shared.wait_engine_down(base, 10)
        if shared.health(base):
            if failed:
                print("recipe-library-e2e: WARNING: engine still running after cleanup",
                      file=sys.stderr)
            elif cleanup_error is None:
                cleanup_error = shared.Failure("engine still answers /v1/health after cleanup")
        log.flush()
        log.close()
        if failed:
            text = log_path.read_text(encoding="utf-8", errors="replace")
            if text.strip():
                print("--- adapter stderr ---", file=sys.stderr)
                print(text[-4000:], file=sys.stderr)
                print("----------------------", file=sys.stderr)
        shutil.rmtree(workdir, ignore_errors=True)
        if cleanup_error is not None:
            raise cleanup_error
    step("PASS")


def main(argv):
    parser = argparse.ArgumentParser(
        description="Run all eight recipes on the demo CSV files via stdio MCP")
    parser.add_argument("--port", type=int, default=7932,
                        help="preferred loopback port (default: 7932; a free port is used if busy)")
    parser.add_argument("--timeout", type=float, default=600.0,
                        help="whole-run timeout in seconds (default: 600)")
    args = parser.parse_args(argv)
    try:
        run(args.port, args.timeout)
    except shared.Failure as error:
        print("recipe-library-e2e: FAIL: %s" % (error,), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("recipe-library-e2e: interrupted", file=sys.stderr)
        return 130
    except Exception:  # noqa: BLE001 - a crash is still a test failure
        traceback.print_exc()
        print("recipe-library-e2e: FAIL: unexpected exception", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))