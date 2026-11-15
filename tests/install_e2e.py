#!/usr/bin/env python3
"""End-to-end install check for the dist artifact (stdlib only).

Creates a temporary HOME with fixture client configs, installs DAP into both
clients from dist/, asserts the generated entries and runs the direct stdio
preflight against the shipped adapter. The real HOME and client configs are
never touched.

    make install-e2e
    python3 tests/install_e2e.py --dist dist
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    print(f"{'PASS' if ok else 'FAIL'}  {name:<26} {detail}".rstrip())
    if not ok:
        FAILURES.append(name)
    return ok


def load_cli(dist: pathlib.Path):
    """Load the shipped dap_cli module so the preflight code under test is dist's."""
    spec = importlib.util.spec_from_file_location(
        "dap_cli_dist", dist / "adapters" / "mcp" / "dap_cli.py"
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def resolve_java_home() -> str:
    candidates = [
        os.environ.get("JAVA_HOME"),
        os.environ.get("DAP_JDK"),
        str(pathlib.Path.home() / "Projects" / "tools" / "jdk-21.0.13+9" / "Contents" / "Home"),
    ]
    for candidate in candidates:
        if candidate and (pathlib.Path(candidate) / "bin" / "java").is_file():
            return str(candidate)
    return ""


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def engine_healthy(port: int) -> bool:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/health", timeout=2) as response:
            return response.status == 200
    except OSError:
        return False


def shutdown_engine(port: int) -> None:
    try:
        request = urllib.request.Request(f"http://127.0.0.1:{port}/v1/shutdown", method="POST")
        urllib.request.urlopen(request, timeout=5).close()
    except OSError:
        pass


def wait_engine_stopped(port: int, timeout: float = 15.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and engine_healthy(port):
        time.sleep(0.2)
    return not engine_healthy(port)


def run_doctor(cli_path: pathlib.Path, dist: pathlib.Path, env: dict):
    return subprocess.run(
        [
            sys.executable,
            str(cli_path),
            "doctor",
            "--client",
            "hermes",
            "--home",
            str(dist),
        ],
        capture_output=True,
        text=True,
        env=env,
        timeout=180,
    )


def tail(result) -> str:
    lines = (result.stdout or "").strip().splitlines()
    if lines:
        return lines[-1]
    return (result.stderr or "").strip()


def run_install(cli_path: pathlib.Path, client: str, dist: pathlib.Path, env: dict):
    return subprocess.run(
        [
            sys.executable,
            str(cli_path),
            "install",
            "--client",
            client,
            "--home",
            str(dist),
        ],
        capture_output=True,
        text=True,
        env=env,
    )


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="DAP dist install end-to-end check")
    parser.add_argument("--dist", default=str(ROOT / "dist"), help="dist directory")
    args = parser.parse_args(argv)
    dist = pathlib.Path(args.dist).resolve()
    cli_path = dist / "adapters" / "mcp" / "dap_cli.py"
    jar_path = dist / "engine-native" / "target" / "dap-native.jar"
    adapter_path = dist / "adapters" / "mcp" / "dap_mcp.py"

    if not check("dist adapters", cli_path.is_file() and adapter_path.is_file(), str(dist)):
        return 1
    check("dist jar", jar_path.is_file(), str(jar_path))
    check("dist scripts", (dist / "scripts" / "dap-mcp").is_file())
    check("dist VERSION", (dist / "VERSION").is_file())
    check("dist SHA256SUMS", (dist / "SHA256SUMS").is_file())

    with tempfile.TemporaryDirectory(prefix="dap-install-e2e-") as directory:
        home = pathlib.Path(directory)
        hermes = home / ".hermes" / "config.yaml"
        hermes.parent.mkdir(parents=True)
        hermes.write_text(
            "mcp_servers:\n  existing:\n    command: existing-server\n",
            encoding="utf-8",
        )
        dsh = home / ".dsh" / "profiles" / "desktop" / "cordis.patch.yml"
        dsh.parent.mkdir(parents=True)
        shutil.copyfile(ROOT / "tests" / "fixtures" / "cordis.patch.yml", dsh)

        environment = dict(os.environ, HOME=str(home), DAP_HOME=str(dist))
        for client in ("hermes", "dsh"):
            result = run_install(cli_path, client, dist, environment)
            check(
                f"install {client}",
                result.returncode == 0,
                result.stderr.strip() or result.stdout.strip(),
            )

        hermes_text = hermes.read_text(encoding="utf-8")
        dsh_text = dsh.read_text(encoding="utf-8")
        check("hermes has dap", "  dap:" in hermes_text)
        check("hermes keeps existing", "  existing:" in hermes_text)
        check("dsh has mcp-manager", "- id: mcp-manager" in dsh_text and "      dap:" in dsh_text)
        check("dsh keeps ui-chat", "- id: ui-chat" in dsh_text)
        entry_adapter = str(adapter_path)
        check(
            "entries point into dist",
            entry_adapter in hermes_text and entry_adapter in dsh_text,
            entry_adapter,
        )

        cli = load_cli(dist)
        try:
            count = cli.stdio_preflight(dist)
        except Exception as exc:  # noqa: BLE001 - any failure is a failed check
            check("stdio preflight", False, f"{type(exc).__name__}: {exc}")
        else:
            check(
                "stdio preflight",
                count >= cli.MIN_TOOLS,
                f"tools/list returned {count} tools (need >= {cli.MIN_TOOLS})",
            )

        java_home = resolve_java_home()
        check("java home", bool(java_home), java_home or "set JAVA_HOME or DAP_JDK")
        port = free_port()
        environment["DAP_PORT"] = str(port)
        if java_home:
            environment["JAVA_HOME"] = java_home
        try:
            if java_home and not engine_healthy(port):
                fresh = run_doctor(cli_path, dist, environment)
                check("doctor fresh install", fresh.returncode == 0, tail(fresh))
                check("engine started by doctor", engine_healthy(port), f"port {port}")
                live = run_doctor(cli_path, dist, environment)
                check("doctor live engine", live.returncode == 0, tail(live))
            elif java_home:
                live = run_doctor(cli_path, dist, environment)
                check("doctor live engine", live.returncode == 0, tail(live))
        finally:
            shutdown_engine(port)
            check("engine stopped", wait_engine_stopped(port))

    print()
    if FAILURES:
        print(f"install e2e: {len(FAILURES)} failure(s): {', '.join(FAILURES)}")
        return 1
    print("install e2e: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
