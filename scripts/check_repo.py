#!/usr/bin/env python3
"""Static checks for DAP (native engine only).

No KNIME directories, no legacy tests. Exit code 0 when everything passes.
"""

from __future__ import annotations

import json
import pathlib
import py_compile
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ERRORS: list[str] = []
WARNINGS: list[str] = []


def fail(message: str) -> None:
    ERRORS.append(message)


def warn(message: str) -> None:
    WARNINGS.append(message)


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def check_layout() -> None:
    for legacy in ("engine", "catalog", "runtime"):
        if (ROOT / legacy).exists():
            fail(f"legacy directory still present: {legacy}/")
    for required in ("README.md", "docs/ROADMAP.md", "docs/ARCHITECTURE.md",
                     "docs/NODES.md", "docs/QUALITY.md", "docs/API.md"):
        if not (ROOT / required).is_file():
            fail(f"missing document: {required}")


def check_version() -> None:
    version = read("VERSION").strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        fail(f"VERSION is not semver: {version!r}")
    openapi = read("api/openapi.yaml")
    match = re.search(r"^\s{2}version:\s*(\S+)", openapi, re.M)
    if not match:
        fail("api/openapi.yaml: info.version missing")
    elif match.group(1).strip('"') != version:
        fail(f"OpenAPI version {match.group(1)} != VERSION {version}")
    adapter = read("adapters/mcp/dap_mcp.py")
    if f'"version": "{version}"' not in adapter:
        warn(f"adapters/mcp/dap_mcp.py does not advertise version {version}")


def check_openapi() -> None:
    try:
        import yaml  # type: ignore
    except ImportError:
        # No PyYAML: fall back to a structural text check so CI still catches
        # a removed path without extra dependencies.
        text = read("api/openapi.yaml")
        for required in ("/v1/health", "/v1/nodes", "/v1/workflows", "/v1/jobs",
                         "/v1/workflows/load", "/v1/datasets", "/v1/shutdown"):
            if not re.search(r"^  " + re.escape(required) + r":", text, re.M):
                fail(f"OpenAPI misses path {required}")
        return
    try:
        spec = yaml.safe_load(read("api/openapi.yaml"))
    except Exception as exc:  # noqa: BLE001
        fail(f"api/openapi.yaml is not valid YAML: {exc}")
        return
    if not isinstance(spec, dict) or spec.get("openapi") != "3.1.0":
        fail("api/openapi.yaml: expected openapi: 3.1.0")
        return
    paths = spec.get("paths") or {}
    for required in ("/v1/health", "/v1/nodes", "/v1/workflows", "/v1/jobs",
                     "/v1/workflows/load", "/v1/datasets", "/v1/shutdown"):
        if required not in paths:
            fail(f"OpenAPI misses path {required}")


def check_native_boundary() -> None:
    hits = []
    for path in (ROOT / "engine-native").rglob("*"):
        if path.is_file() and path.suffix in (".java", ".json", ".xml", ".properties"):
            try:
                text = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            if "org.knime" in text:
                hits.append(str(path.relative_to(ROOT)))
    if hits:
        fail("org.knime references in engine-native: " + ", ".join(hits[:5]))


def check_specs() -> None:
    specs = sorted((ROOT / "engine-native/src/main/resources/specs").glob("*.json"))
    if len(specs) < 11:
        fail(f"expected at least 11 node specs, found {len(specs)}")
    for path in specs:
        try:
            spec = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001
            fail(f"{path.name}: invalid JSON: {exc}")
            continue
        if not spec.get("id") or not spec.get("configSchema"):
            fail(f"{path.name}: id and configSchema are required")


