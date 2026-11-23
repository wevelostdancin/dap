#!/usr/bin/env python3
"""P6 benchmark harness for dap-native (Python 3 stdlib only).

Measures the analyst path end to end on a manifest of datasets: ingest to the
Parquet cache, workflow build + run, /table paging, /profile, /explore, a SQL
aggregate, optional correlation/linear regression, engine RSS, and (optionally)
the MCP adapter's one-call tools against the equivalent HTTP multi-step.

    python3 tests/p6_bench.py --manifest tests/p6_bench_manifest.example.json \
        --data-dir build/p6-bench/data --out build/p6-bench/results --start

Outputs <out>/<dataset>.json, <out>/summary.json and <out>/summary.md. With
<data-dir>/reference.json the profile/explore/table numbers are compared to a
reference and absolute/relative diffs are recorded.

This module imports only the standard library. The pure helpers below are
covered by tests/test_p6_bench.py; the engine and MCP paths are covered by the
smoke run described in docs/BENCH-P6.md.
"""
from __future__ import annotations

import argparse
import http.client
import json
import os
import pathlib
import platform
import queue
import re
import statistics
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parent.parent

DEFAULT_JAR = "engine-native/target/dap-native.jar"
DEFAULT_DATA_DIR = "build/p6-bench/data"
DEFAULT_OUT = "build/p6-bench/results"
DEFAULT_HEAP = "4g"
DEFAULT_PORT = 7811
DEFAULT_TIMEOUT = 900.0
DEFAULT_DUCKDB_MEMORY = "1GB"
DEFAULT_BASE = "http://127.0.0.1:7811"
MCP_SCRIPT = ROOT / "adapters" / "mcp" / "dap_mcp.py"

FORMATS = ("csv", "excel", "json", "parquet")
EXTENSION_FORMATS = {
    ".csv": "csv",
    ".xlsx": "excel",
    ".xls": "excel",
    ".json": "json",
    ".ndjson": "json",
    ".parquet": "parquet",
}
NAME_RE = re.compile(r"[A-Za-z0-9._-]{1,64}")


class BenchError(RuntimeError):
    """The harness, the engine, or the environment failed a measurement."""


class ManifestError(BenchError):
    """The manifest is missing, unreadable, or violates the contract."""


# ------------------------------------------------------------------ helpers


def parse_datasets(value):
    """--datasets 'a, b' -> ('a', 'b'); None/blank -> None (all datasets)."""
    if value is None:
        return None
    text = value.strip()
    if not text:
        return None
    items = [item.strip() for item in text.split(",")]
    if any(not item for item in items):
        raise ValueError("--datasets must be a comma list without empty items")
    return tuple(items)


def approx_tokens(byte_count):
    """Rough agent-context cost: one token per four response bytes."""
    return max(0, int(byte_count)) // 4


def json_size(payload):
    """Response bytes as the engine would write them (compact UTF-8 JSON)."""
    text = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return len(text.encode("utf-8"))


def timing(samples_ms):
    """min/median/max/mean of wall-clock samples; empty -> Nones."""
    values = [float(value) for value in samples_ms]
    if not values:
        return {"count": 0, "min_ms": None, "median_ms": None,
                "max_ms": None, "mean_ms": None}
    return {
        "count": len(values),
        "min_ms": min(values),
        "median_ms": statistics.median(values),
        "max_ms": max(values),
        "mean_ms": statistics.fmean(values),
    }


def diff_value(reference, observed):
    """Absolute and relative difference of one measured value."""
    result = {"reference": reference, "observed": observed, "abs": None, "rel": None}
    if reference is None or observed is None:
        return result
    absolute = abs(float(observed) - float(reference))
    result["abs"] = absolute
    if float(reference) != 0.0:
        result["rel"] = absolute / abs(float(reference))
    return result


def reference_diff(reference, observed):
    """Compare one dataset against reference.json; None without a reference."""
    if reference is None:
        return None
    observed = observed or {}
    diff = {
        "rows": diff_value(reference.get("rows"), observed.get("rows")),
        "numeric_means": {},
        "group_counts": {},
    }
    for section in ("numeric_means", "group_counts"):
        reference_values = reference.get(section) or {}
        observed_values = observed.get(section) or {}
        for key in reference_values:
            diff[section][key] = diff_value(reference_values[key], observed_values.get(key))
    return diff


def rss_bytes(ps_text):
    """ps -o rss= prints KiB; return bytes, or None for unparseable output."""
    text = (ps_text or "").strip()
    if not text:
        return None
    try:
        return int(text.split()[0]) * 1024
    except (IndexError, ValueError):
        return None


def format_bytes(byte_count):
    if byte_count is None:
        return "n/a"
    value = float(byte_count)
    if value < 1024:
        return "%d B" % int(value)
    for unit in ("KiB", "MiB", "GiB", "TiB"):
        value /= 1024.0
        if value < 1024.0:
            return "%.1f %s" % (value, unit)
    return "%.1f PiB" % value


def engine_argv(java, heap, jar):
    return [str(java), "-Xmx%s" % heap, "-jar", str(jar)]


def build_engine_env(base_env, repo, data_dir, out, port, duckdb_memory):
    """Environment contract for a spawned engine (see docs/API.md and the plan)."""
    env = dict(base_env)
    env.update({
        "DAP_HOME": str(repo),
        "DAP_PORT": str(port),
        "DAP_BIND": "127.0.0.1",
        "DAP_DUCKDB_MEMORY_LIMIT": str(duckdb_memory),
        "DAP_DUCKDB_TEMP_DIR": str(pathlib.Path(out) / "duckdb-tmp"),
        "DAP_CACHE_DIR": str(pathlib.Path(out) / "cache"),
        "DAP_ALLOWED_ROOTS": ":".join(str(part) for part in
                                      (data_dir, out, repo, "/tmp")),
    })
    return env


def parse_base(url):
    """http://host:port -> (host, port); raises ValueError without a scheme."""
    parts = urllib.parse.urlsplit(url)
    if not parts.scheme or not parts.hostname:
        raise ValueError("--base must be an absolute URL, got %r" % (url,))
    port = parts.port or (443 if parts.scheme == "https" else 80)
    return parts.hostname, port


