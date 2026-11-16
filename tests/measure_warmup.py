#!/usr/bin/env python3
"""Measure the startup warm-up of dap-native (Python 3 stdlib only).

For DAP_WARMUP=0 and DAP_WARMUP=1 the script spawns the engine on a free port
and measures, per run:

  health      monotonic clock from Popen to the first 200 from GET /v1/health;
  first read  clock from that health answer to the first completed
              csv_reader workflow plus GET .../table (iris.csv), i.e. the first
              real user call including its CSV parse and DuckDB ingest;
  warm-up     the "warm-up completed in N ms" line the engine writes to stderr.

Runs are repeated (3 by default) and the medians are printed. Every spawned JVM
is terminated before the script exits.

    python3 tests/measure_warmup.py
    python3 tests/measure_warmup.py --runs 5 --json build/warmup.json
    python3 tests/measure_warmup.py --jar engine-native/target/dap-native.jar

The warm-up runs on a daemon thread after /v1/health starts answering, so a
first call that arrives during the warm-up is measured as it is in production:
concurrent with the warm-up, not after it.
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import pathlib
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_JAR = ROOT / "engine-native/target/dap-native.jar"
DEFAULT_CSV = ROOT / "tests/goldens-native/data/iris.csv"
SESSION = "warmup-measure"
WARMUP_LINE = re.compile(r"warm-up completed in (\d+) ms")


class MeasureError(RuntimeError):
    """The engine, the harness, or the environment failed a measurement."""


# --------------------------------------------------------------------- HTTP


def request(port, method, path, body=None, timeout=120.0):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"X-DAP-Session": SESSION}
    if data is not None:
        headers["Content-Type"] = "application/json"
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
    try:
        connection.request(method, path, body=data, headers=headers)
        response = connection.getresponse()
        raw = response.read()
        payload = json.loads(raw.decode("utf-8")) if raw else None
        return response.status, payload
    except (OSError, http.client.HTTPException, ValueError) as err:
        raise MeasureError("%s %s failed: %s" % (method, path, err)) from err
    finally:
        connection.close()


def wait_health(port, timeout_s, proc):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        try:
            status, body = request(port, "GET", "/v1/health", timeout=2.0)
            if status == 200 and body and body.get("ok"):
                return True
        except MeasureError:
            pass
        if proc.poll() is not None:
            return False
        time.sleep(0.02)
    return False


# ------------------------------------------------------------------ process


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def port_open(port, timeout=0.3):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def wait_port_free(port, timeout_s=15.0):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if not port_open(port):
            return True
        time.sleep(0.05)
    return False


def terminate(proc, grace=20.0):
    if proc is None or proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        proc.kill()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass


def java_works(candidate):
    try:
        result = subprocess.run([candidate, "-version"], capture_output=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        return False
    return result.returncode == 0


def find_java():
    for variable in ("DAP_JDK", "JAVA_HOME"):
        configured = os.environ.get(variable)
        if not configured:
            continue
        candidate = os.path.join(configured, "bin", "java")
        if os.path.isfile(candidate) and java_works(candidate):
            return candidate
        raise MeasureError("%s=%s has no working bin/java" % (variable, configured))
    found = shutil.which("java")
    if found and java_works(found):
        return found
    raise MeasureError("no working JDK on PATH; set DAP_JDK to a JDK 21 home")


# --------------------------------------------------------------- one run


def first_read(port, csv_path):
    """csv_reader -> run -> /table; returns (total_ms, run_ms, table_ms)."""
    started = time.monotonic()
    status, created = request(port, "POST", "/v1/workflows", {"name": "warmup-first-read"})
    if status != 200:
        raise MeasureError("create workflow: HTTP %d %s" % (status, created))
    workflow = created["id"]

    settings = {"file": str(csv_path), "delimiter": ",", "header": True}
    status, node = request(port, "POST", "/v1/workflows/%s/nodes" % workflow,
                           {"factory": "dap.csv_reader", "settings": settings})
    if status != 200:
        raise MeasureError("add csv_reader: HTTP %d %s" % (status, node))
    node_id = node["node"]

    run_started = time.monotonic()
    status, run = request(port, "POST", "/v1/workflows/%s/run" % workflow, {})
    run_ms = (time.monotonic() - run_started) * 1000.0
    if status != 200 or not run.get("ok"):
        raise MeasureError("run: HTTP %d %s" % (status, run))

    table_started = time.monotonic()
    status, table = request(port, "GET",
                            "/v1/workflows/%s/nodes/%s/table?limit=5" % (workflow, node_id))
    table_ms = (time.monotonic() - table_started) * 1000.0
    if status != 200:
        raise MeasureError("table: HTTP %d %s" % (status, table))
    if table.get("rows") != 150:
        raise MeasureError("table: expected 150 iris rows, got %r" % (table.get("rows"),))
    return (time.monotonic() - started) * 1000.0, run_ms, table_ms


def measure_run(java, jar, heap, warmup, csv_path, workdir, index):
    port = free_port()
    log_path = os.path.join(workdir, "warmup-%s-%d.log" % (warmup, index))
    env = os.environ.copy()
    env["DAP_WARMUP"] = warmup
    command = [java, "-Xmx%s" % heap, "-Ddap.port=%d" % port, "-jar", str(jar)]
    started = time.monotonic()
    with open(log_path, "wb") as log:
        proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env)
        try:
            if not wait_health(port, 60.0, proc):
                tail = pathlib.Path(log_path).read_text(encoding="utf-8", errors="replace")
                raise MeasureError("no /v1/health 200 within 60 s (DAP_WARMUP=%s); "
                                   "engine log: %s" % (warmup, tail.strip()[-800:]))
            health_ms = (time.monotonic() - started) * 1000.0
            read_ms, run_ms, table_ms = first_read(port, csv_path)
        finally:
            terminate(proc)
            wait_port_free(port)
    text = pathlib.Path(log_path).read_text(encoding="utf-8", errors="replace")
    match = WARMUP_LINE.search(text)
    return {
        "warmup": warmup,
        "run": index + 1,
        "healthMs": round(health_ms, 1),
        "firstReadMs": round(read_ms, 1),
        "runMs": round(run_ms, 1),
        "tableMs": round(table_ms, 1),
        "warmupMs": int(match.group(1)) if match else None,
    }


# ------------------------------------------------------------------ report


def median(rows, key):
    values = [row[key] for row in rows if row[key] is not None]
    return round(statistics.median(values), 1) if values else None


def format_ms(value):
    return "n/a" if value is None else "%.1f" % value


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--jar", default=str(DEFAULT_JAR), help="dap-native jar")
    parser.add_argument("--csv", default=str(DEFAULT_CSV), help="CSV for the first read")
    parser.add_argument("--runs", type=int, default=3, help="runs per warm-up setting")
    parser.add_argument("--heap", default="2g", help="JVM -Xmx for every run")
    parser.add_argument("--json", default=None, help="write the raw runs and medians here")
    args = parser.parse_args()

    jar = pathlib.Path(args.jar).resolve()
    csv_path = pathlib.Path(args.csv).resolve()
    if not jar.is_file():
        raise MeasureError("jar not found: %s (run make build-native)" % jar)
    if not csv_path.is_file():
        raise MeasureError("CSV not found: %s" % csv_path)
    java = find_java()

    print("warm-up measurement: jar=%s" % jar)
    print("csv=%s runs=%d heap=%s" % (csv_path, args.runs, args.heap), flush=True)

    runs = []
    with tempfile.TemporaryDirectory(prefix="dap-warmup-measure-") as workdir:
        for warmup in ("0", "1"):
            for index in range(args.runs):
                measured = measure_run(java, jar, args.heap, warmup, csv_path,
                                       workdir, index)
                runs.append(measured)
                print("  DAP_WARMUP=%s run %d: health %s ms, first read %s ms "
                      "(run %s, table %s), warm-up %s ms"
                      % (warmup, measured["run"], format_ms(measured["healthMs"]),
                         format_ms(measured["firstReadMs"]), format_ms(measured["runMs"]),
                         format_ms(measured["tableMs"]),
                         "n/a" if measured["warmupMs"] is None else measured["warmupMs"]),
                      flush=True)

    medians = {}
    for warmup in ("0", "1"):
        subset = [row for row in runs if row["warmup"] == warmup]
        medians[warmup] = {key: median(subset, key) for key in
                           ("healthMs", "firstReadMs", "runMs", "tableMs", "warmupMs")}

    print()
    print("medians over %d runs:" % args.runs)
    print("  %-14s %10s %14s %10s %10s %12s"
          % ("DAP_WARMUP", "health ms", "first read ms", "run ms", "table ms", "warm-up ms"))
    for warmup in ("0", "1"):
        row = medians[warmup]
        print("  %-14s %10s %14s %10s %10s %12s"
              % (warmup, format_ms(row["healthMs"]), format_ms(row["firstReadMs"]),
                 format_ms(row["runMs"]), format_ms(row["tableMs"]),
                 format_ms(row["warmupMs"])))

    if args.json:
        output = pathlib.Path(args.json)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps({"runs": runs, "medians": medians}, indent=2) + "\n",
                          encoding="utf-8")
        print("raw runs -> %s" % output)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except MeasureError as error:
        print("measure_warmup: %s" % error, file=sys.stderr)
        sys.exit(1)
