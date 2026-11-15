#!/usr/bin/env python3
"""Unit tests for the dap-mcp CLI (stdlib unittest).

Never touches the real HOME: client configs live in temporary directories and
the stdio server is replaced by a stub, so the suite runs offline in seconds.

    python3 -m unittest tests.test_dap_cli -v
"""
from __future__ import annotations

import contextlib
import http.server
import importlib.util
import io
import json
import os
import pathlib
import re
import shutil
import socket
import tempfile
import threading
import time
import unittest
import urllib.request
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
CLI = ROOT / "adapters" / "mcp" / "dap_cli.py"
FIXTURES = ROOT / "tests" / "fixtures"


def load_cli():
    """Import a fresh dap_cli module (no state leaks between tests)."""
    spec = importlib.util.spec_from_file_location("dap_cli_under_test", CLI)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_cli(argv):
    cli = load_cli()
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = cli.main(argv)
    return code, out.getvalue(), err.getvalue()


def version_from_repo():
    return (ROOT / "VERSION").read_text(encoding="utf-8").strip()


def api_version_from_repo():
    text = (ROOT / "api" / "openapi.yaml").read_text(encoding="utf-8")
    match = re.search(r"^  version:\s*(\S+)", text, re.M)
    assert match, "api/openapi.yaml: info.version missing"
    return match.group(1).strip("\"'")


class _StubAdapter:
    def __init__(self, calls):
        self.calls = calls

    def main(self):
        self.calls.append("main")


class VersionTest(unittest.TestCase):
    def test_version_prints_three_versions(self):
        code, out, err = run_cli(["version"])
        self.assertEqual(code, 0, err)
        line = out.strip()
        self.assertRegex(line, r"^adapter=\S+ engine=\S+ api=\S+$")
        fields = dict(item.split("=", 1) for item in line.split())
        self.assertEqual(fields["adapter"], version_from_repo())
        self.assertEqual(fields["engine"], version_from_repo())
        self.assertEqual(fields["api"], api_version_from_repo())

    def test_version_falls_back_to_unknown(self):
        with tempfile.TemporaryDirectory(prefix="dap-cli-version-") as tmp:
            code, out, err = run_cli(["version", "--home", tmp])
        self.assertEqual(code, 0, err)
        self.assertEqual(out.strip(), "adapter=unknown engine=unknown api=unknown")


class HelpTest(unittest.TestCase):
    def test_help_exits_zero(self):
        code, out, err = run_cli(["--help"])
        self.assertEqual(code, 0, err)
        for command in ("run", "install", "doctor", "version"):
            self.assertIn(command, out)


class RunTest(unittest.TestCase):
    def test_run_is_default(self):
        cli = load_cli()
        calls = []
        with mock.patch.object(cli, "_load_adapter", lambda: _StubAdapter(calls)):
            code = cli.main([])
        self.assertEqual(code, 0)
        self.assertEqual(calls, ["main"])

    def test_run_subcommand_delegates(self):
        cli = load_cli()
        calls = []
        with mock.patch.object(cli, "_load_adapter", lambda: _StubAdapter(calls)):
            code = cli.main(["run"])
        self.assertEqual(code, 0)
        self.assertEqual(calls, ["main"])

    def test_home_sets_dap_home_before_loading_adapter(self):
        cli = load_cli()
        calls = []
        with tempfile.TemporaryDirectory(prefix="dap-cli-home-") as tmp:
            with mock.patch.dict(os.environ, {"DAP_HOME": "/nonexistent"}):
                with mock.patch.object(cli, "_load_adapter", lambda: _StubAdapter(calls)):
                    code = cli.main(["--home", tmp])
                self.assertEqual(code, 0)
                self.assertEqual(os.environ["DAP_HOME"], str(pathlib.Path(tmp).resolve()))
        self.assertEqual(calls, ["main"])