def derive_format(path):
    return EXTENSION_FORMATS.get(pathlib.Path(path).suffix.lower())


def load_manifest(path, root=ROOT):
    """Read and validate the manifest; relative paths resolve against root."""
    path = pathlib.Path(path)
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ManifestError("cannot read manifest %s: %s" % (path, exc))
    except json.JSONDecodeError as exc:
        raise ManifestError("manifest %s is not valid JSON: %s" % (path, exc))
    if not isinstance(raw, list):
        raise ManifestError("manifest must be a JSON array of dataset objects")
    entries = []
    seen = set()
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            raise ManifestError("manifest entry %d is not an object" % index)
        name = item.get("name")
        if not isinstance(name, str) or not NAME_RE.fullmatch(name):
            raise ManifestError(
                "manifest entry %d: 'name' must match [A-Za-z0-9._-]{1,64}" % index)
        if name in seen:
            raise ManifestError("duplicate dataset name %r in manifest" % name)
        seen.add(name)
        raw_path = item.get("path")
        if not isinstance(raw_path, str) or not raw_path.strip():
            raise ManifestError("dataset %r: 'path' must be a non-empty string" % name)
        resolved = pathlib.Path(raw_path).expanduser()
        if not resolved.is_absolute():
            resolved = pathlib.Path(root) / resolved
        fmt = item.get("format")
        if fmt is None:
            fmt = derive_format(resolved)
            if fmt is None:
                raise ManifestError(
                    "dataset %r: cannot derive format from %s; set 'format'" % (name, resolved))
        else:
            if not isinstance(fmt, str):
                raise ManifestError("dataset %r: 'format' must be a string" % name)
            fmt = fmt.strip().lower()
            if fmt == "xlsx":
                fmt = "excel"
            if fmt not in FORMATS:
                raise ManifestError(
                    "dataset %r: unknown format %r; use one of %s"
                    % (name, item.get("format"), ", ".join(FORMATS)))
        numeric = item.get("numeric_columns") or []
        if not isinstance(numeric, list) or any(not isinstance(column, str) for column in numeric):
            raise ManifestError("dataset %r: 'numeric_columns' must be an array of strings" % name)
        optional = {}
        for key in ("sql", "group_column", "value_column"):
            value = item.get(key)
            if value is not None and not isinstance(value, str):
                raise ManifestError("dataset %r: '%s' must be a string" % (name, key))
            optional[key] = value
        entries.append({
            "name": name,
            "path": str(resolved),
            "format": fmt,
            "sql": optional["sql"],
            "numeric_columns": list(numeric),
            "group_column": optional["group_column"],
            "value_column": optional["value_column"],
        })
    return entries


def select_entries(entries, names):
    """Filter manifest entries by --datasets, keeping the manifest order."""
    if not names:
        return list(entries)
    by_name = {entry["name"]: entry for entry in entries}
    missing = [name for name in names if name not in by_name]
    if missing:
        raise ManifestError(
            "unknown dataset(s): %s; manifest has: %s"
            % (", ".join(missing), ", ".join(entry["name"] for entry in entries)))
    return [by_name[name] for name in names]


def _number(value):
    if value is None:
        return "n/a"
    if isinstance(value, bool):
        return str(value)
    if isinstance(value, int):
        return str(value)
    value = float(value)
    if value.is_integer() and abs(value) < 1e15:
        return "%.1f" % value
    if value != 0.0 and abs(value) < 1e-4:
        return "%.3g" % value
    text = ("%.6f" % value).rstrip("0").rstrip(".")
    return text or "0"


# --------------------------------------------------------------------- gate

DEFAULT_GATE = {
    "maxFailedSteps": 0,
    "maxFailedMcpCalls": 0,
    "maxMcpCalls": 12,
    "maxMcpTokens": 4000,
    "maxMeanAbsDiff": 1e-3,
    "requireAccuracy": True,
}


def load_gate(path):
    """Read a CI gate JSON; missing thresholds use DEFAULT_GATE.

    Broken JSON, a missing file, a non-object, or a wrongly typed/negative
    threshold raises BenchError (CLI exit 2).
    """
    path = pathlib.Path(path)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise BenchError("cannot read gate %s: %s" % (path, exc))
    except ValueError as exc:
        raise BenchError("gate %s is not valid JSON: %s" % (path, exc))
    if not isinstance(data, dict):
        raise BenchError("gate %s must be a JSON object" % path)
    gate = dict(DEFAULT_GATE)
    for key in gate:
        if key not in data:
            continue
        value = data[key]
        if key == "requireAccuracy":
            if not isinstance(value, bool):
                raise BenchError("gate %s: %s must be a boolean" % (path, key))
        elif isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
            raise BenchError("gate %s: %s must be a non-negative number" % (path, key))
        gate[key] = value
    return gate


def check_gate(summary, gate):
    """Return human-readable threshold violations (empty list means pass)."""
    limits = dict(DEFAULT_GATE)
    limits.update(gate or {})
    violations = []
    totals = summary.get("totals") or {}
    failed = totals.get("failed") or 0
    if failed > limits["maxFailedSteps"]:
        violations.append("failed steps %s > %s" % (failed, limits["maxFailedSteps"]))
    mcp = summary.get("mcp") or {}
    failed_calls = mcp.get("failedCalls") or 0
    if failed_calls > limits["maxFailedMcpCalls"]:
        violations.append("failed MCP calls %s > %s" % (
            failed_calls, limits["maxFailedMcpCalls"]))
    calls = mcp.get("callCount") or 0
    if calls > limits["maxMcpCalls"]:
        violations.append("MCP calls %s > %s" % (calls, limits["maxMcpCalls"]))
    tokens = mcp.get("totalTokens") or 0
    if tokens > limits["maxMcpTokens"]:
        violations.append("MCP tokens %s > %s" % (tokens, limits["maxMcpTokens"]))
    accuracy = summary.get("accuracy") or {}
    if limits["requireAccuracy"] and not accuracy.get("available"):
        violations.append("accuracy unavailable but required")
    for dataset in summary.get("datasets") or []:
        diff = dataset.get("accuracy")
        if not diff:
            if limits["requireAccuracy"]:
                violations.append("%s: no accuracy comparison" % dataset.get("name", "?"))
            continue
        checks = []
        if diff.get("rows"):
            checks.append(("rows", diff["rows"], True))
        checks.extend(("mean:%s" % key, value, False)
                      for key, value in (diff.get("numeric_means") or {}).items())
        checks.extend(("group:%s" % key, value, True)
                      for key, value in (diff.get("group_counts") or {}).items())
        for metric, entry, exact in checks:
            name = "%s: %s" % (dataset.get("name", "?"), metric)
            absolute = entry.get("abs")
            if entry.get("observed") is None or absolute is None:
                violations.append("%s has no observed value" % name)
            elif exact and absolute != 0:
                violations.append("%s abs diff %s != 0" % (name, _number(absolute)))
            elif not exact and absolute > limits["maxMeanAbsDiff"]:
                violations.append("%s abs diff %s > %s" % (
                    name, _number(absolute), limits["maxMeanAbsDiff"]))
    return violations