def check_jar() -> None:
    jar = ROOT / "engine-native/target/dap-native.jar"
    if not jar.is_file():
        warn("dap-native.jar not built; jar checks skipped")
        return
    with zipfile.ZipFile(jar) as archive:
        native = [name for name in archive.namelist() if "libduckdb" in name]
    if not native:
        fail("jar contains no DuckDB native library")
        return
    if len(native) > 1:
        # Documented cross-platform build (-Ddap.duckdb.native=all): four native
        # libraries are expected, so the single-platform size limit does not apply.
        warn(f"jar contains {len(native)} DuckDB native libraries (cross-platform build)")
        return
    size_mib = jar.stat().st_size / (1024 * 1024)
    if size_mib >= 50:
        fail(f"jar is {size_mib:.1f} MiB (limit 50 MiB)")


def check_python() -> None:
    paths = (list((ROOT / "tests").glob("*.py"))
             + list((ROOT / "tests/goldens-native").glob("*.py"))
             + list((ROOT / "adapters/mcp").glob("*.py")))
    for path in sorted(paths):
        try:
            compile(path.read_text(encoding="utf-8"), str(path), "exec")
        except SyntaxError as exc:
            fail(f"{path.name}: {exc}")


LEGACY_MARKERS = ("org.knime", "tests/corpus", "tests/parity", "tests/baseline.json",
                  "tests/run_all", "scripts/run.sh", "runtime/knime")
SKIP_PARTS = {".git", ".worktrees", "workspace", "build", "target", ".tools",
              "node_modules", "__pycache__"}
SCAN_SUFFIXES = {".java", ".py", ".md", ".yml", ".yaml", ".json", ".xml", ".sh"}


def check_legacy_markers() -> None:
    hits = []
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix not in SCAN_SUFFIXES and path.name not in ("Makefile", "Dockerfile"):
            continue
        if path == pathlib.Path(__file__).resolve():
            continue
        if any(part in SKIP_PARTS for part in path.relative_to(ROOT).parts):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for marker in LEGACY_MARKERS:
            if marker in text:
                hits.append(f"{path.relative_to(ROOT)}: {marker}")
    if hits:
        fail("legacy references: " + "; ".join(hits[:8]))


CJK_RE = re.compile(r"[\u1100-\u11ff\u2e80-\u303f\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uac00-\ud7af\uf900-\ufaff\uff00-\uffef]")
DOC_SUFFIXES = {".md", ".yml", ".yaml"}
DOC_SKIP_PARTS = {".git", ".worktrees", "build", "target", "node_modules", "__pycache__"}
DOC_SKIP_PREFIXES = {("tests", "fixtures"), ("tests", "goldens-native")}


def find_cjk_lines(text: str) -> list[tuple[int, str]]:
    """Lines that contain CJK ideographs (Chinese/Japanese/Korean)."""
    return [
        (number, line)
        for number, line in enumerate(text.splitlines(), 1)
        if CJK_RE.search(line)
    ]


def check_docs_language() -> None:
    """Documentation must stay Russian/English: no CJK characters in md/yml/yaml."""
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file() or path.suffix not in DOC_SUFFIXES:
            continue
        relative = path.relative_to(ROOT)
        if any(part in DOC_SKIP_PARTS for part in relative.parts):
            continue
        if relative.parts[:2] in DOC_SKIP_PREFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for number, line in find_cjk_lines(text):
            fail(f"CJK characters in {relative}:{number}: {line.strip()[:80]}")


def check_shell() -> None:
    if shutil.which("bash") is None:
        warn("bash not found; shell syntax skipped")
        return
    for path in sorted((ROOT / "scripts").glob("*.sh")):
        proc = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
        if proc.returncode != 0:
            fail(f"bash -n {path.name}: {proc.stderr.strip()}")


def main() -> int:
    check_layout()
    check_version()
    check_openapi()
    check_native_boundary()
    check_specs()
    check_jar()
    check_python()
    check_legacy_markers()
    check_docs_language()
    check_shell()
    for message in WARNINGS:
        print("WARN  " + message)
    for message in ERRORS:
        print("FAIL  " + message)
    print(f"repo: {len(ERRORS)} error(s), {len(WARNINGS)} warning(s)")
    return 1 if ERRORS else 0


if __name__ == "__main__":
    sys.exit(main())
