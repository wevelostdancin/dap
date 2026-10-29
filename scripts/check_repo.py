#!/usr/bin/env python3
"""Static checks for the DAP repository (used by CI and locally).

No external dependencies except optional PyYAML for OpenAPI parsing.
Exit code 0 when all checks pass, 1 otherwise.
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ERRORS: list[str] = []
WARNINGS: list[str] = []


def fail(msg: str) -> None:
    ERRORS.append(msg)


def warn(msg: str) -> None:
    WARNINGS.append(msg)


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def check_version() -> None:
    version = read("VERSION").strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        fail(f"VERSION is not semver: {version!r}")
    manifest = read("engine/META-INF/MANIFEST.MF")
    m = re.search(r"^Bundle-Version:\s*(\S+)", manifest, re.M)
    if not m:
        fail("engine/META-INF/MANIFEST.MF: Bundle-Version missing")
    elif m.group(1) != version:
        fail(f"Bundle-Version {m.group(1)} != VERSION {version}")
    openapi = read("api/openapi.yaml")
    m = re.search(r"^\s{2}version:\s*(\S+)", openapi, re.M)
    if not m:
        fail("api/openapi.yaml: info.version missing")
    elif m.group(1).strip('"') != version:
        fail(f"OpenAPI version {m.group(1)} != VERSION {version}")
    mcp = read("adapters/mcp/dap_mcp.py")
    if f'"version": "{version}"' not in mcp:
        warn(f"adapters/mcp/dap_mcp.py does not advertise version {version}")


def check_shell() -> None:
    if shutil.which("bash") is None:
        warn("bash not found; shell syntax skipped")
        return
    for path in sorted((ROOT / "scripts").glob("*.sh")):
        proc = subprocess.run(["bash", "-n", str(path)], capture_output=True, text=True)
        if proc.returncode != 0:
            fail(f"bash -n {path.name}: {proc.stderr.strip()}")
    if shutil.which("shellcheck"):
        for path in sorted((ROOT / "scripts").glob("*.sh")):
            proc = subprocess.run(["shellcheck", "-S", "warning", str(path)],
                                  capture_output=True, text=True)
            if proc.returncode != 0:
                fail(f"shellcheck {path.name}: {proc.stdout.strip()}")


def check_openapi() -> None:
    try:
        import yaml  # type: ignore
    except ImportError:
        warn("PyYAML not installed; OpenAPI structure check skipped")
        return
    try:
        spec = yaml.safe_load(read("api/openapi.yaml"))
    except Exception as exc:  # noqa: BLE001
        fail(f"api/openapi.yaml is not valid YAML: {exc}")
        return
    if not isinstance(spec, dict) or spec.get("openapi") != "3.1.0":
        fail("api/openapi.yaml: expected openapi: 3.1.0")
    paths = spec.get("paths") or {}
    for required in ["/v1/health", "/v1/nodes", "/v1/workflows", "/v1/jobs"]:
        if required not in paths:
            fail(f"OpenAPI misses path {required}")
    if not any(re.fullmatch(r"/v1/workflows/\{[^}]+\}/jobs", p) for p in paths):
        warn("OpenAPI has no /v1/workflows/{workflow}/jobs path")


def check_frozen_contract() -> None:
    frozen = ROOT / "api" / "openapi-v1-frozen.yaml"
    checksum = ROOT / "api" / "openapi-v1-frozen.sha256"
    if not frozen.exists() or not checksum.exists():
        fail("api/openapi-v1-frozen.yaml or its .sha256 is missing")
        return
    expected = checksum.read_text(encoding="utf-8").split()[0]
    actual = hashlib.sha256(frozen.read_bytes()).hexdigest()
    if actual != expected:
        fail("api/openapi-v1-frozen.yaml checksum " + actual + " != " + expected
             + " (frozen contract was edited)")
    live = hashlib.sha256((ROOT / "api" / "openapi.yaml").read_bytes()).hexdigest()
    if live != actual:
        fail("api/openapi.yaml drifted from api/openapi-v1-frozen.yaml: update the frozen copy, "
             "its .sha256 and record the change in CHANGELOG.md")


def check_catalog() -> None:
    try:
        catalog = json.loads(read("catalog/catalog.json"))
        curated = json.loads(read("catalog/curated.json"))
    except Exception as exc:  # noqa: BLE001
        fail(f"catalog JSON is invalid: {exc}")
        return
    nodes = catalog.get("nodes") or []
    ids = {n.get("id") for n in nodes}
    core = curated.get("core") or []
    if not nodes:
        fail("catalog/catalog.json has no nodes")
    if not core:
        fail("catalog/curated.json has no core nodes")
    missing = [x for x in core if x not in ids]
    if missing:
        fail(f"curated core ids missing from catalog: {missing[:5]}")
    if len(core) < 100 or len(core) > 200:
        warn(f"core size is unusual: {len(core)}")
    unique = len(ids)
    if unique != len(nodes):
        warn(f"catalog has {len(nodes)} entries but {unique} unique ids")


def check_stale() -> None:
    stale = []
    for path in ROOT.rglob("*.md"):
        if ".git" in path.parts or "runtime" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if "scripts/build-runtime.sh" in text:
            stale.append(str(path.relative_to(ROOT)))
    if stale:
        warn("stale build-runtime.sh references: " + ", ".join(stale))


def check_python_syntax() -> None:
    for path in list((ROOT / "adapters").rglob("*.py")) + list((ROOT / "tests").rglob("*.py")):
        try:
            compile(path.read_text(encoding="utf-8"), str(path), "exec")
        except SyntaxError as exc:
            fail(f"python syntax {path.relative_to(ROOT)}: {exc}")


def main() -> int:
    check_version()
    check_shell()
    check_openapi()
    check_frozen_contract()
    check_catalog()
    check_stale()
    check_python_syntax()
    for msg in WARNINGS:
        print(f"WARN  {msg}")
    for msg in ERRORS:
        print(f"FAIL  {msg}")
    if ERRORS:
        print(f"\n{len(ERRORS)} error(s), {len(WARNINGS)} warning(s)")
        return 1
    print(f"OK: {len(WARNINGS)} warning(s), 0 errors")
    return 0


if __name__ == "__main__":
    sys.exit(main())
