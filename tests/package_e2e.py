#!/usr/bin/env python3
"""End-to-end check of the self-contained archive and the installer (stdlib only).

Builds the archive with scripts/package.py (system Python, no download), unpacks
it into a temporary HOME, verifies SHA256SUMS, runs the launcher, starts the
engine, registers DAP in a fixture Hermes config, runs doctor and shuts down.

    python3 tests/package_e2e.py
    python3 tests/package_e2e.py --keep

The full zero-dependency archive (bundled Python) is built by CI with
scripts/python-runtime.sh; this test exercises the same layout with the system
Python so it runs on a developer machine without a 30 MB download.
"""
from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
JAR = ROOT / "engine-native" / "target" / "dap-native.jar"
FAILURES: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    print(f"{'PASS' if ok else 'FAIL'}  {name:<26} {detail}".rstrip())
    if not ok:
        FAILURES.append(name)
    return ok


def java_home() -> str:
    for candidate in (os.environ.get("DAP_JDK"), os.environ.get("JAVA_HOME")):
        if candidate and (pathlib.Path(candidate) / "bin" / "java").is_file():
            return candidate
    fallback = pathlib.Path.home() / "Projects" / "tools" / "jdk-21.0.13+9" / "Contents" / "Home"
    return str(fallback) if (fallback / "bin" / "java").is_file() else ""


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def healthy(port: int) -> bool:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/health", timeout=2) as response:
            return response.status == 200
    except OSError:
        return False


def shutdown(port: int) -> None:
    try:
        request = urllib.request.Request(f"http://127.0.0.1:{port}/v1/shutdown", method="POST")
        urllib.request.urlopen(request, timeout=5).close()
    except OSError:
        pass


def run(command, env=None, cwd=None, timeout=120):
    return subprocess.run(command, capture_output=True, text=True, env=env, cwd=cwd,
                          timeout=timeout)


def build_runtime(jdk: str) -> bool:
    runtime = ROOT / "build" / "package" / "runtime"
    if (runtime / "bin" / "java").is_file():
        return True
    if not jdk:
        return False
    env = dict(os.environ, DAP_JDK=jdk)
    result = run(["sh", str(ROOT / "scripts" / "jlink-runtime.sh"),
                  "--jar", str(JAR), "--output", str(runtime)], env=env, timeout=300)
    if result.returncode != 0:
        print(result.stderr[-2000:])
    return result.returncode == 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="DAP package end-to-end check")
    parser.add_argument("--keep", action="store_true", help="keep the temporary directory")
    args = parser.parse_args(argv)

    jdk = java_home()
    if not check("jar", JAR.is_file(), str(JAR)):
        return 1
    if not check("jdk", bool(jdk), jdk or "set DAP_JDK"):
        return 1
    if not check("runtime", build_runtime(jdk), "build/package/runtime"):
        return 1

    workdir = pathlib.Path(tempfile.mkdtemp(prefix="dap-package-e2e-"))
    try:
        out = workdir / "out"
        result = run([sys.executable, str(ROOT / "scripts" / "package.py"),
                      "--skip-python", "--out", str(out)], cwd=str(ROOT), timeout=300)
        if not check("package", result.returncode == 0, result.stderr.strip()[-500:]):
            return 1
        archives = sorted(out.glob("dap-*.tar.gz"))
        if not check("archive", len(archives) == 1, str(archives)):
            return 1
        archive = archives[0]
        checksum = archive.with_suffix(archive.suffix + ".sha256")
        if not check("archive checksum", checksum.is_file(), str(checksum)):
            return 1

        home = workdir / "home"
        with tarfile.open(archive) as tar:
            names = tar.getnames()
            unsafe = [name for name in names if name.startswith("/") or ".." in pathlib.PurePosixPath(name).parts]
            if not check("archive paths", not unsafe, str(unsafe[:3])):
                return 1
            try:
                tar.extractall(home, filter="data")
            except TypeError:  # Python < 3.12 has no filter argument
                tar.extractall(home)
        top = next(home.iterdir())
        dap = top / "bin" / "dap"
        if not check("launcher", dap.is_file() and os.access(dap, os.X_OK), str(dap)):
            return 1

        sums = run(["shasum", "-a", "256", "-c", "SHA256SUMS"], cwd=str(top))
        if sums.returncode != 0:
            sums = run(["sha256sum", "-c", "SHA256SUMS"], cwd=str(top))
        check("SHA256SUMS", sums.returncode == 0, sums.stderr.strip()[-300:])

        version = (top / "VERSION").read_text(encoding="utf-8").strip()
        result = run([str(dap), "version"], env=dict(os.environ, HOME=str(home)))
        check("dap version", result.returncode == 0 and version in result.stdout,
              result.stdout.strip().replace("\n", " | ")[:120])

        port = free_port()
        env = dict(os.environ, HOME=str(home), DAP_PORT=str(port), DAP_BIND="127.0.0.1")
        engine = subprocess.Popen([str(dap), "engine"], env=env,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and not healthy(port):
                if engine.poll() is not None:
                    break
                time.sleep(0.1)
            check("engine health", healthy(port), f"127.0.0.1:{port}")

            client_config = home / ".hermes" / "config.yaml"
            client_config.parent.mkdir(parents=True, exist_ok=True)
            client_config.write_text("mcpServers: {}\n", encoding="utf-8")
            install = run([str(dap), "install", "--client", "hermes"], env=env, timeout=60)
            check("dap install", install.returncode == 0, install.stderr.strip()[-300:])
            doctor = run([str(dap), "doctor", "--client", "hermes"], env=env, timeout=120)
            check("dap doctor", doctor.returncode == 0, doctor.stdout.strip().splitlines()[-1] if doctor.stdout else "")
        finally:
            shutdown(port)
            try:
                engine.wait(timeout=15)
            except subprocess.TimeoutExpired:
                engine.terminate()
                engine.wait(timeout=5)
    finally:
        if args.keep:
            print("kept:", workdir)
        else:
            shutil.rmtree(workdir, ignore_errors=True)

    print()
    print(f"{len(FAILURES)} failure(s)")
    return 0 if not FAILURES else 1


if __name__ == "__main__":
    sys.exit(main())
