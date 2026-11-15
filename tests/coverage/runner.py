#!/usr/bin/env python3
"""Coverage harness for the 69-task analytics taxonomy (spec section 6.2).

Every task in `tests/coverage/tasks.json` is backed by a scenario in
`tests/coverage/*.json`. The harness starts the engine on a free port with its own
temporary `DAP_HOME`, runs each scenario end to end through `/v1` and compares
rows, columns and values with the golden tolerance rules (docs/QUALITY.md).

A task is covered only when its scenario passes; scenarios whose capabilities are
unavailable (`requires`: sqlite_scanner, httpfs, network) are reported as skipped,
not failed. The command prints `coverage: N/69` and exits non-zero below 67.

Usage:
    python3 tests/coverage/runner.py              # start an engine and run all tasks
    python3 tests/coverage/runner.py --validate   # structural checks, no engine
    python3 tests/coverage/runner.py --base URL   # use a running engine
    python3 tests/coverage/runner.py --only A1    # limit to task ids with a prefix
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import pathlib
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
COVERAGE_DIR = ROOT / "tests" / "coverage"
GOLDENS_DIR = ROOT / "tests" / "goldens-native"
DATA_DIR = GOLDENS_DIR / "data"
JAR = ROOT / "engine-native" / "target" / "dap-native.jar"
HOST = "127.0.0.1"
TASKS_FORMAT = "dap-coverage-tasks/1"
SCENARIO_FORMAT = "dap-coverage-scenario/1"
REQUIRED_PASS = 67
START_TIMEOUT = 60.0
PASS, FAIL, SKIP, UNCOVERED = "PASS", "FAIL", "SKIP", "UNCOVERED"
CHECK_KINDS = {"expect", "expect_profile"}


class CoverageError(RuntimeError):
    """A structural problem in the manifest or a scenario file."""


class CaseFailure(AssertionError):
    """A scenario ran but did not match its pinned expectations."""


# --------------------------------------------------------------- goldens glue


def load_goldens():
    """Import the golden runner to reuse its binding and comparison rules."""
    spec = importlib.util.spec_from_file_location("dap_goldens_runner",
                                                  GOLDENS_DIR / "runner.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def java_binary() -> str:
    home = (os.environ.get("DAP_JDK") or os.environ.get("JAVA_HOME")
            or str(pathlib.Path.home() / "Projects/tools/jdk-21.0.13+9/Contents/Home"))
    candidate = pathlib.Path(home) / "bin" / "java"
    if candidate.is_file():
        return str(candidate)
    raise SystemExit("java not found: set DAP_JDK to a JDK 21 home")


# ------------------------------------------------------------------- manifest


def load_manifest():
    path = COVERAGE_DIR / "tasks.json"
    if not path.is_file():
        raise CoverageError("missing %s" % path)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise CoverageError("%s is not valid JSON: %s" % (path, exc)) from exc
    if data.get("format") != TASKS_FORMAT:
        raise CoverageError("%s: expected format %r" % (path, TASKS_FORMAT))
    tasks = data.get("tasks")
    if not isinstance(tasks, list):
        raise CoverageError("%s: tasks[] is required" % path)
    return tasks


def load_scenarios():
    """Read every scenario file and index its cases by taxonomy task id."""
    cases = {}
    problems = []
    for path in sorted(COVERAGE_DIR.glob("*.json")):
        if path.name == "tasks.json":
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except ValueError as exc:
            problems.append("%s: invalid JSON: %s" % (path.name, exc))
            continue
        if data.get("format") != SCENARIO_FORMAT:
            problems.append("%s: expected format %r" % (path.name, SCENARIO_FORMAT))
            continue
        for case in data.get("cases") or []:
            task = case.get("task")
            if not task:
                problems.append("%s: case without task" % path.name)
                continue
            if task in cases:
                problems.append("%s: duplicate task %r (also in %s)"
                                % (path.name, task, cases[task][0]))
                continue
            cases[task] = (path.name, case)
    return cases, problems


def _validate_workflow(label, workflow, datasets):
    problems = []
    nodes = workflow.get("nodes")
    if not isinstance(nodes, list) or not nodes:
        return ["%s: workflow needs nodes[]" % label]
    node_ids = set()
    for node in nodes:
        node_id = node.get("id")
        if not node_id:
            problems.append("%s: node without id" % label)
            continue
        if node_id in node_ids:
            problems.append("%s: duplicate node id %r" % (label, node_id))
        node_ids.add(node_id)
        node_type = node.get("type")
        if node_type not in LOGICAL_TYPES:
            problems.append("%s/%s: unknown type %r" % (label, node_id, node_type))
        config = node.get("config") or {}
        if "dataset" in config and config["dataset"] not in datasets:
            problems.append("%s/%s: unknown dataset %r"
                            % (label, node_id, config["dataset"]))
    for edge in workflow.get("edges") or []:
        for side in ("from", "to"):
            if edge.get(side) not in node_ids:
                problems.append("%s: edge references unknown node %r"
                                % (label, edge.get(side)))
    return problems


def _validate_checks(label, workflow, checks):
    problems = []
    node_ids = {node.get("id") for node in workflow.get("nodes") or []}
    if not isinstance(checks, list) or not checks:
        return ["%s: case needs checks[]" % label]
    for check in checks:
        if check.get("node") not in node_ids:
            problems.append("%s: check references unknown node %r"
                            % (label, check.get("node")))
        if check.get("kind") not in CHECK_KINDS:
            problems.append("%s: unknown check kind %r" % (label, check.get("kind")))
        if "rows" not in check:
            problems.append("%s: %s check needs rows" % (label, check.get("kind")))
        for column in check.get("columns") or []:
            if not isinstance(column, dict) or not column.get("name") or not column.get("type"):
                problems.append("%s: check columns need name and type" % label)
    return problems


def validate(tasks, cases, datasets):
    """Structural checks that need no engine; safe to run in CI."""
    problems = []
    if len(tasks) != 69:
        problems.append("tasks.json: expected 69 tasks, found %d" % len(tasks))
    seen = set()
    scenario_names = set()
    for task in tasks:
        task_id = task.get("id")
        if not task_id:
            problems.append("tasks.json: entry without id")
            continue
        if task_id in seen:
            problems.append("tasks.json: duplicate task %r" % task_id)
        seen.add(task_id)
        if task.get("kind") not in ("node", "recipe"):
            problems.append("%s: kind must be node or recipe" % task_id)
        if task.get("not_covered"):
            continue
        scenario = task.get("scenario")
        if not scenario:
            problems.append("%s: needs scenario or not_covered" % task_id)
            continue
        scenario_names.add(scenario)
        if not (COVERAGE_DIR / scenario).is_file():
            problems.append("%s: scenario file %r is missing" % (task_id, scenario))
            continue
        if task_id not in cases:
            problems.append("%s: %s has no case for this task" % (task_id, scenario))
    for task_id, (scenario, _case) in cases.items():
        if task_id not in seen:
            problems.append("%s: case in %s is not in tasks.json" % (task_id, scenario))
    for task_id, (scenario, case) in cases.items():
        label = "%s/%s" % (scenario, task_id)
        if not case.get("title"):
            problems.append("%s: case needs a title" % label)
        if case.get("kind") not in ("node", "recipe"):
            problems.append("%s: kind must be node or recipe" % label)
        if case.get("mode") == "save_load":
            workflow = case.get("workflow")
            if not isinstance(workflow, dict):
                problems.append("%s: save_load case needs workflow" % label)
                continue
            problems.extend(_validate_workflow(label, workflow, datasets))
            problems.extend(_validate_checks(label, workflow, case.get("checks")))
            continue
        steps = case.get("steps")
        if steps is not None:
            for index, step in enumerate(steps):
                step_label = "%s step %d" % (label, index + 1)
                workflow = step.get("workflow")
                if not isinstance(workflow, dict):
                    problems.append("%s: step needs workflow" % step_label)
                    continue
                problems.extend(_validate_workflow(step_label, workflow, datasets))
                problems.extend(_validate_checks(step_label, workflow, step.get("checks")))
            continue
        workflow = case.get("workflow")
        if not isinstance(workflow, dict):
            problems.append("%s: case needs workflow or steps" % label)
            continue
        problems.extend(_validate_workflow(label, workflow, datasets))
        problems.extend(_validate_checks(label, workflow, case.get("checks")))
    return problems


# --------------------------------------------------------------- placeholders


def substitute(value, tmpdir):
    if isinstance(value, str):
        return value.replace("{DATA}", str(DATA_DIR.resolve())).replace("{TMP}", str(tmpdir))
    if isinstance(value, list):
        return [substitute(item, tmpdir) for item in value]
    if isinstance(value, dict):
        return {key: substitute(item, tmpdir) for key, item in value.items()}
    return value


def binding(goldens, node, datasets, tmpdir):
    """Map one scenario node onto a dap.* factory plus resolved settings."""
    node_type = node["type"]
    config = substitute(node.get("config") or {}, tmpdir)
    if node_type in ("table_source", "csv_reader", "excel_reader") and "dataset" in config:
        return goldens.native_binding(node_type, config, datasets)
    if node_type == "table_source":
        raise CoverageError("table_source needs a dataset")
    return "dap." + node_type, config


# ---------------------------------------------------------------- engine boot


def extension_dir():
    configured = os.environ.get("DAP_DUCKDB_EXTENSION_DIR")
    if configured:
        return pathlib.Path(configured)
    candidate = ROOT / "build" / "duckdb-ext"
    return candidate if candidate.is_dir() else None


def extension_available(name):
    directory = extension_dir()
    if directory is None or not directory.is_dir():
        return False
    return any(path.name.startswith(name) and path.suffix == ".duckdb_extension"
               for path in directory.rglob("*.duckdb_extension"))


def network_available(case):
    """Probe every URL referenced by the case; no URL means no network needed."""
    text = json.dumps(case)
    hosts = set(re.findall(r"https?://([^/'\"\s]+)", text))
    for host in hosts:
        hostname = host.rsplit(":", 1)[0]
        try:
            with socket.create_connection((hostname, 443), timeout=2):
                pass
        except OSError:
            return False
    return True


def unavailable_requires(case):
    reasons = []
    for requirement in case.get("requires") or []:
        if requirement == "network":
            if not network_available(case):
                reasons.append("network is unreachable")
        elif not extension_available(requirement):
            reasons.append("DuckDB extension %s is not installed (run make extensions)"
                           % requirement)
    return reasons


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((HOST, 0))
        return sock.getsockname()[1]


def health(base):
    try:
        with urllib.request.urlopen(base + "/v1/health", timeout=1) as response:
            return response.status == 200
    except (urllib.error.URLError, OSError):
        return False


def wait_health(base, proc, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            return False
        if health(base):
            return True
        time.sleep(0.1)
    return False


def start_engine(tmpdir):
    if not JAR.is_file():
        raise SystemExit("missing %s: run make build-native" % JAR)
    port = free_port()
    base = "http://%s:%d" % (HOST, port)
    env = dict(os.environ, DAP_PORT=str(port), DAP_BIND=HOST, DAP_HOME=str(tmpdir),
               DAP_IDLE_TIMEOUT_SEC="0", DAP_DUCKDB_TEMP_DIR=str(tmpdir / "tmp"))
    directory = extension_dir()
    if directory is not None:
        env["DAP_DUCKDB_EXTENSION_DIR"] = str(directory)
    log = (tmpdir / "engine.log").open("wb")
    proc = subprocess.Popen([java_binary(), "-jar", str(JAR)], cwd=str(ROOT),
                            env=env, stdout=log, stderr=subprocess.STDOUT)
    if not wait_health(base, proc, START_TIMEOUT):
        log.close()
        tail = (tmpdir / "engine.log").read_text(errors="replace").splitlines()[-30:]
        proc.kill()
        raise SystemExit("engine did not become healthy in %.0fs\n%s"
                         % (START_TIMEOUT, "\n".join(tail)))
    return proc, base, log


def stop_engine(proc, base, log):
    try:
        request = urllib.request.Request(base + "/v1/shutdown", method="POST", data=b"{}")
        urllib.request.urlopen(request, timeout=5).read()
    except (urllib.error.URLError, OSError):
        pass
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
    log.close()


# ------------------------------------------------------------- case execution


def _add_nodes(api, goldens, workflow, datasets, tmpdir):
    workflow_id = api.create_workflow(workflow["id"])
    nodes = {}
    for node in workflow["nodes"]:
        factory, settings = binding(goldens, node, datasets, tmpdir)
        response = api.add_node(workflow_id, factory, settings)
        if response.status != 200:
            raise CaseFailure("node %s: add %s failed: HTTP %s %s"
                              % (node["id"], factory, response.status, _short(response.body)))
        nodes[node["id"]] = response.body["node"]
    for edge in workflow.get("edges") or []:
        api.connect_ok(workflow_id, nodes[edge["from"]], nodes[edge["to"]],
                       edge.get("fromPort", 0), edge.get("toPort", 0))
    return workflow_id, nodes


def _require_run(api, workflow_id, goldens):
    response = api.run(workflow_id)
    if response.status != 200:
        raise CaseFailure("run failed: HTTP %s %s" % (response.status, _short(response.body)))
    body = response.body or {}
    logical = {}
    for state in body.get("nodes") or []:
        if state.get("state") not in goldens.NATIVE_OK_STATES:
            raise CaseFailure("node %s state %s: %s"
                              % (state.get("node"), state.get("state"),
                                 state.get("message") or "no message"))


def run_step(api, goldens, workflow, checks, datasets, tmpdir):
    """Run one workflow and evaluate its checks; raises CaseFailure on mismatch."""
    workflow = substitute(workflow, tmpdir)
    checks = substitute(checks, tmpdir)
    workflow_id, nodes = _add_nodes(api, goldens, workflow, datasets, tmpdir)
    try:
        _require_run(api, workflow_id, goldens)
        observation = {"status": "ok", "nodes": {}, "checks": {}, "durationMs": None,
                       "checkProblems": []}
        for check in checks:
            goldens._native_check(api, workflow_id,
                                  {"id": workflow["id"], "kind": "native"},
                                  nodes, check, observation, {}, {}, datasets,
                                  set(), set())
        problems = observation["checkProblems"]
        if problems:
            raise CaseFailure("; ".join(problems[:3]))
    finally:
        api.delete_workflow(workflow_id)


def run_save_load(api, goldens, case, datasets, tmpdir):
    """Save a workflow, drop it, load it back from disk and check the copy."""
    workflow = substitute(case["workflow"], tmpdir)
    checks = substitute(case["checks"], tmpdir)
    workflow_id, nodes = _add_nodes(api, goldens, workflow, datasets, tmpdir)
    try:
        save_dir = pathlib.Path(tmpdir) / ("saved-" + workflow["id"])
        api.post("/v1/workflows/%s/save" % workflow_id, {"dir": str(save_dir)}).require()
        api.delete_workflow(workflow_id)
        workflow_id = None
        loaded = api.post("/v1/workflows/load",
                          {"dir": str(save_dir), "name": workflow["id"] + "-copy"}).require()
        loaded_id = loaded["id"]
        loaded_nodes = [entry["node"] for entry in api.workflow_nodes(loaded_id)]
        if len(loaded_nodes) != len(workflow["nodes"]):
            raise CaseFailure("loaded workflow has %d nodes, expected %d"
                              % (len(loaded_nodes), len(workflow["nodes"])))
        mapping = {node["id"]: loaded_nodes[index]
                   for index, node in enumerate(workflow["nodes"])}
        _require_run(api, loaded_id, goldens)
        observation = {"status": "ok", "nodes": {}, "checks": {}, "durationMs": None,
                       "checkProblems": []}
        for check in checks:
            goldens._native_check(api, loaded_id, {"id": workflow["id"], "kind": "native"},
                                  mapping, check, observation, {}, {}, datasets,
                                  set(), set())
        problems = observation["checkProblems"]
        if problems:
            raise CaseFailure("; ".join(problems[:3]))
        workflow_id = loaded_id
    finally:
        if workflow_id is not None:
            api.delete_workflow(workflow_id)


def run_case(api, goldens, case, datasets, tmpdir):
    if case.get("mode") == "save_load":
        run_save_load(api, goldens, case, datasets, tmpdir)
        return
    steps = case.get("steps")
    if steps is None:
        run_step(api, goldens, case["workflow"], case["checks"], datasets, tmpdir)
        return
    for step in steps:
        run_step(api, goldens, step["workflow"], step["checks"], datasets, tmpdir)


# ----------------------------------------------------------------- reporting


def _short(value, limit=300):
    text = json.dumps(value, ensure_ascii=False, default=str)
    return text if len(text) <= limit else text[:limit] + "..."


def run_tasks(api, goldens, tasks, cases, datasets, tmpdir, verbose=False, only=None):
    statuses = {}
    details = {}
    for task in tasks:
        task_id = task["id"]
        if only and not any(task_id.startswith(prefix) for prefix in only):
            continue
        title = task.get("title") or ""
        if task.get("not_covered"):
            statuses[task_id] = UNCOVERED
            details[task_id] = task["not_covered"]
            print("UNCOVERED %-4s %s" % (task_id, title))
            continue
        scenario, case = cases[task_id]
        reasons = unavailable_requires(case)
        if reasons:
            statuses[task_id] = SKIP
            details[task_id] = "; ".join(reasons)
            print("SKIP  %-4s %-46s %s" % (task_id, title, details[task_id]))
            continue
        started = time.monotonic()
        try:
            run_case(api, goldens, case, datasets, tmpdir)
        except (CaseFailure, CoverageError, AssertionError, goldens.NativeError) as exc:
            statuses[task_id] = FAIL
            details[task_id] = str(exc)
            print("FAIL  %-4s %-46s %s" % (task_id, title, details[task_id]))
            continue
        except Exception as exc:  # noqa: BLE001 - a broken case is a failed case
            statuses[task_id] = FAIL
            details[task_id] = "%s: %s" % (type(exc).__name__, exc)
            print("FAIL  %-4s %-46s %s" % (task_id, title, details[task_id]))
            continue
        statuses[task_id] = PASS
        elapsed = (time.monotonic() - started) * 1000
        print("PASS  %-4s %-46s %5.0fms  %s" % (task_id, title, elapsed, scenario))
    return statuses, details


def summarize(statuses, details, total):
    counts = {status: 0 for status in (PASS, FAIL, SKIP, UNCOVERED)}
    for status in statuses.values():
        counts[status] += 1
    passed = counts[PASS]
    print("\ncoverage: %d/%d (PASS %d, FAIL %d, SKIP %d, UNCOVERED %d)"
          % (passed, total, counts[PASS], counts[FAIL], counts[SKIP], counts[UNCOVERED]))
    for status in (FAIL, SKIP, UNCOVERED):
        ids = [task_id for task_id, value in statuses.items() if value == status]
        if not ids:
            continue
        print("%s: %s" % (status.lower() + " tasks", ", ".join(sorted(ids))))
        for task_id in sorted(ids):
            if details.get(task_id):
                print("  %s: %s" % (task_id, details[task_id]))
    if passed < REQUIRED_PASS:
        print("coverage target is %d/69; %d more task(s) must pass"
              % (REQUIRED_PASS, REQUIRED_PASS - passed))
        return 1
    return 0


# ----------------------------------------------------------------------- CLI


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", help="use a running engine instead of starting one")
    parser.add_argument("--validate", action="store_true",
                        help="check the manifest and scenarios without an engine")
    parser.add_argument("--only", action="append", metavar="PREFIX",
                        help="limit to task ids starting with PREFIX")
    parser.add_argument("--keep", action="store_true", help="leave the engine running")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    global LOGICAL_TYPES
    goldens = load_goldens()
    LOGICAL_TYPES = goldens.LOGICAL_TYPES
    datasets = goldens.load_datasets()
    try:
        tasks = load_manifest()
    except CoverageError as exc:
        print("coverage: %s" % exc, file=sys.stderr)
        return 2
    cases, problems = load_scenarios()
    problems.extend(validate(tasks, cases, datasets))
    if problems:
        for problem in problems:
            print("FAIL  " + problem)
        print("coverage: %d structural problem(s)" % len(problems))
        return 2
    if args.validate:
        print("coverage: manifest and %d scenario file(s) are valid"
              % len({scenario for scenario, _case in cases.values()}))
        return 0

    import dap_client as dap

    tmpdir = pathlib.Path(tempfile.mkdtemp(prefix="dap-coverage-"))
    proc = log = None
    base = args.base
    try:
        if base is None:
            proc, base, log = start_engine(tmpdir)
        api = dap.Api(base, session="dap-coverage", timeout=180)
        api.health()
        statuses, details = run_tasks(api, goldens, tasks, cases, datasets, tmpdir,
                                      verbose=args.verbose, only=args.only)
        return summarize(statuses, details, len(tasks))
    finally:
        if proc is not None and not args.keep:
            stop_engine(proc, base, log)
        if not args.keep:
            shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())