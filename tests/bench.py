#!/usr/bin/env python3
"""Phase 3 benchmark harness for dap-native (Python 3 stdlib only).

Three independent measurements, selectable with --only:

  cold      spawn "java -jar" and stop the clock at the first 200 from
            /v1/health; 3 runs (median, min, max).
  csv       write (or reuse) a deterministic CSV and run
            csv_reader -> row_filter -> group_by -> sorter through /v1; measure
            the sync run, /table, /profile, and the JVM peak RSS.
  branches  two independent csv_reader branches in one DAG vs the same two
            ingests run sequentially; report the measured ratio. When the
            engine serializes ingest the reason is recorded instead of forcing
            a number.

Every spawned JVM is terminated before the harness exits. The 10M-row case is
meant for the integrator's machine; see docs/BENCH.md for the method.

    python3 tests/bench.py --jar engine-native/target/dap-native.jar --rows 100000
    python3 tests/bench.py --jar engine-native/target/dap-native.jar --json build/bench.json
    python3 tests/bench.py --base http://127.0.0.1:7925 --only csv,branches
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import pathlib
import platform
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import urllib.parse
import zipfile

SECTIONS = ("cold", "csv", "branches")
GROUPS = 8
GROUP_NAMES = tuple("g%d" % index for index in range(GROUPS))
DEFAULT_ROWS = 10_000_000
DUCKDB_ENV = ("DAP_DUCKDB_MEMORY_LIMIT", "DAP_DUCKDB_THREADS", "DAP_DUCKDB_TEMP_DIR")
CSV_COLUMNS = (
    {"name": "id", "type": "Int64"},
    {"name": "value", "type": "Float64"},
    {"name": "grp", "type": "Utf8"},
)
class BenchError(RuntimeError):
    """The service, the harness, or the environment failed a measurement."""


class Reply:
    __slots__ = ("status", "body", "ms")

    def __init__(self, status, body, ms):
        self.status = status
        self.body = body
        self.ms = ms


def short(value, limit=500):
    try:
        text = json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        text = repr(value)
    return text if len(text) <= limit else text[:limit] + "..."


# --------------------------------------------------------------------- HTTP


class Client:
    """Minimal keep-alive /v1 client; timings include the HTTP round trip."""

    def __init__(self, base, timeout):
        parts = urllib.parse.urlsplit(base)
        if parts.scheme not in ("http", "") or not parts.hostname:
            raise BenchError("unsupported --base %r (expected http://host:port)" % (base,))
        self.host = parts.hostname
        self.port = parts.port or 80
        self.timeout = timeout
        self.session = "bench"
        self._conn = None

    def close(self):
        if self._conn is not None:
            try:
                self._conn.close()
            except OSError:
                pass
            self._conn = None

    def _connection(self):
        if self._conn is None:
            self._conn = http.client.HTTPConnection(
                self.host, self.port, timeout=self.timeout)
        return self._conn

    def request(self, method, path, body=None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"X-DAP-Session": self.session}
        if data is not None:
            headers["Content-Type"] = "application/json"
        last_error = None
        for attempt in (0, 1):
            conn = self._connection()
            started = time.monotonic()
            try:
                conn.request(method, path, body=data, headers=headers)
                response = conn.getresponse()
                raw = response.read()
            except (http.client.RemoteDisconnected, ConnectionResetError) as err:
                # Jetty may drop an idle keep-alive socket; retry idempotent GETs once.
                last_error = err
                self.close()
                if attempt or method != "GET":
                    break
                continue
            except (OSError, http.client.HTTPException) as err:
                self.close()
                raise BenchError("%s %s failed: %s" % (method, path, err)) from err
            elapsed = (time.monotonic() - started) * 1000.0
            try:
                payload = json.loads(raw.decode("utf-8")) if raw else None
            except ValueError:
                payload = {"raw": raw[:400].decode("utf-8", "replace")}
            return Reply(response.status, payload, elapsed)
        raise BenchError("%s %s failed: %s" % (method, path, last_error))

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, body=None):
        return self.request("POST", path, body)


def health_once(host, port):
    """One short-timeout /v1/health probe; True only for 200 with ok=true."""
    conn = http.client.HTTPConnection(host, port, timeout=1.0)
    try:
        conn.request("GET", "/v1/health")
        response = conn.getresponse()
        raw = response.read()
        if response.status != 200:
            return False
        try:
            return json.loads(raw.decode("utf-8")).get("ok") is True
        except (ValueError, AttributeError):
            return False
    except (OSError, http.client.HTTPException):
        return False
    finally:
        try:
            conn.close()
        except OSError:
            pass


def wait_health(host, port, timeout_s, proc=None):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if health_once(host, port):
            return True
        if proc is not None and proc.poll() is not None:
            return False
        time.sleep(0.02)
    return False


def port_open(host, port, timeout=0.3):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        return sock.connect_ex((host, port)) == 0


def wait_port_free(host, port, timeout_s=15.0):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if not port_open(host, port):
            return True
        time.sleep(0.05)
    return False


# ------------------------------------------------------------------ process


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


def read_rss(pid):
    """Resident set size of one process in bytes (macOS/Linux ps reports KiB)."""
    try:
        result = subprocess.run(
            ["ps", "-o", "rss=", "-p", str(pid)],
            capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    fields = result.stdout.split()
    if not fields:
        return None
    try:
        return int(fields[0]) * 1024
    except ValueError:
        return None


def read_heap(base):
    """JVM heap (usedMb, maxMb) from /v1/health; the roadmap's RAM formula lives here.

    /health reports Runtime.totalMemory()-freeMemory(), which is the metric the roadmap
    specifies for the 10M run. A separate short connection keeps the sampler thread away
    from the client's keep-alive socket.
    """
    try:
        with urllib.request.urlopen(base + "/v1/health", timeout=2) as response:
            memory = (json.loads(response.read().decode("utf-8")) or {}).get("memory") or {}
        used = memory.get("usedMb")
        maximum = memory.get("maxMb")
        return (float(used) if used is not None else None,
                float(maximum) if maximum is not None else None)
    except (OSError, ValueError, AttributeError):
        return None, None


class RssSampler(threading.Thread):
    """Samples ps RSS and /health heap until stopped; keeps both maxima."""

    def __init__(self, pid, base, interval):
        super().__init__(name="dap-bench-rss", daemon=True)
        self.pid = pid
        self.base = base
        self.interval = interval
        self.peak = None
        self.heap_peak_mb = None
        self.heap_max_mb = None
        self._stop = threading.Event()

    def sample(self):
        value = read_rss(self.pid)
        if value is not None and (self.peak is None or value > self.peak):
            self.peak = value
        used, maximum = read_heap(self.base)
        if used is not None and (self.heap_peak_mb is None or used > self.heap_peak_mb):
            self.heap_peak_mb = used
        if maximum is not None:
            self.heap_max_mb = maximum

    def run(self):
        while not self._stop.wait(self.interval):
            self.sample()

    def stop(self):
        self._stop.set()
        self.join(timeout=5)


def resolve_java(cli_value):
    if cli_value:
        return cli_value
    for name in ("DAP_JDK", "JAVA_HOME"):
        home = os.environ.get(name)
        if not home:
            continue
        if os.path.isfile(home):
            return home
        candidate = os.path.join(home, "bin", "java")
        if os.path.exists(candidate):
            return candidate
    return "java"


def java_version(java):
    try:
        result = subprocess.run(
            [java, "-version"], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    lines = (result.stderr or result.stdout or "").strip().splitlines()
    return lines[0] if lines else None


def total_memory():
    try:
        return os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    except (AttributeError, ValueError, OSError):
        return None


def jar_info(path):
    stat = os.stat(path)
    return {
        "path": os.path.abspath(path),
        "bytes": stat.st_size,
        "mib": round(stat.st_size / (1024.0 * 1024.0), 1),
    }


def duckdb_version(jar, override):
    if override:
        return override
    pom = pathlib.Path(jar).resolve().parent.parent / "pom.xml"
    try:
        text = pom.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    match = re.search(r"<duckdb\.version>([^<]+)</duckdb\.version>", text)
    return match.group(1).strip() if match else None


def jar_reads_duckdb_env(jar):
    """True when any engine class carries a DAP_DUCKDB_* literal.

    The names are read in NativeService.Settings, which the compiler emits as
    NativeService$Settings.class, so the outer class alone is not enough.
    """
    try:
        with zipfile.ZipFile(jar) as archive:
            names = [name for name in archive.namelist()
                     if name.startswith("com/dap/nativeengine/") and name.endswith(".class")]
            for name in names:
                if b"DAP_DUCKDB" in archive.read(name):
                    return True
    except (KeyError, OSError, zipfile.BadZipFile):
        return None
    return False


def duckdb_settings():
    return {name: os.environ.get(name) for name in DUCKDB_ENV}


# ---------------------------------------------------------------------- CSV


def write_csv(path, rows):
    """Deterministic CSV: id 0..rows-1, value in [0, 100), cycling group name."""
    started = time.monotonic()
    chunk = 100_000
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write("id,value,grp\n")
        for start in range(0, rows, chunk):
            stop = min(start + chunk, rows)
            lines = []
            for index in range(start, stop):
                lines.append("%d,%.3f,%s" % (
                    index, ((index * 7919) % 100000) / 1000.0, GROUP_NAMES[index % GROUPS]))
            handle.write("\n".join(lines))
            handle.write("\n")
    return (time.monotonic() - started) * 1000.0


# ---------------------------------------------------------------- workflows


def add_node(client, workflow, factory, settings):
    reply = client.post("/v1/workflows/%s/nodes" % workflow,
                        {"factory": factory, "settings": settings})
    if reply.status != 200:
        raise BenchError("add %s -> HTTP %s: %s" % (factory, reply.status, short(reply.body)))
    return reply.body["node"]


def connect(client, workflow, source, target):
    reply = client.post("/v1/workflows/%s/connect" % workflow, {
        "from": source, "to": target, "fromPort": 0, "toPort": 0})
    if reply.status != 200:
        raise BenchError("connect %s -> %s -> HTTP %s: %s"
                         % (source, target, reply.status, short(reply.body)))


def create_workflow(client, name):
    reply = client.post("/v1/workflows", {"name": name})
    if reply.status != 200:
        raise BenchError("create workflow -> HTTP %s: %s" % (reply.status, short(reply.body)))
    return reply.body["id"]


def add_chain(client, workflow, csv_path, threshold):
    """csv_reader -> row_filter -> group_by -> sorter inside an existing workflow."""
    source = add_node(client, workflow, "dap.csv_reader", {
        "file": csv_path,
        "delimiter": ",",
        "header": True,
        "columns": list(CSV_COLUMNS),
        "emptyStringIsNull": False,
    })
    row_filter = add_node(client, workflow, "dap.row_filter", {
        "logic": "and",
        "where": [{"column": "value", "op": "ge", "value": threshold}],
    })
    group = add_node(client, workflow, "dap.group_by", {
        "keys": ["grp"],
        "aggregations": [
            {"column": "value", "fn": "mean"},
            {"column": "value", "fn": "sum"},
        ],
    })
    sorter = add_node(client, workflow, "dap.sorter", {
        "keys": [{"column": "grp", "direction": "asc"}],
        "collation": "binary",
    })
    connect(client, workflow, source, row_filter)
    connect(client, workflow, row_filter, group)
    connect(client, workflow, group, sorter)
    return {"source": source, "filter": row_filter, "group": group, "final": sorter}


def build_chain(client, name, csv_path, threshold):
    workflow = create_workflow(client, name)
    return {"workflow": workflow, **add_chain(client, workflow, csv_path, threshold)}


def run_workflow(client, workflow, timeout_s):
    reply = client.post("/v1/workflows/%s/run" % workflow,
                        {"async": False, "timeoutSec": int(timeout_s)})
    if reply.status != 200 or not (reply.body or {}).get("ok"):
        raise BenchError("run %s -> HTTP %s: %s" % (workflow, reply.status, short(reply.body)))
    return reply.ms, (reply.body or {}).get("durationMs")


def get_json(client, path, what):
    reply = client.get(path)
    if reply.status != 200:
        raise BenchError("%s -> HTTP %s: %s" % (what, reply.status, short(reply.body)))
    return reply.ms, reply.body


def workflow_url(workflow, node, tail):
    return "/v1/workflows/%s/nodes/%s/%s" % (workflow, node, tail)


# ----------------------------------------------------------------- sections


def cold_section(args, java, workdir):
    if port_open("127.0.0.1", args.port):
        raise BenchError("port %d is already in use; cold start needs a free port" % args.port)
    runs = []
    for index in range(args.cold_runs):
        log_path = os.path.join(workdir, "cold-%d.log" % index)
        with open(log_path, "wb") as log:
            command = [java, "-Xmx%s" % args.heap,
                       "-Ddap.port=%d" % args.port, "-jar", args.jar]
            started = time.monotonic()
            proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            try:
                healthy = wait_health("127.0.0.1", args.port, args.start_timeout, proc)
                elapsed = (time.monotonic() - started) * 1000.0
                if not healthy:
                    raise BenchError("cold run %d: no /v1/health 200 within %.0fs"
                                     % (index + 1, args.start_timeout))
            finally:
                terminate(proc)
                wait_port_free("127.0.0.1", args.port)
        runs.append(elapsed)
    return {
        "ran": True,
        "runsMs": [round(value, 1) for value in runs],
        "medianMs": round(statistics.median(runs), 1),
        "minMs": round(min(runs), 1),
        "maxMs": round(max(runs), 1),
    }


def csv_section(client, args, workdir, sampler, errors):
    generated = args.csv_path is None
    path = args.csv_path or os.path.join(workdir, "bench-%d.csv" % args.rows)
    write_ms = None
    if generated:
        print("writing %d rows to %s ..." % (args.rows, path), flush=True)
        write_ms = write_csv(path, args.rows)

    chain = build_chain(client, "bench-csv", path, args.threshold)
    warmup_ms = []
    for _ in range(args.warmup):
        elapsed, _ = run_workflow(client, chain["workflow"], args.timeout)
        warmup_ms.append(round(elapsed, 1))
    run_ms, server_ms = run_workflow(client, chain["workflow"], args.timeout)
    table_ms, table = get_json(
        client, workflow_url(chain["workflow"], chain["final"], "table?port=0&limit=10"),
        "table")
    profile_ms, profile = get_json(
        client, workflow_url(chain["workflow"], chain["final"], "profile"), "profile")
    source_ms, source = get_json(
        client, workflow_url(chain["workflow"], chain["source"], "profile"), "profile(source)")

    observed_rows = source.get("rows")
    if generated and observed_rows != args.rows:
        raise BenchError("csv_reader loaded %r rows, generated %d" % (observed_rows, args.rows))
    if generated and table.get("rows") != GROUPS:
        errors.append("final table has %r rows, expected one per group (%d)"
                      % (table.get("rows"), GROUPS))

    stat = os.stat(path)
    rows = args.rows if generated else observed_rows
    return {
        "ran": True,
        "csv": {
            "path": os.path.abspath(path),
            "generated": generated,
            "rows": rows,
            "observedRows": observed_rows,
            "bytes": stat.st_size,
            "mib": round(stat.st_size / (1024.0 * 1024.0), 1),
            "writeMs": None if write_ms is None else round(write_ms, 1),
            "threshold": args.threshold,
            "groups": GROUPS,
        },
        "workflow": chain,
        "warmupRunsMs": warmup_ms or None,
        "runMs": round(run_ms, 1),
        "runServerDurationMs": server_ms,
        "totalMs": round(run_ms + table_ms, 1),
        "tableMs": round(table_ms, 1),
        "tableRows": table.get("rows"),
        "profileMs": round(profile_ms, 1),
        "profileRows": profile.get("rows"),
        "profileSourceMs": round(source_ms, 1),
        "profileSourceRows": observed_rows,
        "rssPeakBytes": None if sampler is None else read_rss(sampler.pid),
        "heapPeakMb": None if sampler is None else sampler.heap_peak_mb,
        "heapMaxMb": None if sampler is None else sampler.heap_max_mb,
        "note": ("sync-run measures ingestion (csv_reader is the only eager node); "
                 "/table on the final node materializes the SQL pipeline, so "
                 "totalMs = runMs + tableMs approximates end-to-end"),
    }


def branches_section(client, args):
    parallel = create_workflow(client, "bench-branches-parallel")
    first = add_chain(client, parallel, args.csv_path, args.threshold)
    second = add_chain(client, parallel, args.csv_path, args.threshold)
    single = build_chain(client, "bench-branches-single", args.csv_path, args.threshold)

    sequential_runs = []
    parallel_runs = []
    for _ in range(args.branch_reps):
        left, _ = run_workflow(client, single["workflow"], args.timeout)
        right, _ = run_workflow(client, single["workflow"], args.timeout)
        both, _ = run_workflow(client, parallel, args.timeout)
        sequential_runs.append(round(left + right, 1))
        parallel_runs.append(round(both, 1))

    sequential = statistics.median(sequential_runs)
    concurrent = statistics.median(parallel_runs)
    ratio = sequential / concurrent if concurrent else None
    reason = ("DuckDbSession.loadInto serializes CREATE TABLE + DuckDBAppender "
              "on the shared job connection (docs/NATIVE.md 2.4), but CSV parsing "
              "runs before that lock: parsing overlaps, appending does not. The "
              "ratio is therefore size-dependent (small files: parse dominates, "
              "ratio > 1.0x; large files: serialized append dominates, ratio -> "
              "1.0x). The measured ratio is reported as-is, not forced.")
    return {
        "ran": True,
        "workflow": parallel,
        "singleWorkflow": single["workflow"],
        "nodes": {"first": first, "second": second},
        "branchReps": args.branch_reps,
        "sequentialRunsMs": sequential_runs,
        "parallelRunsMs": parallel_runs,
        "sequentialMs": round(sequential, 1),
        "parallelMs": round(concurrent, 1),
        "ratio": None if ratio is None else round(ratio, 2),
        "expectedIfFullySerialized": 1.0,
        "expectedIfFullyParallel": 2.0,
        "ingestSerialized": True,
        "parseOutsideLock": True,
        "reason": reason,
        "note": "sync-run measures ingestion only; downstream SQL is lazy and runs on /table",
    }


# ------------------------------------------------------------------ report


def new_report(args, only, java):
    jar = jar_info(args.jar) if args.jar else {"path": None, "bytes": None, "mib": None}
    environment = {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpuCount": os.cpu_count(),
        "memBytes": total_memory(),
        "python": platform.python_version(),
        "java": {"path": java, "version": java_version(java)},
        "jar": jar,
        "heap": args.heap,
        "duckdb": {
            "version": duckdb_version(args.jar, args.duckdb_version) if args.jar else None,
            "settings": duckdb_settings(),
            "engineReadsDapDuckdbEnv": jar_reads_duckdb_env(args.jar) if args.jar else None,
        },
    }
    return {
        "meta": {
            "tool": "tests/bench.py",
            "schema": 1,
            "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "only": only,
            "base": args.base,
            "serviceMode": "external" if args.base else "spawned",
        },
        "environment": environment,
        "service": {"ran": False, "mode": None, "base": None, "startupMs": None,
                    "rssIdleBytes": None, "heapIdleMb": None},
        "cold": {"ran": False, "runsMs": None, "medianMs": None, "minMs": None, "maxMs": None},
        "csv": {"ran": False},
        "branches": {"ran": False},
        "errors": [],
        "notes": [],
    }


def print_report(report):
    env = report["environment"]
    jar = env["jar"]
    duckdb = env["duckdb"]
    mem = env["memBytes"]
    print("")
    print("DAP native benchmark  (%s)" % ", ".join(report["meta"]["only"]))
    print("  machine   %s, %s, %s cores, %s" % (
        env["platform"], env["machine"], env["cpuCount"],
        "unknown" if mem is None else "%.1f GiB" % (mem / (1024.0 ** 3))))
    print("  java      %s" % (env["java"]["version"] or env["java"]["path"]))
    if jar["path"]:
        print("  jar       %s (%s MiB)" % (jar["path"], jar["mib"]))
    else:
        print("  jar       n/a (no --jar; size not measured)")
    settings = duckdb["settings"]
    print("  duckdb    %s  (DAP_DUCKDB_*: %s; engine reads env: %s)" % (
        duckdb["version"] or "unknown",
        ", ".join("%s=%s" % (key, value or "unset") for key, value in settings.items()),
        duckdb["engineReadsDapDuckdbEnv"]))
    service = report["service"]
    if service.get("ran"):
        if service["mode"] == "spawned":
            print("  service   spawned %s, first health %.0f ms, idle rss %s" % (
                service["base"], service["startupMs"] or 0, mib(service["rssIdleBytes"])))
        else:
            print("  service   external %s" % (service["base"],))

    cold = report["cold"]
    if cold["ran"]:
        count = len(cold["runsMs"])
        print("  cold start (%d run%s)   median %s  min %s  max %s   runs %s" % (
            count, "" if count == 1 else "s", ms(cold["medianMs"]), ms(cold["minMs"]),
            ms(cold["maxMs"]), ", ".join("%.0f" % value for value in cold["runsMs"])))
    else:
        print("  cold start            skipped")

    csv = report["csv"]
    if csv["ran"]:
        source = csv["csv"]
        print("  csv %s rows%s   run %s   total %s   /table %s   /profile %s   "
              "/profile(src) %s   rss peak %s" % (
                  source["rows"], " (generated)" if source["generated"] else "",
                  ms(csv["runMs"]), ms(csv["totalMs"]), ms(csv["tableMs"]),
                  ms(csv["profileMs"]), ms(csv["profileSourceMs"]),
                  mib(csv["rssPeakBytes"])))
    else:
        print("  csv                   skipped")

    branches = report["branches"]
    if branches["ran"]:
        print("  branches (%d sources)  parallel %s   sequential %s   ratio %s   "
              "(append serialized, parse overlaps)" % (
                  len(branches["nodes"]), ms(branches["parallelMs"]),
                  ms(branches["sequentialMs"]),
                  "n/a" if branches["ratio"] is None else "%.2fx" % branches["ratio"]))
    else:
        print("  branches              skipped")

    for warning in report["errors"]:
        print("  warning   %s" % warning)
    print("")


def ms(value):
    if value is None:
        return "-"
    return "%.0f ms" % value if value >= 10 else "%.1f ms" % value


def mib(value):
    if value is None:
        return "-"
    return "%.0f MiB" % (value / (1024.0 * 1024.0))


# -------------------------------------------------------------------- main


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="dap-native Phase 3 benchmark harness (Python 3 stdlib only)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("--jar", help="path to dap-native.jar (required unless --base is used)")
    parser.add_argument("--base", help="target an already running service, e.g. http://127.0.0.1:7925")
    parser.add_argument("--port", type=int, default=7800, help="port for the spawned service")
    parser.add_argument("--heap", default="4g", help="JVM -Xmx value")
    parser.add_argument("--java", help="java binary (default: $DAP_JDK, $JAVA_HOME, then PATH)")
    parser.add_argument("--rows", type=int, default=DEFAULT_ROWS,
                        help="rows in the generated CSV")
    parser.add_argument("--csv", help="reuse an existing CSV instead of generating one")
    parser.add_argument("--threshold", type=float, default=50.0,
                        help="row_filter threshold: keep value >= threshold")
    parser.add_argument("--only", default="cold,csv,branches",
                        help="comma-separated sections: cold,csv,branches")
    parser.add_argument("--json", help="write the JSON report to this path")
    parser.add_argument("--keep-data", action="store_true",
                        help="keep the generated CSV and service logs")
    parser.add_argument("--workdir", help="base directory for the CSV and logs (default: system temp)")
    parser.add_argument("--warmup", type=int, default=1,
                        help="unmeasured runs of the csv workflow before the timed run")
    parser.add_argument("--cold-runs", type=int, default=3)
    parser.add_argument("--branch-reps", type=int, default=1)
    parser.add_argument("--rss-interval", type=float, default=0.2)
    parser.add_argument("--timeout", type=float, default=1800.0, help="HTTP/job timeout in seconds")
    parser.add_argument("--start-timeout", type=float, default=60.0,
                        help="seconds to wait for the first /v1/health 200")
    parser.add_argument("--duckdb-version", help="override the DuckDB version recorded in the report")
    args = parser.parse_args(argv)

    args.only = [token.strip() for token in args.only.split(",") if token.strip()]
    unknown = [token for token in args.only if token not in SECTIONS]
    if unknown:
        parser.error("unknown --only sections: %s" % ", ".join(unknown))
    if not args.jar and not args.base:
        parser.error("--jar is required unless --base targets a running service")
    if args.jar and not os.path.exists(args.jar):
        parser.error("--jar does not exist: %s" % args.jar)
    if "cold" in args.only and not args.jar:
        parser.error("--only cold needs --jar")
    if "cold" in args.only and args.base:
        parser.error("--only cold cannot target --base; it spawns its own JVM")
    if args.csv and not os.path.exists(args.csv):
        parser.error("--csv does not exist: %s" % args.csv)
    if "branches" in args.only and "csv" not in args.only and not args.csv:
        parser.error("--only branches needs --csv when the csv section is not selected")
    if args.rows < 1:
        parser.error("--rows must be positive")
    if args.cold_runs < 1 or args.branch_reps < 1 or args.warmup < 0:
        parser.error("--cold-runs/--branch-reps must be positive; --warmup must be >= 0")
    if not args.heap:
        parser.error("--heap must not be empty")
    return args


def service_host_port(base):
    parts = urllib.parse.urlsplit(base)
    return parts.hostname or "127.0.0.1", parts.port or 80


def main(argv=None):
    args = parse_args(argv)
    java = resolve_java(args.java)
    workdir = tempfile.mkdtemp(prefix="dap-bench-", dir=args.workdir)
    report = new_report(args, args.only, java)
    args.csv_path = os.path.abspath(args.csv) if args.csv else None

    proc = None
    sampler = None
    client = None
    failed = False
    try:
        if "cold" in args.only:
            report["cold"] = cold_section(args, java, workdir)

        needs_service = "csv" in args.only or "branches" in args.only
        if needs_service:
            if args.base:
                client = Client(args.base, args.timeout)
                if not health_once(*service_host_port(args.base)):
                    raise BenchError("%s does not answer /v1/health" % args.base)
                report["service"] = {"ran": True, "mode": "external",
                                     "base": args.base, "startupMs": None,
                                     "rssIdleBytes": None}
            else:
                base = "http://127.0.0.1:%d" % args.port
                if port_open("127.0.0.1", args.port):
                    raise BenchError("port %d is already in use" % args.port)
                log_path = os.path.join(workdir, "service.log")
                with open(log_path, "wb") as log:
                    started = time.monotonic()
                    proc = subprocess.Popen(
                        [java, "-Xmx%s" % args.heap, "-Ddap.port=%d" % args.port,
                         "-jar", args.jar], stdout=log, stderr=subprocess.STDOUT)
                if not wait_health("127.0.0.1", args.port, args.start_timeout, proc):
                    raise BenchError("service did not answer /v1/health within %.0fs "
                                     "(log: %s)" % (args.start_timeout, log_path))
                startup_ms = (time.monotonic() - started) * 1000.0
                client = Client(base, args.timeout)
                sampler = RssSampler(proc.pid, base, args.rss_interval)
                sampler.start()
                sampler.sample()
                idle_rss = sampler.peak
                report["service"] = {"ran": True, "mode": "spawned", "base": base,
                                     "startupMs": round(startup_ms, 1),
                                     "rssIdleBytes": idle_rss,
                                     "heapIdleMb": sampler.heap_peak_mb, "log": log_path}

            if "csv" in args.only:
                result = csv_section(client, args, workdir, sampler, report["errors"])
                if sampler is not None:
                    result["rssPeakBytes"] = sampler.peak
                    result["heapPeakMb"] = sampler.heap_peak_mb
                    result["heapMaxMb"] = sampler.heap_max_mb
                    if sampler.peak is None and sampler.heap_peak_mb is None:
                        report["errors"].append(
                            "no memory sample: ps and /v1/health both failed for pid %d" % proc.pid)
                    elif sampler.peak is None:
                        report["notes"].append(
                            "RSS unavailable (ps blocked); JVM heap peak from /v1/health is reported "
                            "as heapPeakMb, which is the roadmap RAM formula")
                report["csv"] = result
                args.csv_path = result["csv"]["path"]
            if "branches" in args.only:
                if not args.csv_path:
                    raise BenchError("branches needs a CSV: pass --csv or run the csv section")
                report["branches"] = branches_section(client, args)
                if report["branches"]["parallelMs"] < 100:
                    report["errors"].append(
                        "branch ratio is noisy below 100 ms; use --branch-reps 3 "
                        "or a larger --rows")
    except BenchError as error:
        failed = True
        report["errors"].append(str(error))
        print("FAIL: %s" % error, file=sys.stderr)
    except KeyboardInterrupt:
        failed = True
        print("interrupted", file=sys.stderr)
    finally:
        if sampler is not None:
            sampler.stop()
        if client is not None:
            client.close()
        if proc is not None:
            terminate(proc)
            wait_port_free("127.0.0.1", args.port)
        if args.keep_data or failed:
            report["dataDir"] = workdir
        else:
            shutil.rmtree(workdir, ignore_errors=True)

    print_report(report)
    if args.json:
        target = os.path.abspath(args.json)
        parent = os.path.dirname(target)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(target, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2, sort_keys=True)
            handle.write("\n")
        print("json report: %s" % target)
    return 2 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
