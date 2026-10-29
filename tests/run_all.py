#!/usr/bin/env python3
"""Self-contained golden + contract runner for the DAP engine.

Starts a disposable DAP instance on 127.0.0.1:7810 through scripts/run.sh with
its own data directory (/tmp/dap-golden-tests), waits for /v1/health, runs the
golden scenarios and the contract suite, then POSTs /v1/shutdown.

The resident service on 127.0.0.1:7800 is never touched: the runner does not
connect to it and refuses to start if port 7810 is already owned by anything.

Usage:
    python3 tests/run_all.py            # run, then shut the service down
    python3 tests/run_all.py --keep     # leave the service running for debugging

Exit code: 0 when no check FAILed (SKIPs are reported but allowed), 1 when at
least one check failed, 2 when the environment prevents the run.
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
import traceback
from pathlib import Path

TESTS_DIR = Path(__file__).resolve().parent
ROOT = TESTS_DIR.parent
sys.path.insert(0, str(TESTS_DIR))

import dap_client as dap  # noqa: E402

HOST = "127.0.0.1"
PORT = 7810
BASE = "http://%s:%d" % (HOST, PORT)
DATA_DIR = Path("/tmp/dap-golden-tests")
LOG_FILE = DATA_DIR / "service.log"
RUN_SH = ROOT / "scripts" / "run.sh"

START_TIMEOUT = 120.0
SHUTDOWN_TIMEOUT = 45.0
EXPECTED_NODES = 832
EXPECTED_CORE = 141

GOLDEN_ORDER = ["iris", "groupby", "join", "loop", "missing", "settings_error", "jobs"]


# --------------------------------------------------------------------- output


def banner(text):
    print(text, flush=True)


def tail(path, lines=40):
    try:
        content = Path(path).read_text(errors="replace").splitlines()
    except OSError as err:
        return "<cannot read %s: %s>" % (path, err)
    return "\n".join(content[-lines:])


# ------------------------------------------------------------------- service


def port_open():
    try:
        with socket.create_connection((HOST, PORT), timeout=0.5):
            return True
    except OSError:
        return False


def preflight():
    if not port_open():
        return
    detail = ""
    try:
        body = dap.Api(BASE, "runner").health()
        detail = " (DAP reports version %s, nodes %s)" % (body.get("version"), body.get("nodes"))
    except (dap.ApiError, AssertionError):
        detail = " (not answering as DAP)"
    raise SystemExit(
        "port %d is already in use%s.\n"
        "Refusing to hijack it: stop that instance first, e.g.\n"
        "  curl -X POST %s/v1/shutdown   # only if it is your test instance" % (PORT, detail, BASE))


def start_service():
    if not RUN_SH.is_file():
        raise SystemExit("service launcher not found: %s" % RUN_SH)
    shutil.rmtree(DATA_DIR, ignore_errors=True)
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    log_handle = open(LOG_FILE, "wb")
    env = os.environ.copy()
    env.update({"DAP_PORT": str(PORT), "DAP_DATA": str(DATA_DIR), "DAP_BIND": HOST})
    proc = subprocess.Popen(
        [str(RUN_SH)],
        cwd=str(ROOT),
        env=env,
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    return proc, log_handle


def wait_health(proc, timeout=START_TIMEOUT):
    api = dap.Api(BASE, "runner")
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        if proc.poll() is not None:
            raise SystemExit("service exited with code %s before becoming healthy.\n%s"
                             % (proc.returncode, tail(LOG_FILE)))
        try:
            body = api.health()
            if body.get("ok"):
                return body
        except (dap.ApiError, AssertionError) as err:
            last_error = err
        time.sleep(0.5)
    raise SystemExit("service did not become healthy within %.0fs (last error: %s).\n%s"
                     % (timeout, last_error, tail(LOG_FILE)))


def stop_service(proc):
    if proc is None or proc.poll() is not None:
        return
    try:
        dap.Api(BASE, "runner").post("/v1/shutdown", {}, timeout=10)
    except (dap.ApiError, AssertionError):
        pass
    try:
        proc.wait(timeout=SHUTDOWN_TIMEOUT)
        return
    except subprocess.TimeoutExpired:
        pass
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(proc.pid, sig)
        except OSError:
            return
        try:
            proc.wait(timeout=10)
            return
        except subprocess.TimeoutExpired:
            continue


# ------------------------------------------------------------------ scenarios


def load_module(module_name, path):
    spec = importlib.util.spec_from_file_location(module_name, str(path))
    if spec is None or spec.loader is None:
        raise ImportError("cannot load %s" % path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def execute(module, api, scenario):
    try:
        module.run(api, scenario)
    except dap.ConnectionFailed as err:
        scenario.checks.append(dap.Check("scenario.setup", dap.FAIL,
                                         "service unreachable: %s" % err))
    except Exception as err:  # noqa: BLE001 - setup errors are reported as failures
        scenario.checks.append(dap.Check(
            "scenario.setup", dap.FAIL,
            "%s: %s | %s" % (type(err).__name__, err,
                             traceback.format_exc().strip().splitlines()[-1])))


def run_all(args):
    started = time.time()
    banner("DAP golden/contract tests")
    banner("repo:    %s" % ROOT)
    banner("target:  %s  (DAP_DATA=%s)" % (BASE, DATA_DIR))
    banner("note:    the resident service on 127.0.0.1:7800 is not used or modified")
    banner("")

    preflight()
    proc = None
    log_handle = None
    scenarios = []
    exit_code = 0
    try:
        proc, log_handle = start_service()
        health = wait_health(proc)
        banner("service: pid=%d version=%s nodes=%d core=%d"
               % (proc.pid, health.get("version"), health.get("nodes"), health.get("coreNodes")))
        banner("")

        contract_path = TESTS_DIR / "contract" / "test_api.py"
        if contract_path.is_file():
            module = load_module("contract_test_api", contract_path)
            api = dap.Api(BASE, "golden-contract")
            scenario = dap.Scenario("contract")
            started_at = time.time()
            execute(module, api, scenario)
            print(scenario.render(), flush=True)
            print("  (%.2fs)" % (time.time() - started_at), flush=True)
            scenarios.append(scenario)

        golden_dir = TESTS_DIR / "golden"
        known = set(GOLDEN_ORDER)
        extra = sorted(path.stem for path in golden_dir.glob("*.py")
                       if not path.stem.startswith("_") and path.stem not in known)
        for name in GOLDEN_ORDER + extra:
            path = golden_dir / ("%s.py" % name)
            if not path.is_file():
                scenario = dap.Scenario(name)
                scenario.checks.append(dap.Check("scenario.file", dap.FAIL,
                                                 "missing scenario file %s" % path))
                print(scenario.render(), flush=True)
                scenarios.append(scenario)
                continue
            module = load_module("golden_" + name, path)
            api = dap.Api(BASE, "golden-" + name)
            scenario = dap.Scenario(name)
            started_at = time.time()
            execute(module, api, scenario)
            print(scenario.render(), flush=True)
            print("  (%.2fs)" % (time.time() - started_at), flush=True)
            scenarios.append(scenario)

        if proc.poll() is not None:
            banner("")
            banner("ERROR: test service died mid-run (code %s); see %s"
                   % (proc.returncode, LOG_FILE))
            banner(tail(LOG_FILE))
            exit_code = 1
    except KeyboardInterrupt:
        banner("")
        banner("interrupted")
        exit_code = 130
    finally:
        if proc is not None:
            if args.keep:
                banner("")
                banner("service kept running: %s (pid %d, data %s)"
                       % (BASE, proc.pid, DATA_DIR))
                banner("stop it with: curl -X POST %s/v1/shutdown" % BASE)
            else:
                stop_service(proc)
        if log_handle is not None:
            log_handle.close()

    counts = {dap.PASS: 0, dap.FAIL: 0, dap.SKIP: 0}
    failures = []
    for scenario in scenarios:
        for check in scenario.checks:
            counts[check.status] += 1
            if check.status == dap.FAIL:
                failures.append("%s / %s: %s" % (scenario.name, check.name, check.detail))

    banner("")
    banner("=" * 96)
    banner("SUMMARY  PASS %d  FAIL %d  SKIP %d  scenarios %d  elapsed %.1fs"
           % (counts[dap.PASS], counts[dap.FAIL], counts[dap.SKIP],
              len(scenarios), time.time() - started))
    if failures:
        banner("failures:")
        for failure in failures:
            banner("  - " + failure)
        exit_code = exit_code or 1
    banner("=" * 96)
    return exit_code


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--keep", action="store_true",
                        help="keep the disposable service on 127.0.0.1:7810 running after the run")
    return parser.parse_args(argv)


def main(argv=None):
    return run_all(parse_args(argv))


if __name__ == "__main__":
    sys.exit(main())
