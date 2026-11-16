#!/usr/bin/env python3
"""Tests for the engine build guard in the MCP adapter (stdlib unittest).

The adapter must never serve an agent a catalog of a previous build: when the
engine on the port answers with another version than DAP_HOME/VERSION, it is
stopped and the current jar is started instead.

    python3 -m unittest tests.test_mcp_engine_version -v
"""
from __future__ import annotations

import importlib.util
import os
import pathlib
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADAPTER = ROOT / "adapters" / "mcp" / "dap_mcp.py"


class FakeChild:
    """Stand-in for subprocess.Popen of the engine process."""

    pid = 4242

    def poll(self):
        return None


def load_adapter(home: pathlib.Path):
    """Import dap_mcp.py with DAP_HOME pointed at a throwaway directory."""
    os.environ["DAP_HOME"] = str(home)
    spec = importlib.util.spec_from_file_location("dap_mcp_under_test", ADAPTER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class EngineBuildGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.home = pathlib.Path(self.tmp.name)
        (self.home / "VERSION").write_text("1.2.0\n", encoding="utf-8")
        jar = self.home / "engine-native" / "target"
        jar.mkdir(parents=True)
        (jar / "dap-native.jar").write_bytes(b"jar")
        self.mod = load_adapter(self.home)

    def tearDown(self):
        os.environ.pop("DAP_ALLOW_STALE_ENGINE", None)
        self.tmp.cleanup()

    # ------------------------------------------------------------ pure helpers

    def test_expected_version_is_read_from_disk(self):
        self.assertEqual(self.mod.expected_version(), "1.2.0")

    def test_expected_version_is_none_without_file(self):
        (self.home / "VERSION").unlink()
        self.assertIsNone(self.mod.expected_version())

    def test_same_version_is_not_stale(self):
        self.assertIsNone(self.mod._stale_reason({"version": "1.2.0"}))

    def test_other_version_is_stale(self):
        reason = self.mod._stale_reason({"version": "1.1.0"})
        self.assertIn("1.1.0", reason)
        self.assertIn("1.2.0", reason)

    def test_missing_version_cannot_be_judged(self):
        self.assertIsNone(self.mod._stale_reason({}))
        self.assertIsNone(self.mod._stale_reason({"version": ""}))

    def test_allow_stale_switch(self):
        os.environ["DAP_ALLOW_STALE_ENGINE"] = "1"
        self.assertTrue(self.mod._allow_stale_engine())
        os.environ["DAP_ALLOW_STALE_ENGINE"] = "no"
        self.assertFalse(self.mod._allow_stale_engine())

    # --------------------------------------------------------------- lifecycle

    def _run_ensure(self, bodies):
        """Run ensure_service with probe() answering bodies in order, then None."""
        calls = []
        queue = list(bodies)

        def fake_probe():
            return queue.pop(0) if queue else None

        with mock.patch.object(self.mod, "probe", side_effect=fake_probe), \
             mock.patch.object(self.mod, "health", return_value=True), \
             mock.patch.object(self.mod, "_wait_for_warmup"), \
             mock.patch.object(self.mod, "_launch_native",
                               return_value=FakeChild()) as launch, \
             mock.patch.object(self.mod, "call",
                               side_effect=lambda m, p, **kw: calls.append((m, p))):
            self.mod.ensure_service()
        return calls, launch

    def test_stale_engine_is_replaced(self):
        calls, launch = self._run_ensure([{"version": "1.1.0", "generation": "old"}])
        self.assertIn(("POST", "/v1/shutdown"), calls)
        self.assertTrue(launch.called)

    def test_current_engine_is_attached_without_restart(self):
        calls, launch = self._run_ensure([{"version": "1.2.0", "generation": "now"}])
        self.assertEqual(calls, [])
        self.assertFalse(launch.called)

    def test_stale_engine_is_kept_when_explicitly_allowed(self):
        os.environ["DAP_ALLOW_STALE_ENGINE"] = "1"
        calls, launch = self._run_ensure([{"version": "1.1.0", "generation": "old"}])
        self.assertEqual(calls, [])
        self.assertFalse(launch.called)

    def test_engine_newer_than_version_file_is_kept(self):
        """A VERSION file ahead of the jar must not trigger a restart loop."""
        (self.home / "VERSION").write_text("1.1.0\n", encoding="utf-8")
        self.assertIsNone(self.mod._stale_reason({"version": "1.2.0"}))

    def test_stale_engine_is_replaced_only_once(self):
        calls = []
        bodies = [{"version": "1.1.0"}, None, {"version": "1.1.0"}]

        def fake_probe():
            return bodies.pop(0) if bodies else None

        with mock.patch.object(self.mod, "probe", side_effect=fake_probe), \
             mock.patch.object(self.mod, "health", return_value=True), \
             mock.patch.object(self.mod, "_wait_for_warmup"), \
             mock.patch.object(self.mod, "_launch_native", return_value=FakeChild()), \
             mock.patch.object(self.mod, "call",
                               side_effect=lambda m, p, **kw: calls.append((m, p))):
            self.mod.ensure_service()
            self.mod.ensure_service()
        self.assertEqual(calls.count(("POST", "/v1/shutdown")), 1)

    # ---------------------------------------------------------------- catalog

    def test_search_nodes_asks_for_the_whole_catalog(self):
        seen = []

        def fake_call(method, path, **kwargs):
            seen.append(path)
            return {"nodes": []}

        with mock.patch.object(self.mod, "call", side_effect=fake_call):
            self.mod.tool_search_nodes({})
        self.assertIn("limit=200", seen[0])


if __name__ == "__main__":
    unittest.main()
