#!/usr/bin/env python3
"""Unit tests for the dap-mcp CLI (stdlib unittest).

Never touches the real HOME: client configs live in temporary directories and
the stdio server is replaced by a stub, so the suite runs offline in seconds.

    python3 -m unittest tests.test_dap_cli -v
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import pathlib
import re
import tempfile
import unittest
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
