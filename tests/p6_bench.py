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
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.parse

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
    return "%.4g" % value


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
