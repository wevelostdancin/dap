#!/usr/bin/env python3
"""Static checks for the dap-dsh DSH bundle (stdlib unittest).

Run from the repository root:

    python3 -m unittest tests.test_dsh_plugin -v
"""
from __future__ import annotations

import json
import pathlib
import re
import shutil
import subprocess
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLUGIN = ROOT / "dsh-plugin"
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


class PackageManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.data = json.loads(read(PLUGIN / "package.json"))

    def test_identity(self) -> None:
        self.assertEqual(self.data["name"], "dap-dsh")
        self.assertEqual(self.data["version"], "1.3.0")
        self.assertTrue(self.data["description"].strip())
        self.assertEqual(self.data["license"], "Apache-2.0")
        self.assertEqual(self.data["type"], "module")
        self.assertEqual(self.data["main"], "./index.js")
        self.assertEqual(self.data["exports"], {".": "./index.js"})

    def test_files_and_engines(self) -> None:
        self.assertEqual(
            self.data["files"],
            ["index.js", "cordis.patch.yml", "bin", "README.md", "LICENSE"],
        )
        self.assertEqual(self.data["engines"]["node"], ">=24.9.0")
        self.assertEqual(self.data["engines"]["dsh"], ">=0.2.2-0 <0.3.0")
        self.assertEqual(
            self.data["peerDependencies"],
            {"@deepseek-ai/dsh": ">=0.2.2-0 <0.3.0"},
        )
        self.assertTrue(self.data["peerDependenciesMeta"]["@deepseek-ai/dsh"]["optional"])

    def test_dsh_manifest(self) -> None:
        dsh = self.data["dsh"]
        self.assertEqual(dsh["manifestVersion"], 1)
        self.assertEqual(dsh["kind"], "plugin")
        self.assertEqual(dsh["bundle"], {"patch": "./cordis.patch.yml"})
        self.assertEqual(dsh["capabilities"], [])
        self.assertEqual(dsh["labels"], [])


class BundleFilesTest(unittest.TestCase):
    def test_entry_has_no_module_side_effects(self) -> None:
        text = read(PLUGIN / "index.js")
        self.assertIn("export const name = 'dap-dsh'", text)
        self.assertIn("export const inject = []", text)
        self.assertIn("export function apply() {}", text)
        self.assertNotIn("import ", text)
        self.assertNotIn("require(", text)

    def test_license_matches_root(self) -> None:
        self.assertEqual(read(PLUGIN / "LICENSE"), read(ROOT / "LICENSE"))

    def test_launcher(self) -> None:
        launcher = PLUGIN / "bin" / "launch.mjs"
        self.assertTrue(launcher.is_file())
        text = read(launcher)
        for token in ("mcp", ".dap", "SIGINT", "SIGTERM", "stdio: 'inherit'",
                      "process.exit(2)", "install.sh"):
            self.assertIn(token, text)
        node = shutil.which("node")
        if node is None:
            self.skipTest("node not available")
        proc = subprocess.run([node, "--check", str(launcher)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)


class PatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.text = read(PLUGIN / "cordis.patch.yml")

    def test_insert_row_precedes_mcp_manager(self) -> None:
        pattern = r"^-\s+insert:\s+\[\{id:\s*dap-dsh,\s*name:\s*dap-dsh\}\]$"
        self.assertIsNotNone(re.search(pattern, self.text, re.M))
        self.assertIsNotNone(re.search(r"^-\s+id:\s*mcp-manager$", self.text, re.M))
        self.assertLess(self.text.index("- insert:"), self.text.index("- id: mcp-manager"))

    def test_mcp_server_fields_in_text(self) -> None:
        for token in ("servers:", "dap:", "transport: stdio", "args: ['mcp']",
                      "startupTimeoutMs: 60000", "toolCallTimeoutMs: 300000",
                      "enabled: true", "reconnect: {enabled: true}"):
            self.assertIn(token, self.text)
        self.assertEqual(self.text.count("!!js"), 2)
        self.assertIn("!!js process.env.HOME + '/.dap/bin/dap'", self.text)
        self.assertIn("!!js process.env.HOME + '/.dap'", self.text)

    def test_yaml_structure_when_pyyaml_available(self) -> None:
        try:
            import yaml  # type: ignore
        except ImportError:
            self.skipTest("PyYAML not installed; text checks above cover the patch")
        # yaml.safe_load rejects the DSH-specific !!js tag; strip it only after
        # the text assertions above have verified that the tag is present.
        data = yaml.safe_load(self.text.replace("!!js ", ""))
        self.assertIsInstance(data, list)
        insert_rows = [row for row in data if isinstance(row, dict) and "insert" in row]
        self.assertEqual(insert_rows[0]["insert"], [{"id": "dap-dsh", "name": "dap-dsh"}])
        manager = next(row for row in data if isinstance(row, dict) and row.get("id") == "mcp-manager")
        server = manager["config"]["servers"]["dap"]
        self.assertEqual(server["transport"], "stdio")
        self.assertIn(".dap/bin/dap", server["command"])
        self.assertEqual(server["args"], ["mcp"])
        self.assertIn(".dap", server["env"]["DAP_HOME"])
        self.assertTrue(server["enabled"])
        self.assertEqual(server["startupTimeoutMs"], 60000)
        self.assertEqual(server["toolCallTimeoutMs"], 300000)
        self.assertTrue(server["reconnect"]["enabled"])


class DocsTest(unittest.TestCase):
    def test_readme_documents_install_and_license(self) -> None:
        text = read(PLUGIN / "README.md")
        self.assertIn("install.sh", text)
        self.assertIn("Settings -> Plugins", text)
        self.assertIn("npm publish", text)
        self.assertIn("dsh plugin --profile web add dap-dsh", text)
        self.assertIn("Apache-2.0", text)

    def test_dsh_plugin_doc(self) -> None:
        text = read(ROOT / "docs" / "DSH-PLUGIN.md")
        for token in ("install.sh", "Settings -> Plugins", "npm publish",
                      "dsh plugin --profile web add dap-dsh", "mcp__dap__*", "Apache-2.0"):
            self.assertIn(token, text)

    def test_no_cjk_in_docs_and_readme(self) -> None:
        targets = [ROOT / "README.md", PLUGIN / "README.md", ROOT / "docs" / "DSH-PLUGIN.md"]
        targets.extend(sorted((ROOT / "docs").rglob("*.md")))
        for path in dict.fromkeys(targets):
            with self.subTest(path=str(path.relative_to(ROOT))):
                self.assertIsNone(CJK.search(read(path)))


if __name__ == "__main__":
    unittest.main()