FAKE_ADAPTER = '''#!/usr/bin/env python3
import json
import sys

TOOLS = [
    {"name": "tool_%d" % index, "description": "fake", "inputSchema": {"type": "object"}}
    for index in range(COUNT)
]

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    message = json.loads(line)
    method = message.get("method")
    if method == "initialize":
        reply = {
            "jsonrpc": "2.0",
            "id": message.get("id"),
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "fake-dap", "version": "0"},
            },
        }
        print(json.dumps(reply), flush=True)
    elif method == "tools/list":
        reply = {"jsonrpc": "2.0", "id": message.get("id"), "result": {"tools": TOOLS}}
        print(json.dumps(reply), flush=True)
'''

SILENT_ADAPTER = '''#!/usr/bin/env python3
import sys
import time

for _line in sys.stdin:
    time.sleep(30)
'''


class _HealthHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, *args):
        pass


def start_fake_engine():
    server = http.server.HTTPServer(("127.0.0.1", 0), _HealthHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server




JAR = ROOT / "engine-native" / "target" / "dap-native.jar"
DEFAULT_JAVA_HOME = pathlib.Path.home() / "Projects" / "tools" / "jdk-21.0.13+9" / "Contents" / "Home"


def java_home():
    for candidate in (
        os.environ.get("JAVA_HOME"),
        os.environ.get("DAP_JDK"),
        str(DEFAULT_JAVA_HOME),
    ):
        if candidate and (pathlib.Path(candidate) / "bin" / "java").is_file():
            return str(candidate)
    return ""


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def engine_healthy(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/health", timeout=2) as response:
            return response.status == 200
    except OSError:
        return False


def shutdown_engine(port):
    try:
        request = urllib.request.Request(f"http://127.0.0.1:{port}/v1/shutdown", method="POST")
        urllib.request.urlopen(request, timeout=5).close()
    except OSError:
        pass


def wait_engine_stopped(port, timeout=15.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and engine_healthy(port):
        time.sleep(0.2)
    return not engine_healthy(port)

SIBLING_CONFIG = "mcp_servers:\n  other:\n    command: other\n"


class InstallHermesTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-cli-install-")
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)
        self.config = self.dir / "config.yaml"

    def install(self, *extra):
        return run_cli(["install", "--client", "hermes", "--config", str(self.config), *extra])

    def test_writes_entry(self):
        self.config.write_text(SIBLING_CONFIG, encoding="utf-8")
        code, out, err = self.install()
        self.assertEqual(code, 0, err)
        written = self.config.read_text(encoding="utf-8")
        self.assertIn("mcp_servers:", written)
        self.assertIn("  dap:", written)
        self.assertIn("  other:", written)
        self.assertIn(str(ROOT / "adapters" / "mcp" / "dap_mcp.py"), written)
        self.assertIn(f"DAP_HOME: {ROOT}", written)
        self.assertIn("resources: true", written)
        self.assertIn("prompts: true", written)
        self.assertIn("connect_timeout: 60", written)

    def test_dry_run_prints_and_keeps_file(self):
        self.config.write_text(SIBLING_CONFIG, encoding="utf-8")
        code, out, err = self.install("--dry-run")
        self.assertEqual(code, 0, err)
        self.assertIn("  dap:", out)
        self.assertEqual(self.config.read_text(encoding="utf-8"), SIBLING_CONFIG)

    def test_missing_config_exits_2_without_force(self):
        code, out, err = self.install()
        self.assertEqual(code, 2)
        self.assertIn("not found", err)
        self.assertFalse(self.config.exists())

    def test_force_creates_missing_config(self):
        code, out, err = self.install("--force")
        self.assertEqual(code, 0, err)
        self.assertIn("  dap:", self.config.read_text(encoding="utf-8"))

    def test_malformed_config_exits_2_and_prints_fragment(self):
        source = "mcp_servers: {dap: {command: python3}}\n"
        self.config.write_text(source, encoding="utf-8")
        code, out, err = self.install()
        self.assertEqual(code, 2)
        self.assertIn("flow", err)
        self.assertIn("mcp_servers", err)
        self.assertEqual(self.config.read_text(encoding="utf-8"), source)

    def test_read_only_config_exits_2_and_writes_nothing(self):
        self.config.write_text(SIBLING_CONFIG, encoding="utf-8")
        self.config.chmod(0o444)
        self.addCleanup(self.config.chmod, 0o644)
        code, out, err = self.install()
        self.assertEqual(code, 2)
        self.assertIn("not writable", err)
        self.assertEqual(self.config.read_text(encoding="utf-8"), SIBLING_CONFIG)

    def test_second_run_is_idempotent(self):
        self.config.write_text(SIBLING_CONFIG, encoding="utf-8")
        code, _, err = self.install()
        self.assertEqual(code, 0, err)
        first = self.config.read_text(encoding="utf-8")
        mtime = self.config.stat().st_mtime_ns
        code, _, err = self.install()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.config.read_text(encoding="utf-8"), first)
        self.assertEqual(self.config.stat().st_mtime_ns, mtime)
        self.assertEqual(first.count("  dap:"), 1)

    def test_home_with_client_configs_is_used_as_client_home(self):
        client_home = self.dir / "fakehome"
        (client_home / ".hermes").mkdir(parents=True)
        config = client_home / ".hermes" / "config.yaml"
        config.write_text(SIBLING_CONFIG, encoding="utf-8")
        with tempfile.TemporaryDirectory(prefix="dap-cli-otherhome-") as other:
            with mock.patch.dict(os.environ, {"HOME": other}):
                code, out, err = run_cli(
                    ["install", "--client", "hermes", "--home", str(client_home)]
                )
        self.assertEqual(code, 0, err)
        self.assertIn("  dap:", config.read_text(encoding="utf-8"))

    def test_unknown_client_is_rejected(self):
        code, out, err = run_cli(["install", "--client", "nope"])
        self.assertEqual(code, 2)
        self.assertIn("invalid choice", err)


class InstallDshTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-cli-dsh-")
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)
        self.path = self.dir / "cordis.patch.yml"
        self.fixture = (FIXTURES / "cordis.patch.yml").read_text(encoding="utf-8")

    def install(self, *extra):
        return run_cli(["install", "--client", "dsh", "--config", str(self.path), *extra])

    def test_writes_mcp_manager_entry(self):
        self.path.write_text(self.fixture, encoding="utf-8")
        code, out, err = self.install()
        self.assertEqual(code, 0, err)
        written = self.path.read_text(encoding="utf-8")
        self.assertIn("- id: mcp-manager", written)
        self.assertIn("      dap:", written)
        self.assertIn("        transport: stdio", written)
        self.assertIn("        startupTimeoutMs: 60000", written)
        self.assertIn("        toolCallTimeoutMs: 300000", written)
        self.assertIn("        reconnect:\n          enabled: true", written)
        self.assertIn(str(ROOT / "adapters" / "mcp" / "dap_mcp.py"), written)
        self.assertIn(f"DAP_HOME: {ROOT}", written)
        self.assertIn("- id: ui-chat", written)
        self.assertIn("# DSH profile patch", written)

    def test_dry_run_prints_and_keeps_file(self):
        self.path.write_text(self.fixture, encoding="utf-8")
        code, out, err = self.install("--dry-run")
        self.assertEqual(code, 0, err)
        self.assertIn("- id: mcp-manager", out)
        self.assertEqual(self.path.read_text(encoding="utf-8"), self.fixture)

    def test_malformed_patch_exits_2_and_prints_fragment(self):
        source = "config:\n  servers: {}\n"
        self.path.write_text(source, encoding="utf-8")
        code, out, err = self.install()
        self.assertEqual(code, 2)
        self.assertIn("YAML list", err)
        self.assertIn("config:", err)
        self.assertEqual(self.path.read_text(encoding="utf-8"), source)

    def test_second_run_is_idempotent(self):
        self.path.write_text(self.fixture, encoding="utf-8")
        code, _, err = self.install()
        self.assertEqual(code, 0, err)
        first = self.path.read_text(encoding="utf-8")
        mtime = self.path.stat().st_mtime_ns
        code, _, err = self.install()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.path.read_text(encoding="utf-8"), first)
        self.assertEqual(self.path.stat().st_mtime_ns, mtime)
        self.assertEqual(first.count("- id: mcp-manager"), 1)