def _ok_text(result):
    return "yes" if result.get("ok") else "no: %s" % (result.get("error") or "error")


def render_summary_md(summary):
    """Pure markdown rendering of summary.json for docs/BENCH-P6.md."""
    environment = summary.get("environment") or {}
    engine = summary.get("engine") or {}
    lines = [
        "# P6 benchmark — summary",
        "",
        "Generated: %s" % summary.get("generatedAt", "n/a"),
        "Engine: DAP %s at %s (start %s ms)" % (
            engine.get("version") or "?", engine.get("base", "n/a"),
            _number(engine.get("startMs"))),
        "",
        "## Environment",
        "",
        "| What | Value |",
        "|---|---|",
    ]
    for key in ("platform", "python", "java", "heap", "duckdbMemory", "jar"):
        lines.append("| %s | %s |" % (key, environment.get(key, "n/a")))
    lines += [
        "",
        "## Per-dataset timings",
        "",
        "| dataset | step | wall ms | bytes | ok |",
        "|---|---|---|---|---|",
    ]
    for dataset in summary.get("datasets") or []:
        for step, result in (dataset.get("steps") or {}).items():
            lines.append("| %s | %s | %s | %s | %s |" % (
                dataset.get("name", "?"), step, _number(result.get("ms")),
                result.get("bytes", "n/a"), _ok_text(result)))
    mcp = summary.get("mcp") or {}
    if mcp.get("enabled"):
        lines += [
            "",
            "## MCP overhead",
            "",
            "| tool | wall ms | bytes | tokens | ok |",
            "|---|---|---|---|---|",
        ]
        for call in mcp.get("calls") or []:
            lines.append("| %s | %s | %s | %s | %s |" % (
                call.get("tool", "?"), _number(call.get("ms")),
                call.get("bytes", "n/a"), call.get("tokens", "n/a"),
                _ok_text(call)))
        if mcp.get("comparison"):
            lines += [
                "",
                "| tool | MCP ms | HTTP ms | HTTP steps | speedup |",
                "|---|---|---|---|---|",
            ]
            for row in mcp["comparison"]:
                lines.append("| %s | %s | %s | %s | %s |" % (
                    row.get("tool", "?"), _number(row.get("mcpMs")),
                    _number(row.get("httpMs")), row.get("httpSteps", "n/a"),
                    _number(row.get("speedup"))))
    accuracy = summary.get("accuracy") or {}
    lines += ["", "## Accuracy vs reference", ""]
    if accuracy.get("available"):
        lines += [
            "| dataset | metric | reference | observed | abs | rel |",
            "|---|---|---|---|---|---|",
        ]
        for dataset in summary.get("datasets") or []:
            diff = dataset.get("accuracy")
            if not diff:
                continue
            rows = []
            if diff.get("rows"):
                rows.append(("rows", diff["rows"]))
            for column, entry in (diff.get("numeric_means") or {}).items():
                rows.append(("mean:%s" % column, entry))
            for value, entry in (diff.get("group_counts") or {}).items():
                rows.append(("group:%s" % value, entry))
            for metric, entry in rows:
                lines.append("| %s | %s | %s | %s | %s | %s |" % (
                    dataset.get("name", "?"), metric,
                    _number(entry.get("reference")), _number(entry.get("observed")),
                    _number(entry.get("abs")), _number(entry.get("rel"))))
    else:
        lines.append(accuracy.get("note") or "no reference data")
    totals = summary.get("totals") or {}
    lines += [
        "",
        "## Totals",
        "",
        "- datasets: %s" % totals.get("datasets", "n/a"),
        "- steps: %s (%s failed)" % (totals.get("steps", "n/a"), totals.get("failed", 0)),
        "",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------- HTTP


class Reply:
    __slots__ = ("status", "body", "ms")

    def __init__(self, status, body, ms):
        self.status = status
        self.body = body
        self.ms = ms

    @property
    def text(self):
        return self.body.decode("utf-8", errors="replace")


class Client:
    """Keep-alive /v1 client in a dedicated session; timings include the round trip."""

    def __init__(self, base, timeout, session="p6-bench"):
        self.host, self.port = parse_base(base)
        self.timeout = timeout
        self.session = session
        self.connection = None

    def close(self):
        if self.connection is not None:
            try:
                self.connection.close()
            except OSError:
                pass
            self.connection = None

    def _connection(self):
        if self.connection is None:
            self.connection = http.client.HTTPConnection(
                self.host, self.port, timeout=self.timeout)
        return self.connection

    def request(self, method, path, payload=None):
        body = None
        headers = {"X-DAP-Session": self.session}
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        started = time.perf_counter()
        try:
            connection = self._connection()
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            data = response.read()
        except (http.client.HTTPException, OSError):
            self.close()
            raise
        elapsed = (time.perf_counter() - started) * 1000.0
        return Reply(response.status, data, elapsed)

    def get_json(self, path, what=None):
        return _parse_reply(self.request("GET", path), what or path)

    def post_json(self, path, payload=None, what=None):
        return _parse_reply(self.request("POST", path, payload), what or path)


def _parse_reply(reply, what):
    if reply.status >= 400:
        raise BenchError("%s: HTTP %d %s" % (what, reply.status, reply.text[:400]))
    if not reply.body:
        return {}, len(reply.body)
    try:
        return json.loads(reply.text), len(reply.body)
    except ValueError as exc:
        raise BenchError("%s: invalid JSON (%s): %s" % (what, exc, reply.text[:200]))


def measured(steps, name, func):
    """Run one measured step: record wall ms, response bytes, and any error."""
    started = time.perf_counter()
    result = None
    size = 0
    error = None
    try:
        result, size = func()
    except Exception as exc:  # noqa: BLE001 — a failed step must not end the run
        error = "%s: %s" % (type(exc).__name__, exc)
    elapsed = (time.perf_counter() - started) * 1000.0
    steps[name] = {"ms": round(elapsed, 3), "bytes": int(size or 0),
                   "ok": error is None, "error": error}
    return result if error is None else None


# ---------------------------------------------------------------- workflows


def create_workflow(client, name):
    data, size = client.post_json("/v1/workflows", {"name": name}, "workflow create")
    workflow = data.get("id")
    if not workflow:
        raise BenchError("workflow create returned no id: %r" % (data,))
    return workflow, size


def add_node(client, workflow, factory, settings):
    data, size = client.post_json(
        "/v1/workflows/%s/nodes" % workflow,
        {"factory": factory, "settings": settings}, "add node %s" % factory)
    node = data.get("node")
    if not node:
        raise BenchError("add node returned no id: %r" % (data,))
    return node, size


def connect(client, workflow, source, target):
    _, size = client.post_json(
        "/v1/workflows/%s/connect" % workflow,
        {"from": source, "to": target}, "connect")
    return None, size


def run_workflow(client, workflow, timeout):
    data, size = client.post_json(
        "/v1/workflows/%s/run" % workflow,
        {"timeoutSec": int(timeout)}, "run %s" % workflow)
    if not data.get("ok"):
        raise BenchError("run %s failed: %r" % (workflow, data))
    return data, size


def delete_workflow(client, workflow):
    if not workflow:
        return
    try:
        client.request("DELETE", "/v1/workflows/%s" % workflow)
    except (OSError, http.client.HTTPException):
        pass


def node_path(workflow, node, tail):
    return "/v1/workflows/%s/nodes/%s/%s" % (workflow, node, tail)


def build_reader_workflow(client, name, parquet):
    workflow, total = create_workflow(client, name)
    node, size = add_node(client, workflow, "dap.parquet_reader", {"file": parquet})
    return (workflow, node), total + size


def build_query_workflow(client, name, parquet, factory, settings):
    workflow, total = create_workflow(client, name)
    reader, size = add_node(client, workflow, "dap.parquet_reader", {"file": parquet})
    node, size2 = add_node(client, workflow, factory, settings)
    _, size3 = connect(client, workflow, reader, node)
    return (workflow, node), total + size + size2 + size3


def run_query_workflow(client, steps, prefix, parquet, factory, settings, timeout, limit=1000):
    """Build parquet_reader -> factory, run, read the table, delete the workflow."""
    built = measured(steps, prefix + "_build", lambda: build_query_workflow(
        client, "p6-" + prefix, parquet, factory, settings))
    if built is None:
        return None
    workflow, node = built
    try:
        run = measured(steps, prefix + "_run", lambda: run_workflow(client, workflow, timeout))
        if run is None:
            return None
        return measured(steps, prefix + "_table", lambda: client.get_json(
            node_path(workflow, node, "table?limit=%d" % limit), "table"))
    finally:
        delete_workflow(client, workflow)


# ------------------------------------------------------------------- engine


def resolve_java(cli_value=None):
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
    default = pathlib.Path.home() / "Projects/tools/jdk-21.0.13+9/Contents/Home/bin/java"
    if default.is_file():
        return str(default)
    return "java"


def java_version(java):
    try:
        result = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError):
        return None
    lines = (result.stderr or result.stdout or "").strip().splitlines()
    return lines[0] if lines else None


