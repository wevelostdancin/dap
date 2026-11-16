#!/usr/bin/env python3
"""Run the native golden suite against a disposable engine.

Starts engine-native/target/dap-native.jar on 127.0.0.1:7811 with its own
data directory, waits for /v1/health, runs tests/goldens-native/runner.py,
then POSTs /v1/shutdown. The resident service on :7800 is never touched.

Usage:
    python3 tests/run_goldens.py            # run and shut down
    python3 tests/run_goldens.py --keep     # leave the engine running
"""

from __future__ import annotations

import argparse
import os
import pathlib
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAR = ROOT / "engine-native" / "target" / "dap-native.jar"
RUNNER = ROOT / "tests" / "goldens-native" / "runner.py"
DATA_DIR = pathlib.Path("/tmp/dap-goldens")
LOG_FILE = DATA_DIR / "engine.log"
HOST = "127.0.0.1"
PORT = 7811
BASE = "http://%s:%d" % (HOST, PORT)
START_TIMEOUT = 60.0


def java_binary() -> str:
    home = (os.environ.get("DAP_JDK") or os.environ.get("JAVA_HOME")
            or str(pathlib.Path.home() / "Projects/tools/jdk-21.0.13+9/Contents/Home"))
    candidate = pathlib.Path(home) / "bin" / "java"
    if candidate.is_file():
        return str(candidate)
    raise SystemExit("java not found: set DAP_JDK to a JDK 21 home")


def port_open() -> bool:
    try:
        with socket.create_connection((HOST, PORT), timeout=0.5):
            return True
    except OSError:
        return False


def health() -> bool:
    try:
        with urllib.request.urlopen(BASE + "/v1/health", timeout=1) as response:
            return response.status == 200
    except (urllib.error.URLError, OSError):
        return False


def wait_health(proc: subprocess.Popen, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            return False
        if health():
            return True
        time.sleep(0.1)
    return False


def shutdown() -> None:
    try:
        request = urllib.request.Request(BASE + "/v1/shutdown", method="POST", data=b"{}")
        urllib.request.urlopen(request, timeout=5).read()
    except (urllib.error.URLError, OSError):
        pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--keep", action="store_true", help="leave the engine running")
    parser.add_argument("--only", action="append", metavar="PREFIX")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    if not JAR.is_file():
        raise SystemExit("missing %s: run make build-native" % JAR)
    if port_open():
        raise SystemExit("port %d is already in use; stop that service first" % PORT)

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, DAP_PORT=str(PORT), DAP_BIND=HOST,
               DAP_DUCKDB_TEMP_DIR=str(DATA_DIR / "tmp"))
    with LOG_FILE.open("wb") as log:
        proc = subprocess.Popen([java_binary(), "-jar", str(JAR)],
                                cwd=str(ROOT), env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        if not wait_health(proc, START_TIMEOUT):
            tail = LOG_FILE.read_text(errors="replace").splitlines()[-30:]
            raise SystemExit("engine did not become healthy in %.0fs\n%s"
                             % (START_TIMEOUT, "\n".join(tail)))
        command = [sys.executable, str(RUNNER), "--base", BASE]
        for prefix in args.only or []:
            command += ["--only", prefix]
        if args.verbose:
            command.append("--verbose")
        return subprocess.call(command)
    finally:
        if not args.keep:
            shutdown()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    sys.exit(main())