class DoctorTest(unittest.TestCase):
    """doctor runs against a temp home: a stub HTTP server stands in for the engine."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-doctor-")
        self.addCleanup(self._tmp.cleanup)
        self.home = pathlib.Path(self._tmp.name)
        self.engine = start_fake_engine()
        self.addCleanup(self.engine.server_close)
        self.addCleanup(self.engine.shutdown)
        self.port = self.engine.server_address[1]
        patched = mock.patch.dict(
            os.environ,
            {
                "HOME": str(self.home),
                "DAP_HOME": str(self.home),
                "DAP_PORT": str(self.port),
            },
        )
        patched.start()
        self.addCleanup(patched.stop)

    def make_jar(self):
        jar = self.home / "engine-native" / "target" / "dap-native.jar"
        jar.parent.mkdir(parents=True, exist_ok=True)
        jar.write_bytes(b"")

    def make_adapter(self, count=30, body=None):
        path = self.home / "adapters" / "mcp" / "dap_mcp.py"
        path.parent.mkdir(parents=True, exist_ok=True)
        text = body if body is not None else FAKE_ADAPTER.replace("COUNT", str(count))
        path.write_text(text, encoding="utf-8")

    def make_config(self, text="mcp_servers:\n  dap:\n    command: python3\n"):
        path = self.home / ".hermes" / "config.yaml"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def doctor(self, *extra):
        return run_cli(["doctor", "--client", "hermes", *extra])

    def test_all_checks_pass(self):
        self.make_jar()
        self.make_adapter(30)
        self.make_config()
        code, out, err = self.doctor()
        self.assertEqual(code, 0, out + err)
        for name in ("python", "jar", "engine", "preflight", "config", "port"):
            self.assertRegex(out, rf"PASS  {name}\b")
        self.assertIn("tools/list returned 30 tools", out)

    def test_missing_jar_fails(self):
        self.make_adapter(30)
        self.make_config()
        code, out, err = self.doctor()
        self.assertEqual(code, 1)
        self.assertRegex(out, r"FAIL  jar\b")
        self.assertIn("make build-native", out)

    def test_config_without_dap_fails(self):
        self.make_jar()
        self.make_adapter(30)
        self.make_config(text=SIBLING_CONFIG)
        code, out, err = self.doctor()
        self.assertEqual(code, 1)
        self.assertRegex(out, r"FAIL  config\b")
        self.assertIn("no dap entry", out)

    def test_missing_config_fails(self):
        self.make_jar()
        self.make_adapter(30)
        code, out, err = self.doctor()
        self.assertEqual(code, 1)
        self.assertRegex(out, r"FAIL  config\b")

    def test_preflight_counts_tools(self):
        self.make_jar()
        self.make_adapter(30)
        cli = load_cli()
        self.assertEqual(cli.stdio_preflight(self.home), 30)

    def test_preflight_below_minimum_fails(self):
        self.make_jar()
        self.make_adapter(21)
        self.make_config()
        code, out, err = self.doctor()
        self.assertEqual(code, 1)
        self.assertRegex(out, r"FAIL  preflight\b")
        self.assertIn("21 tools", out)

    def test_silent_adapter_times_out(self):
        self.make_adapter(body=SILENT_ADAPTER)
        cli = load_cli()
        with self.assertRaises(TimeoutError):
            cli.stdio_preflight(self.home, timeout=0.5)

    def test_json_output(self):
        self.make_jar()
        self.make_adapter(30)
        self.make_config()
        code, out, err = self.doctor("--json")
        self.assertEqual(code, 0, err)
        checks = json.loads(out)
        self.assertEqual(
            [check["name"] for check in checks],
            ["python", "jar", "engine", "preflight", "config", "extensions", "port"],
        )
        self.assertTrue(all(check["ok"] for check in checks))

    def test_min_tools_matches_declared_tools(self):
        cli = load_cli()
        adapter = cli._load_adapter()
        self.assertEqual(cli.MIN_TOOLS, len(adapter.TOOLS))

@unittest.skipUnless(JAR.is_file(), "dap-native.jar not built")
@unittest.skipUnless(java_home(), "JDK 21 not found")
class DoctorStartsEngineTest(unittest.TestCase):
    """A fresh install: no engine running, doctor must start it through the adapter."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-doctor-engine-")
        self.addCleanup(self._tmp.cleanup)
        self.home = pathlib.Path(self._tmp.name)
        adapter_dir = self.home / "adapters" / "mcp"
        adapter_dir.mkdir(parents=True)
        for source in (ROOT / "adapters" / "mcp").glob("*.py"):
            shutil.copyfile(source, adapter_dir / source.name)
        jar = self.home / "engine-native" / "target" / "dap-native.jar"
        jar.parent.mkdir(parents=True)
        jar.symlink_to(JAR)
        config = self.home / ".hermes" / "config.yaml"
        config.parent.mkdir(parents=True)
        config.write_text("mcp_servers:\n  dap:\n    command: python3\n", encoding="utf-8")
        self.port = free_port()
        patched = mock.patch.dict(
            os.environ,
            {
                "HOME": str(self.home),
                "DAP_HOME": str(self.home),
                "DAP_PORT": str(self.port),
                "JAVA_HOME": java_home(),
            },
        )
        patched.start()
        self.addCleanup(wait_engine_stopped, self.port)
        self.addCleanup(shutdown_engine, self.port)
        self.addCleanup(patched.stop)

    def doctor(self):
        return run_cli(["doctor", "--client", "hermes", "--home", str(self.home)])

    def test_doctor_starts_engine_and_second_run_is_fast(self):
        self.assertFalse(engine_healthy(self.port))
        code, out, err = self.doctor()
        self.assertEqual(code, 0, out + err)
        self.assertIn("started by doctor", out)
        self.assertTrue(engine_healthy(self.port))
        started = time.monotonic()
        code, out, err = self.doctor()
        elapsed = time.monotonic() - started
        self.assertEqual(code, 0, out + err)
        self.assertIn("healthy at", out)
        self.assertLess(elapsed, 10.0)



class ExtensionsCheckTest(unittest.TestCase):
    def test_missing_extensions_are_optional(self):
        cli = load_cli()
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"DAP_DUCKDB_EXTENSION_DIR": ""}):
                check = cli._check_extensions(pathlib.Path(tmp))
            self.assertTrue(check.ok)
            self.assertIn("optional", check.detail)
            self.assertIn("make extensions", check.detail)

    def test_installed_extensions_are_reported(self):
        cli = load_cli()
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "v1.5.6" / "osx_arm64"
            root.mkdir(parents=True)
            for name in cli.EXTENSIONS:
                (root / (name + ".duckdb_extension")).write_bytes(b"x")
            with mock.patch.dict(os.environ, {"DAP_DUCKDB_EXTENSION_DIR": tmp}):
                check = cli._check_extensions(pathlib.Path(tmp) / "home")
            self.assertTrue(check.ok)
            self.assertIn("installed", check.detail)
            for name in cli.EXTENSIONS:
                self.assertIn(name, check.detail)