def file_size(path):
    if path is None:
        return None
    try:
        return pathlib.Path(path).stat().st_size
    except (OSError, TypeError):
        return None


def resolve_path(value):
    path = pathlib.Path(value).expanduser()
    return path if path.is_absolute() else ROOT / path


def health_once(host, port):
    """One short /v1/health probe; True only for 200 with ok=true."""
    connection = http.client.HTTPConnection(host, port, timeout=1.0)
    try:
        connection.request("GET", "/v1/health")
        response = connection.getresponse()
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
            connection.close()
        except OSError:
            pass


class Engine:
    """Owns the spawned engine (or targets an external --base with --no-start)."""

    START_TIMEOUT = 180.0

    def __init__(self, args):
        self.args = args
        self.start = args.start
        self.base = ("http://127.0.0.1:%d" % args.port) if args.start else args.base
        self.host, self.port = parse_base(self.base)
        self.process = None
        self.log_handle = None
        self.start_ms = None
        self.rss_error = None

    def start_engine(self):
        if not self.start:
            client = Client(self.base, min(self.args.timeout, 30.0))
            try:
                health, _ = client.get_json("/v1/health", "health")
            finally:
                client.close()
            self._check_health(health)
            return
        jar = pathlib.Path(self.args.jar)
        if not jar.is_file():
            raise BenchError("jar not found: %s (run make build-native)" % jar)
        java = resolve_java(self.args.java)
        out = pathlib.Path(self.args.out)
        out.mkdir(parents=True, exist_ok=True)
        log_path = out / "engine.log"
        self.log_handle = open(log_path, "wb")
        env = build_engine_env(os.environ, ROOT, pathlib.Path(self.args.data_dir),
                               out, self.port, self.args.duckdb_memory)
        started = time.perf_counter()
        self.process = subprocess.Popen(
            engine_argv(java, self.args.heap, jar), cwd=str(ROOT), env=env,
            stdout=self.log_handle, stderr=self.log_handle)
        deadline = time.monotonic() + self.START_TIMEOUT
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise BenchError("engine exited during startup (see %s)" % log_path)
            if health_once(self.host, self.port):
                self.start_ms = (time.perf_counter() - started) * 1000.0
                return
            time.sleep(0.05)
        raise BenchError("engine did not become healthy in %.0f s (see %s)"
                         % (self.START_TIMEOUT, log_path))

    def stop(self):
        if self.process is None:
            if self.log_handle is not None:
                self.log_handle.close()
                self.log_handle = None
            return
        try:
            client = Client(self.base, 10.0)
            try:
                client.post_json("/v1/shutdown", {}, "shutdown")
            except Exception:  # noqa: BLE001 — best-effort stop
                pass
            finally:
                client.close()
        finally:
            try:
                self.process.wait(timeout=30.0)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                try:
                    self.process.wait(timeout=10.0)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=10.0)
            if self.log_handle is not None:
                self.log_handle.close()
                self.log_handle = None

    def _check_health(self, health):
        if not isinstance(health, dict) or health.get("ok") is not True:
            raise BenchError("engine health is not ok: %r" % (health,))

    def health(self, client):
        data, size = client.get_json("/v1/health", "health")
        self._check_health(data)
        return data, size

    def rss(self):
        self.rss_error = None
        if self.process is None or self.process.poll() is not None:
            self.rss_error = "engine process is not running (--no-start)"
            return None
        try:
            result = subprocess.run(
                ["ps", "-o", "rss=", "-p", str(self.process.pid)],
                capture_output=True, text=True, timeout=10)
        except (OSError, subprocess.SubprocessError) as exc:
            self.rss_error = "ps unavailable: %s" % exc
            return None
        value = rss_bytes(result.stdout)
        if value is None:
            self.rss_error = "ps returned no rss for pid %d" % self.process.pid
        return value


