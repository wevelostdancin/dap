#!/usr/bin/env python3
"""Unit tests for the line-based MCP config editor (stdlib unittest).

PyYAML is not a dependency, so the editor is tested against exact block-style
YAML text. Every test works in a temporary directory: the real client configs
are never touched.

    python3 -m unittest tests.test_mcp_config -v
"""
from __future__ import annotations

import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "adapters" / "mcp" / "mcp_config.py"
FIXTURES = ROOT / "tests" / "fixtures"


def load_config_module():
    spec = importlib.util.spec_from_file_location("dap_mcp_config_under_test", CONFIG)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


MC = load_config_module()

HERMES_ENTRY = {
    "command": "/usr/bin/python3",
    "args": ["/abs/adapters/mcp/dap_mcp.py"],
    "env": {"DAP_HOME": "/abs/dap", "DAP_PORT": "7800"},
    "cwd": "/abs/dap",
    "enabled": True,
    "timeout": 300,
    "connect_timeout": 60,
    "tools": {"resources": True, "prompts": True},
}

SIBLING_CONFIG = "mcp_servers:\n  other:\n    command: other\n"


class UpsertMappingBlockTest(unittest.TestCase):
    def test_empty_file_creates_nested_mapping(self):
        text = MC.upsert_mapping_block("", ["mcp_servers", "dap"], {"command": "python3"})
        self.assertEqual(text, "mcp_servers:\n  dap:\n    command: python3\n")

    def test_existing_sibling_is_preserved(self):
        text = MC.upsert_mapping_block(SIBLING_CONFIG, ["mcp_servers", "dap"], {"command": "python3"})
        self.assertEqual(
            text,
            "mcp_servers:\n  other:\n    command: other\n  dap:\n    command: python3\n",
        )

    def test_existing_entry_is_replaced(self):
        source = "mcp_servers:\n  dap:\n    command: old\n    timeout: 1\n"
        text = MC.upsert_mapping_block(source, ["mcp_servers", "dap"], {"command": "python3"})
        self.assertEqual(text, "mcp_servers:\n  dap:\n    command: python3\n")

    def test_upsert_is_idempotent(self):
        first = MC.upsert_mapping_block(SIBLING_CONFIG, ["mcp_servers", "dap"], {"command": "python3"})
        second = MC.upsert_mapping_block(first, ["mcp_servers", "dap"], {"command": "python3"})
        self.assertEqual(first, second)

    def test_comments_outside_the_block_are_preserved(self):
        source = (
            "# clients\n"
            "mcp_servers:\n"
            "  # keep me\n"
            "  other:\n"
            "    command: other\n"
            "\n"
            "# tail\n"
        )
        text = MC.upsert_mapping_block(source, ["mcp_servers", "dap"], {"command": "python3"})
        self.assertEqual(
            text,
            "# clients\n"
            "mcp_servers:\n"
            "  # keep me\n"
            "  other:\n"
            "    command: other\n"
            "  dap:\n"
            "    command: python3\n"
            "\n"
            "# tail\n",
        )

    def test_scalars_and_sequences_are_rendered_safely(self):
        block = {
            "command": "/usr/bin/python3",
            "args": ["/abs/dap_mcp.py", "/abs/second"],
            "env": {"DAP_HEAP": "2g", "DAP_PORT": "7800"},
            "enabled": True,
            "timeout": 300,
        }
        text = MC.upsert_mapping_block("", ["mcp_servers", "dap"], block)
        self.assertEqual(
            text,
            "mcp_servers:\n"
            "  dap:\n"
            "    command: /usr/bin/python3\n"
            "    args:\n"
            "      - /abs/dap_mcp.py\n"
            "      - /abs/second\n"
            "    env:\n"
            "      DAP_HEAP: 2g\n"
            '      DAP_PORT: "7800"\n'
            "    enabled: true\n"
            "    timeout: 300\n",
        )

    def test_flow_style_mapping_raises(self):
        source = "mcp_servers: {dap: {command: python3}}\n"
        with self.assertRaises(MC.ConfigError):
            MC.upsert_mapping_block(source, ["mcp_servers", "dap"], {"command": "python3"})

    def test_flow_style_document_raises(self):
        with self.assertRaises(MC.ConfigError):
            MC.upsert_mapping_block("{mcp_servers: {}}\n", ["mcp_servers", "dap"], {"command": "x"})

    def test_anchor_raises(self):
        source = "mcp_servers: &base\n  dap:\n    command: old\n"
        with self.assertRaises(MC.ConfigError):
            MC.upsert_mapping_block(source, ["mcp_servers", "dap"], {"command": "python3"})

    def test_existing_scalar_instead_of_mapping_raises(self):
        source = "mcp_servers:\n  dap: python3\n"
        with self.assertRaises(MC.ConfigError):
            MC.upsert_mapping_block(source, ["mcp_servers", "dap"], {"command": "python3"})

    def test_empty_path_is_rejected(self):
        with self.assertRaises(ValueError):
            MC.upsert_mapping_block("", [], {"command": "python3"})


class WriteHermesTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-hermes-")
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)
        self.path = self.dir / "config.yaml"

    def test_writes_entry_and_backup(self):
        self.path.write_text(SIBLING_CONFIG, encoding="utf-8")
        text = MC.write_hermes(self.path, HERMES_ENTRY)
        written = self.path.read_text(encoding="utf-8")
        self.assertEqual(written, text)
        self.assertIn("mcp_servers:\n  other:", written)
        self.assertIn("  dap:", written)
        self.assertIn("/abs/adapters/mcp/dap_mcp.py", written)
        self.assertIn("resources: true", written)
        backups = sorted(self.dir.glob("config.yaml.bak-*"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(encoding="utf-8"), SIBLING_CONFIG)

    def test_second_run_is_idempotent_and_keeps_mtime(self):
        self.path.write_text(SIBLING_CONFIG, encoding="utf-8")
        MC.write_hermes(self.path, HERMES_ENTRY)
        before = self.path.stat().st_mtime_ns
        MC.write_hermes(self.path, HERMES_ENTRY)
        self.assertEqual(self.path.stat().st_mtime_ns, before)
        self.assertEqual(len(list(self.dir.glob("config.yaml.bak-*"))), 1)
        self.assertEqual(self.path.read_text(encoding="utf-8").count("  dap:"), 1)

    def test_dry_run_does_not_write(self):
        self.path.write_text(SIBLING_CONFIG, encoding="utf-8")
        text = MC.write_hermes(self.path, HERMES_ENTRY, dry_run=True)
        self.assertIn("  dap:", text)
        self.assertEqual(self.path.read_text(encoding="utf-8"), SIBLING_CONFIG)
        self.assertEqual(list(self.dir.glob("config.yaml.bak-*")), [])

    def test_missing_file_is_created_without_backup(self):
        nested = self.dir / "sub" / "config.yaml"
        text = MC.write_hermes(nested, HERMES_ENTRY)
        self.assertTrue(nested.is_file())
        self.assertEqual(nested.read_text(encoding="utf-8"), text)
        self.assertEqual(list(nested.parent.glob("config.yaml.bak-*")), [])

    def test_read_only_config_raises_and_writes_nothing(self):
        self.path.write_text(SIBLING_CONFIG, encoding="utf-8")
        self.path.chmod(0o444)
        self.addCleanup(self.path.chmod, 0o644)
        with self.assertRaises(MC.ConfigError):
            MC.write_hermes(self.path, HERMES_ENTRY)
        self.assertEqual(self.path.read_text(encoding="utf-8"), SIBLING_CONFIG)
        self.assertEqual(list(self.dir.glob("config.yaml.bak-*")), [])

DSH_ENTRY = {
    "transport": "stdio",
    "command": "/usr/bin/python3",
    "args": ["/abs/adapters/mcp/dap_mcp.py"],
    "env": {"DAP_HOME": "/abs/dap"},
    "cwd": "/abs/dap",
    "enabled": True,
    "startupTimeoutMs": 60000,
    "toolCallTimeoutMs": 300000,
    "reconnect": True,
}


class WriteDshTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="dap-dsh-")
        self.addCleanup(self._tmp.cleanup)
        self.dir = pathlib.Path(self._tmp.name)
        self.path = self.dir / "cordis.patch.yml"
        self.fixture = (FIXTURES / "cordis.patch.yml").read_text(encoding="utf-8")

    def test_appends_entry_to_fixture(self):
        self.path.write_text(self.fixture, encoding="utf-8")
        text = MC.write_dsh(self.path, DSH_ENTRY)
        written = self.path.read_text(encoding="utf-8")
        self.assertEqual(written, text)
        self.assertIn("- id: mcp-manager", written)
        self.assertIn("      dap:", written)
        self.assertIn("        transport: stdio", written)
        self.assertIn("        reconnect: true", written)
        self.assertIn("- id: ui-chat", written)
        self.assertIn("# DSH profile patch", written)
        backups = sorted(self.dir.glob("cordis.patch.yml.bak-*"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(encoding="utf-8"), self.fixture)

    def test_existing_entry_merges_only_servers_dap(self):
        source = (
            "# profile patch\n"
            "- id: mcp-manager\n"
            "  priority: 10\n"
            "  config:\n"
            "    servers:\n"
            "      other:\n"
            "        transport: stdio\n"
        )
        self.path.write_text(source, encoding="utf-8")
        MC.write_dsh(self.path, DSH_ENTRY)
        written = self.path.read_text(encoding="utf-8")
        self.assertIn("  priority: 10", written)
        self.assertIn("      other:", written)
        self.assertIn("      dap:", written)
        self.assertIn("        command: /usr/bin/python3", written)
        self.assertEqual(written.count("- id: mcp-manager"), 1)

    def test_servers_missing_is_created(self):
        source = "- id: mcp-manager\n  config:\n    enabled: true\n"
        self.path.write_text(source, encoding="utf-8")
        MC.write_dsh(self.path, DSH_ENTRY)
        written = self.path.read_text(encoding="utf-8")
        self.assertIn("    enabled: true", written)
        self.assertIn("    servers:", written)
        self.assertIn("      dap:", written)

    def test_existing_dap_entry_is_replaced(self):
        source = (
            "- id: mcp-manager\n"
            "  config:\n"
            "    servers:\n"
            "      dap:\n"
            "        transport: stdio\n"
            "        command: old\n"
            "        reconnect: false\n"
        )
        self.path.write_text(source, encoding="utf-8")
        MC.write_dsh(self.path, DSH_ENTRY)
        written = self.path.read_text(encoding="utf-8")
        self.assertNotIn("command: old", written)
        self.assertNotIn("reconnect: false", written)
        self.assertIn("        command: /usr/bin/python3", written)
        self.assertEqual(written.count("      dap:"), 1)

    def test_second_run_is_idempotent(self):
        self.path.write_text(self.fixture, encoding="utf-8")
        MC.write_dsh(self.path, DSH_ENTRY)
        first = self.path.read_text(encoding="utf-8")
        mtime = self.path.stat().st_mtime_ns
        MC.write_dsh(self.path, DSH_ENTRY)
        self.assertEqual(self.path.read_text(encoding="utf-8"), first)
        self.assertEqual(self.path.stat().st_mtime_ns, mtime)
        self.assertEqual(first.count("- id: mcp-manager"), 1)

    def test_dry_run_does_not_write(self):
        self.path.write_text(self.fixture, encoding="utf-8")
        text = MC.write_dsh(self.path, DSH_ENTRY, dry_run=True)
        self.assertIn("- id: mcp-manager", text)
        self.assertEqual(self.path.read_text(encoding="utf-8"), self.fixture)
        self.assertEqual(list(self.dir.glob("*.bak-*")), [])

    def test_empty_patch_creates_entry(self):
        text = MC.write_dsh(self.path, DSH_ENTRY)
        self.assertTrue(text.startswith("- id: mcp-manager\n"))
        self.assertTrue(self.path.is_file())

    def test_non_list_document_raises(self):
        self.path.write_text("config:\n  servers: {}\n", encoding="utf-8")
        with self.assertRaises(MC.ConfigError):
            MC.write_dsh(self.path, DSH_ENTRY)

    def test_flow_style_list_raises(self):
        self.path.write_text("[{id: mcp-manager}]\n", encoding="utf-8")
        with self.assertRaises(MC.ConfigError):
            MC.write_dsh(self.path, DSH_ENTRY)

    def test_flow_style_servers_raises(self):
        source = "- id: mcp-manager\n  config:\n    servers: {dap: {transport: stdio}}\n"
        self.path.write_text(source, encoding="utf-8")
        with self.assertRaises(MC.ConfigError):
            MC.write_dsh(self.path, DSH_ENTRY)

