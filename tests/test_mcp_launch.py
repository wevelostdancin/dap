#!/usr/bin/env python3
"""Unit tests for the MCP adapter's native-launch lifecycle (stdlib unittest).

The tests never start Java or KNIME: Popen/urlopen are monkeypatched, except for
one real short-lived Python child that stands in for a java process which exits
immediately. The whole module runs offline in well under five seconds.

    python3 -m unittest tests.test_mcp_launch -v
    python3 tests/test_mcp_launch.py -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"


def load_adapter(**environment):
    """Import a fresh adapter module with exactly *environment* (plus PATH/HOME)."""
    env = {"PATH": os.environ.get("PATH", ""), "HOME": os.environ.get("HOME", "")}
    env.update(environment)
    saved = dict(os.environ)
    try:
        os.environ.clear()
        os.environ.update(env)
        spec = importlib.util.spec_from_file_location("dap_mcp_under_test", ADAPTER)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        os.environ.clear()
        os.environ.update(saved)


def make_home(directory, jar=True):
    home = pathlib.Path(directory)
    if jar:
        jar_path = home / "engine-native" / "target" / "dap-native.jar"
        jar_path.parent.mkdir(parents=True, exist_ok=True)
        jar_path.write_bytes(b"")
    return home


class FakeChild:
    """Minimal Popen double for launch/stop paths that must not spawn anything."""

    def __init__(self, code=None, pid=424242):
        self.pid = pid
        self.returncode = code
        self.terminated = False
        self.killed = False
        self.waited = False

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated = True
        self.returncode = -15

    def kill(self):
        self.killed = True
        self.returncode = -9

    def wait(self, timeout=None):
        self.waited = True
        return self.returncode


class NativeLaunchTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-mcp-launch-")
        self.home = pathlib.Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_missing_jar_reports_build_hint(self):
        module = load_adapter(DAP_HOME=str(self.home))
        self.assertFalse(pathlib.Path(module.NATIVE_JAR).exists())
        with mock.patch.object(module, "probe", return_value=None), \
                mock.patch.object(module, "_port_busy", return_value=False):
            with self.assertRaises(module.DapError) as caught:
                module.ensure_service()
        error = caught.exception
        self.assertEqual(error.code, -32001)
        self.assertIn("не найден", str(error))
        self.assertIn("mvnw -DskipTests package", str(error))
        self.assertEqual(error.data["jar"], module.NATIVE_JAR)

    def test_missing_jar_is_jsonrpc_error_with_build_hint(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        env = {key: value for key, value in os.environ.items()
               if not key.startswith("DAP_")}
        env.update({
            "DAP_HOME": str(self.home),
            "DAP_PORT": str(port),
            "DAP_SESSION": "mcp-launch-test",
        })
        request = "\n".join([
            json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                        "params": {"protocolVersion": "2024-11-05"}}),
            json.dumps({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                        "params": {"name": "dap_search_nodes", "arguments": {}}}),
        ]) + "\n"
        process = subprocess.Popen(
            [sys.executable, str(ADAPTER)],
            cwd=str(ROOT), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, env=env,
        )
        try:
            stdout, _stderr = process.communicate(request, timeout=5)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
        responses = [json.loads(line) for line in stdout.splitlines() if line.strip()]
        error = next(item["error"] for item in responses if item.get("id") == 2)
        self.assertEqual(error["code"], -32001)
        self.assertIn("соберите jar", error["message"])
        self.assertIn("mvnw -DskipTests package", error["message"])

    def test_launch_native_returns_popen_with_heap(self):
        module = load_adapter(DAP_HOME=str(self.home), DAP_HEAP="3g")
        child = FakeChild()
        log = io.BytesIO()
        with mock.patch.object(module.subprocess, "Popen", return_value=child) as popen:
            returned = module._launch_native({"DAP_PORT": "7999"}, log)
        self.assertIs(returned, child)
        command, kwargs = popen.call_args
        self.assertEqual(command[0], [module._java(), "-Xmx3g", "-jar", module.NATIVE_JAR])
        self.assertEqual(kwargs["stdout"], log)
        self.assertEqual(kwargs["stderr"], log)
        self.assertEqual(kwargs["cwd"], module.DAP_HOME)
        self.assertEqual(kwargs["env"], {"DAP_PORT": "7999"})
        self.assertTrue(kwargs["start_new_session"])
        self.assertEqual(kwargs["stdin"], subprocess.DEVNULL)

    def test_immediate_child_exit_fails_fast_and_is_reaped(self):
        module = load_adapter(DAP_HOME=str(self.home))
        make_home(self.home)
        self.assertTrue(pathlib.Path(module.NATIVE_JAR).is_file())
        real_popen = subprocess.Popen
        started = []

        def spawn(*args, **kwargs):
            process = real_popen(*args, **kwargs)
            started.append(process)
            return process

        started_at = time.monotonic()
        with contextlib.redirect_stderr(io.StringIO()), \
                mock.patch.object(module, "probe", return_value=None), \
                mock.patch.object(module, "_port_busy", return_value=False), \
                mock.patch.object(module, "_java", return_value=sys.executable), \
                mock.patch.object(module.time, "sleep", return_value=None), \
                mock.patch.object(module, "START_WAIT", 3.0), \
                mock.patch.object(module.subprocess, "Popen", side_effect=spawn):
            with self.assertRaises(module.DapError) as caught:
                module.ensure_service()
        elapsed = time.monotonic() - started_at
        message = str(caught.exception)
        self.assertEqual(caught.exception.code, -32001)
        self.assertIn("завершился при запуске", message)
        self.assertIn("код выхода 2", message)
        self.assertIn("Unknown option", message)
        self.assertEqual(caught.exception.data["exitCode"], 2)
        self.assertTrue(caught.exception.data["tail"])
        self.assertLess(elapsed, 5.0)
        self.assertEqual(len(started), 1)
        self.assertIsNotNone(started[0].poll(), "exited child was not reaped")

    def test_timeout_terminates_child_and_reports_log_tail(self):
        module = load_adapter(DAP_HOME=str(self.home))
        make_home(self.home)
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        try:
            with contextlib.redirect_stderr(io.StringIO()), \
                    mock.patch.object(module, "probe", return_value=None), \
                    mock.patch.object(module, "_port_busy", return_value=False), \
                    mock.patch.object(module, "_launch_native", return_value=child), \
                    mock.patch.object(module.time, "sleep", return_value=None), \
                    mock.patch.object(module, "START_WAIT", 0.05):
                with self.assertRaises(module.DapError) as caught:
                    module.ensure_service()
            self.assertIn("не поднялся", str(caught.exception))
            self.assertEqual(caught.exception.data["log"], module.LOG)
            self.assertIsNotNone(child.poll(), "timed-out child is still running")
        finally:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=5)

    def test_native_launch_uses_jar_and_heap(self):
        module = load_adapter(DAP_HOME=str(self.home))
        make_home(self.home)
        child = FakeChild()
        with contextlib.redirect_stderr(io.StringIO()), \
                mock.patch.object(module, "probe", return_value=None), \
                mock.patch.object(module, "_port_busy", return_value=False), \
                mock.patch.object(module, "START_WAIT", 0), \
                mock.patch.object(module, "_launch_native", return_value=child) as launch:
            with self.assertRaises(module.DapError):
                module.ensure_service()
        launch.assert_called_once()
        self.assertEqual(launch.call_args.args[1].name, module.LOG)



if __name__ == "__main__":
    unittest.main(verbosity=2)