def memory_snapshot(engine, client):
    try:
        health, _ = engine.health(client)
        memory = health.get("memory")
    except BenchError:
        memory = None
    return {"rssBytes": engine.rss(), "rssNote": getattr(engine, "rss_error", None),
            "healthMemory": memory}


# ---------------------------------------------------------------------- MCP


class McpClient:
    """Minimal JSON-RPC client for the stdio MCP adapter, with a read timeout."""

    def __init__(self, script, env, timeout, log_path):
        self.timeout = timeout
        self.log_path = str(log_path)
        self.log_handle = open(log_path, "wb")
        self.process = subprocess.Popen(
            [sys.executable, str(script)], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=self.log_handle, cwd=str(ROOT),
            env=env, text=True, encoding="utf-8", bufsize=1)
        self.next_id = 0
        self.lines = queue.Queue()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.lines.put(line)
        finally:
            self.lines.put(None)

    def _send(self, payload):
        self.process.stdin.write(json.dumps(payload, ensure_ascii=False) + "\n")
        self.process.stdin.flush()

    def _read_message(self):
        deadline = time.monotonic() + self.timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise BenchError("MCP adapter timed out after %.0f s" % self.timeout)
            try:
                line = self.lines.get(timeout=remaining)
            except queue.Empty:
                raise BenchError("MCP adapter timed out after %.0f s" % self.timeout)
            if line is None:
                raise BenchError("MCP adapter exited (see %s)" % self.log_path)
            line = line.strip()
            if not line:
                continue
            try:
                return json.loads(line)
            except ValueError:
                continue

    def call(self, method, params=None):
        self.next_id += 1
        request_id = self.next_id
        self._send({"jsonrpc": "2.0", "id": request_id, "method": method,
                    "params": params or {}})
        while True:
            message = self._read_message()
            if message.get("id") == request_id:
                return message

    def notify(self, method, params=None):
        self._send({"jsonrpc": "2.0", "method": method, "params": params or {}})

    def initialize(self):
        message = self.call("initialize", {
            "protocolVersion": "2024-11-05", "capabilities": {},
            "clientInfo": {"name": "p6-bench", "version": "1"}})
        if "error" in message:
            raise BenchError("MCP initialize failed: %r" % (message["error"],))
        self.notify("notifications/initialized")
        return message.get("result") or {}

    def call_tool(self, name, arguments):
        started = time.perf_counter()
        message = self.call("tools/call", {"name": name, "arguments": arguments})
        elapsed = (time.perf_counter() - started) * 1000.0
        if "error" in message:
            error = message["error"]
            detail = error.get("message") if isinstance(error, dict) else error
            raise BenchError("%s: %s" % (name, detail))
        text = ""
        for item in (message.get("result") or {}).get("content") or []:
            if isinstance(item, dict) and item.get("type") == "text":
                text += item.get("text") or ""
        return elapsed, len(text.encode("utf-8")), text

    def close(self):
        try:
            if self.process.stdin is not None:
                self.process.stdin.close()
        except OSError:
            pass
        try:
            self.process.wait(timeout=10.0)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=10.0)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10.0)
        if self.log_handle is not None:
            self.log_handle.close()
            self.log_handle = None


HTTP_EQUIVALENTS = {
    "dap_explore": ("explore",),
    "dap_analyze": ("reader_build", "reader_run", "table_first", "profile"),
    "dap_get_table": ("table_page",),
    "dap_estimate": ("estimate",),
    "dap_report": ("reader_build", "reader_run", "table_first", "profile"),
}


def mcp_json(text, what):
    try:
        return json.loads(text or "{}")
    except ValueError as exc:
        raise BenchError("%s: invalid JSON result: %s" % (what, exc))


def record_mcp_call(result, tool, func):
    started = time.perf_counter()
    size = 0
    text = None
    error = None
    try:
        _, size, text = func()
    except Exception as exc:  # noqa: BLE001 — one failed tool must not end the run
        error = "%s: %s" % (type(exc).__name__, exc)
    elapsed = (time.perf_counter() - started) * 1000.0
    result["calls"].append({
        "tool": tool,
        "ms": round(elapsed, 3),
        "bytes": int(size),
        "tokens": approx_tokens(size),
        "ok": error is None,
        "error": error,
    })
    return text if error is None else None


def measure_mcp(engine, dataset, entry, args):
    result = {"enabled": True, "dataset": entry["name"], "calls": [], "callCount": 0,
              "failedCalls": 0, "totalMs": 0.0, "totalBytes": 0, "totalTokens": 0,
              "comparison": [], "error": None}
    parquet = dataset.get("parquet")
    if not parquet:
        result["error"] = "no ingested Parquet path for %s" % entry["name"]
        return result
    mcp_dir = pathlib.Path(args.out) / "mcp"
    mcp_dir.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env.update({"DAP_HOME": str(ROOT), "DAP_PORT": str(engine.port),
                "DAP_BIND": "127.0.0.1"})
    try:
        mcp = McpClient(MCP_SCRIPT, env, args.timeout, mcp_dir / "adapter.log")
    except OSError as exc:
        result["error"] = "cannot spawn MCP adapter: %s" % exc
        return result
    try:
        try:
            mcp.initialize()
        except Exception as exc:  # noqa: BLE001 — record and stop the MCP section
            result["error"] = "initialize: %s" % exc
            return result
        sql = entry.get("sql")
        record_mcp_call(result, "dap_explore", lambda: mcp.call_tool(
            "dap_explore", {"dataset": parquet, "sampleRows": 1000}))
        analyze_args = {"dataset": parquet, "profile": True, "limit": 20}
        estimate_args = {"dataset": parquet}
        if sql:
            analyze_args["sql"] = sql
            estimate_args["sql"] = sql
        record_mcp_call(result, "dap_analyze", lambda: mcp.call_tool("dap_analyze", analyze_args))
        workflow = None
        node = None
        created = record_mcp_call(result, "dap_workflow_create", lambda: mcp.call_tool(
            "dap_workflow_create", {"name": "p6-mcp"}))
        if created:
            workflow = mcp_json(created, "dap_workflow_create").get("id")
        if workflow:
            added = record_mcp_call(result, "dap_add_node", lambda: mcp.call_tool(
                "dap_add_node", {"workflow": workflow, "factory": "dap.parquet_reader",
                                 "settings": {"file": parquet}}))
            if added:
                node = mcp_json(added, "dap_add_node").get("node")
            record_mcp_call(result, "dap_run", lambda: mcp.call_tool(
                "dap_run", {"workflow": workflow}))
            columns = (dataset.get("columnNames") or [])[:2]
            record_mcp_call(result, "dap_get_table", lambda: mcp.call_tool(
                "dap_get_table", {"workflow": workflow, "node": node, "offset": 1000,
                                  "limit": 20, "columns": columns, "format": "csv"}))
            record_mcp_call(result, "dap_delete_workflow", lambda: mcp.call_tool(
                "dap_delete_workflow", {"workflow": workflow}))
        record_mcp_call(result, "dap_estimate", lambda: mcp.call_tool(
            "dap_estimate", estimate_args))
        record_mcp_call(result, "dap_report", lambda: mcp.call_tool(
            "dap_report", {"dataset": parquet, "format": "html", "limit": 20,
                           "report": str(mcp_dir / ("%s-report.html" % entry["name"]))}))
    finally:
        mcp.close()
    result["callCount"] = len(result["calls"])
    result["failedCalls"] = sum(1 for call in result["calls"] if not call["ok"])
    result["totalMs"] = round(sum(call["ms"] for call in result["calls"]), 3)
    result["totalBytes"] = sum(call["bytes"] for call in result["calls"] if call["ok"])
    result["totalTokens"] = approx_tokens(result["totalBytes"])
    by_tool = {call["tool"]: call for call in result["calls"]}
    for tool, step_names in HTTP_EQUIVALENTS.items():
        call = by_tool.get(tool)
        if not call or not call["ok"]:
            continue
        steps = dataset.get("steps") or {}
        if any(name not in steps or not steps[name]["ok"] for name in step_names):
            continue
        http_ms = round(sum(steps[name]["ms"] for name in step_names), 3)
        speedup = round(http_ms / call["ms"], 3) if call["ms"] > 0 else None
        result["comparison"].append({"tool": tool, "mcpMs": call["ms"], "httpMs": http_ms,
                                     "httpSteps": len(step_names), "speedup": speedup})
    return result


# ------------------------------------------------------------------ dataset


def quote_identifier(name):
    return '"%s"' % str(name).replace('"', '""')


def group_counts_from_table(table):
    if not table:
        return {}
    columns = [column.get("name") for column in table.get("columns") or []]
    if len(columns) < 2:
        return {}
    counts = {}
    for row in table.get("data") or []:
        if len(row) < 2:
            continue
        try:
            counts[str(row[0])] = int(float(row[1]))
        except (TypeError, ValueError):
            continue
    return counts


def regression_settings(entry):
    numeric = entry.get("numeric_columns") or []
    value = entry.get("value_column")
    if not value:
        return None
    predictors = [column for column in numeric if column != value]
    if not predictors:
        return None
    # Large tables: fit on a deterministic first-N sample instead of failing at the
    # in-memory 100k cap (dap.linear_regression sample_rows).
    settings = {"x": predictors, "y": value, "mode": "coefficients",
                "sample_rows": 100000}
    group = entry.get("group_column")
    if group and group not in predictors and group != value:
        settings["group_by"] = [group]
    return settings


def measure_dataset(engine, client, entry, timeout):
    steps = {}
    dataset = {"name": entry["name"], "path": entry["path"],
               "format": entry["format"], "fileBytes": file_size(entry["path"]),
               "steps": steps}
    dataset["memory"] = {"before": memory_snapshot(engine, client)}
    ingest = measured(steps, "ingest", lambda: client.post_json(
        "/v1/datasets", {"file": entry["path"], "format": entry["format"]}, "ingest"))
    if ingest is None:
        dataset["error"] = "ingest failed; remaining steps skipped"
        return dataset
    parquet = ingest.get("path")
    columns = ingest.get("columns") or []
    dataset.update({
        "parquet": parquet,
        "parquetBytes": file_size(parquet),
        "rows": ingest.get("rows"),
        "columns": len(columns),
        "columnNames": [column.get("name") for column in columns
                        if isinstance(column, dict)],
        "cached": ingest.get("cached"),
    })
    workflow = None
    try:
        built = measured(steps, "reader_build", lambda: build_reader_workflow(
            client, "p6-reader", parquet))
        if built is not None:
            workflow, node = built
            run = measured(steps, "reader_run", lambda: run_workflow(
                client, workflow, timeout))
            if run is not None:
                table = measured(steps, "table_first", lambda: client.get_json(
                    node_path(workflow, node, "table?limit=20"), "table"))
                if table:
                    dataset["tableRows"] = table.get("rows")
                measured(steps, "table_page", lambda: client.get_json(
                    node_path(workflow, node, "table?offset=1000&limit=20"), "table page"))
                profile = measured(steps, "profile", lambda: client.get_json(
                    node_path(workflow, node, "profile"), "profile"))
                if profile:
                    dataset["profileRows"] = profile.get("rows")
                    dataset["numericMeans"] = {
                        column.get("name"): column["mean"]
                        for column in profile.get("columns") or []
                        if isinstance(column, dict)
                        and isinstance(column.get("mean"), (int, float))}
                measured(steps, "estimate", lambda: client.get_json(
                    node_path(workflow, node, "estimate"), "estimate"))
    finally:
        delete_workflow(client, workflow)
    dataset["memory"]["afterReader"] = memory_snapshot(engine, client)
    explore = measured(steps, "explore", lambda: client.post_json(
        "/v1/explore", {"dataset": parquet, "sampleRows": 1000}, "explore"))
    if explore:
        dataset["exploreRows"] = explore.get("rows")
        dataset["exploreQuality"] = (explore.get("quality") or {}).get("score")
    dataset["memory"]["afterExplore"] = memory_snapshot(engine, client)
    sql = entry.get("sql") or "SELECT COUNT(*) AS n FROM src"
    run_query_workflow(client, steps, "sql", parquet, "dap.sql_query",
                       {"sql": sql, "inputs": ["src"], "limit": 1000}, timeout)
    numeric = entry.get("numeric_columns") or []
    if len(numeric) >= 2:
        run_query_workflow(client, steps, "correlation", parquet, "dap.correlation",
                           {"columns": numeric, "method": "pearson"}, timeout)
    regression = regression_settings(entry)
    if regression:
        run_query_workflow(client, steps, "regression", parquet,
                           "dap.linear_regression", regression, timeout)
    group = entry.get("group_column")
    if group:
        group_sql = ("SELECT %s AS grp, COUNT(*) AS n FROM src GROUP BY 1 ORDER BY n DESC"
                     % quote_identifier(group))
        table = run_query_workflow(client, steps, "group_counts", parquet, "dap.sql_query",
                                   {"sql": group_sql, "inputs": ["src"], "limit": 1000},
                                   timeout)
        dataset["groupCounts"] = group_counts_from_table(table)
    dataset["memory"]["afterHeavy"] = memory_snapshot(engine, client)
    return dataset


# --------------------------------------------------------------------- run


def environment_info(args, data_dir):
    jar = pathlib.Path(args.jar)
    return {
        "platform": platform.platform(),
        "python": platform.python_version(),
        "java": java_version(resolve_java(args.java)) or "n/a",
        "heap": args.heap,
        "duckdbMemory": args.duckdb_memory,
        "jar": "%s (%s)" % (jar, format_bytes(file_size(jar))),
        "dataDir": str(data_dir),
        "manifest": str(args.manifest),
        "timeoutSec": args.timeout,
    }


def read_reference(path):
    """Read a dataset -> rows/numeric_means/group_counts reference JSON."""
    path = pathlib.Path(path)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise BenchError("reference %s is unreadable: %s" % (path, exc))
    if not isinstance(data, dict):
        raise BenchError("reference %s must map a dataset name to metrics" % path)
    return data


def load_reference(data_dir):
    path = pathlib.Path(data_dir) / "reference.json"
    if not path.is_file():
        return None
    return read_reference(path)


def observed_accuracy(dataset):
    rows = dataset.get("profileRows")
    if rows is None:
        rows = dataset.get("exploreRows")
    return {"rows": rows,
            "numeric_means": dataset.get("numericMeans") or {},
            "group_counts": dataset.get("groupCounts") or {}}


def step_ms(dataset, name):
    step = (dataset.get("steps") or {}).get(name)
    if not step or not step.get("ok"):
        return "n/a"
    return "%.1f" % step["ms"]


def run(args):
    args.jar = str(resolve_path(args.jar))
    args.data_dir = str(resolve_path(args.data_dir))
    args.out = str(resolve_path(args.out))
    args.manifest = str(resolve_path(args.manifest))
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    data_dir = pathlib.Path(args.data_dir)
    entries = select_entries(load_manifest(args.manifest), parse_datasets(args.datasets))
    if not entries:
        raise BenchError("manifest %s selects no datasets" % args.manifest)
    reference = (read_reference(resolve_path(args.reference)) if args.reference
                 else load_reference(data_dir))
    engine = Engine(args)
    summary = {
        "format": "dap-p6-bench/1",
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "environment": environment_info(args, data_dir),
        "engine": {"base": engine.base, "version": None, "startMs": None,
                   "healthMs": None, "memoryBefore": None, "memoryAfter": None},
        "datasets": [],
        "mcp": {"enabled": False, "calls": [], "callCount": 0, "totalMs": 0.0,
                "totalBytes": 0, "totalTokens": 0, "comparison": [], "error": None},
        "accuracy": {"available": False, "note": "not measured"},
        "totals": {"datasets": 0, "steps": 0, "failed": 0},
    }
    client = None
    try:
        engine.start_engine()
        summary["engine"]["startMs"] = (round(engine.start_ms, 3)
                                        if engine.start_ms is not None else None)
        client = Client(engine.base, args.timeout)
        reply = client.request("GET", "/v1/health")
        health = json.loads(reply.text) if reply.body else {}
        engine._check_health(health)
        summary["engine"]["version"] = health.get("version")
        summary["engine"]["healthMs"] = round(reply.ms, 3)
        summary["engine"]["memoryBefore"] = health.get("memory")
        for entry in entries:
            print("[p6-bench] %s (%s) ..." % (entry["name"], entry["path"]), flush=True)
            try:
                dataset = measure_dataset(engine, client, entry, args.timeout)
            except Exception as exc:  # noqa: BLE001 — one dataset must not end the run
                dataset = {"name": entry["name"], "path": entry["path"],
                           "format": entry["format"], "steps": {},
                           "error": "%s: %s" % (type(exc).__name__, exc),
                           "traceback": traceback.format_exc()}
                print("[p6-bench] %s failed: %s" % (entry["name"], dataset["traceback"]),
                      file=sys.stderr, flush=True)
            if reference is not None:
                dataset["accuracy"] = reference_diff(reference.get(entry["name"]),
                                                     observed_accuracy(dataset))
            (out / ("%s.json" % entry["name"])).write_text(
                json.dumps(dataset, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            summary["datasets"].append(dataset)
            print("[p6-bench] %s: ingest %s ms, run %s ms, table %s ms, "
                  "profile %s ms, explore %s ms" % (
                      entry["name"], step_ms(dataset, "ingest"),
                      step_ms(dataset, "reader_run"), step_ms(dataset, "table_first"),
                      step_ms(dataset, "profile"), step_ms(dataset, "explore")),
                  flush=True)
        if args.mcp:
            primary = summary["datasets"][0] if summary["datasets"] else None
            if primary and primary.get("parquet"):
                summary["mcp"] = measure_mcp(engine, primary, entries[0], args)
            else:
                summary["mcp"] = {"enabled": True, "calls": [], "callCount": 0,
                                  "failedCalls": 0, "totalMs": 0.0, "totalBytes": 0,
                                  "totalTokens": 0, "comparison": [],
                                  "error": "no measured dataset with an ingested Parquet path"}
            print("[p6-bench] MCP: %d call(s), %d failed, %s ms total" % (
                summary["mcp"]["callCount"], summary["mcp"].get("failedCalls", 0),
                _number(summary["mcp"]["totalMs"])), flush=True)
        if reference is None:
            summary["accuracy"] = {
                "available": False,
                "note": "no reference.json in %s; accuracy skipped" % data_dir}
        else:
            compared = [dataset["name"] for dataset in summary["datasets"]
                        if dataset.get("accuracy")]
            if compared:
                summary["accuracy"] = {"available": True, "datasets": compared}
            else:
                summary["accuracy"] = {
                    "available": False,
                    "note": "reference has no entry for the measured datasets"}
        steps_total = 0
        failed = 0
        for dataset in summary["datasets"]:
            for step in (dataset.get("steps") or {}).values():
                steps_total += 1
                if not step.get("ok"):
                    failed += 1
        summary["totals"] = {"datasets": len(summary["datasets"]),
                             "steps": steps_total, "failed": failed}
        reply = client.request("GET", "/v1/health")
        summary["engine"]["memoryAfter"] = (
            json.loads(reply.text).get("memory") if reply.body else None)
        (out / "summary.json").write_text(
            json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        (out / "summary.md").write_text(render_summary_md(summary), encoding="utf-8")
        return summary
    finally:
        if client is not None:
            client.close()
        engine.stop()


# --------------------------------------------------------------------- CLI


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        description="P6 benchmark harness for dap-native (stdlib only)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("--jar", default=DEFAULT_JAR,
                        help="path to dap-native.jar")
    parser.add_argument("--data-dir", default=DEFAULT_DATA_DIR,
                        help="dataset directory; reference.json is read from here")
    parser.add_argument("--manifest", default=str(ROOT / "tests" / "p6_bench_manifest.example.json"),
                        help="JSON manifest of datasets")
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="results directory (per-dataset JSON, summary, logs)")
    parser.add_argument("--base", default=DEFAULT_BASE,
                        help="engine base URL for --no-start")
    parser.add_argument("--start", action=argparse.BooleanOptionalAction, default=True,
                        help="spawn and stop the engine")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT,
                        help="port for the spawned engine")
    parser.add_argument("--heap", default=DEFAULT_HEAP, help="JVM -Xmx value")
    parser.add_argument("--duckdb-memory", default=DEFAULT_DUCKDB_MEMORY,
                        help="DAP_DUCKDB_MEMORY_LIMIT for the spawned engine")
    parser.add_argument("--datasets", default=None,
                        help="comma list of dataset names to measure (default: all)")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                        help="per-operation timeout in seconds")
    parser.add_argument("--mcp", action=argparse.BooleanOptionalAction, default=True,
                        help="measure the MCP adapter's one-call tools")
    parser.add_argument("--java", default=None,
                        help="java binary (default: $DAP_JDK, $JAVA_HOME, then PATH)")
    parser.add_argument("--gate", default=None,
                        help="CI gate JSON; exit 1 on violations, 2 if unreadable")
    parser.add_argument("--reference", default=None,
                        help="checked-in reference JSON (dataset -> rows/means/counts)")
    parser.add_argument("--json", default=None,
                        help="gate result JSON (default: <out>/gate.json with --gate)")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    gate = None
    if args.gate:
        try:
            gate = load_gate(args.gate)
        except (BenchError, OSError, ValueError) as exc:
            print("p6-bench: %s" % exc, file=sys.stderr)
            return 2
    try:
        summary = run(args)
    except (BenchError, OSError, ValueError) as exc:
        print("p6-bench: %s" % exc, file=sys.stderr)
        return 2
    print("p6-bench: %d dataset(s), %d step(s), %d failed; wrote %s" % (
        summary["totals"]["datasets"], summary["totals"]["steps"],
        summary["totals"]["failed"],
        pathlib.Path(args.out).resolve()), flush=True)
    if gate is None:
        return 0
    violations = check_gate(summary, gate)
    mcp = summary.get("mcp") or {}
    totals = summary.get("totals") or {}
    accuracy = summary.get("accuracy") or {}
    report = {
        "format": "dap-p6-bench-gate/1",
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "ok": not violations,
        "violations": violations,
        "gate": gate,
        "observed": {
            "failedSteps": totals.get("failed", 0),
            "mcpCalls": mcp.get("callCount", 0),
            "mcpFailedCalls": mcp.get("failedCalls", 0),
            "mcpTokens": mcp.get("totalTokens", 0),
            "accuracyAvailable": accuracy.get("available", False),
        },
        "summary": str(pathlib.Path(args.out) / "summary.json"),
    }
    json_path = resolve_path(args.json) if args.json else pathlib.Path(args.out) / "gate.json"
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if violations:
        for violation in violations:
            print("p6-bench gate: %s" % violation, file=sys.stderr)
        print("p6-bench gate: FAIL (%d violation(s)); wrote %s" % (
            len(violations), json_path), file=sys.stderr)
        return 1
    print("p6-bench gate: PASS; wrote %s" % json_path, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
