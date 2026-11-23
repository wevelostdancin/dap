#!/usr/bin/env python3
"""Unit tests for the pure helpers of scripts/package.py (no jlink, no network)."""
from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent


def load_package():
    path = ROOT / "scripts" / "package.py"
    spec = importlib.util.spec_from_file_location("dap_package", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


package = load_package()


class PlatformTagTest(unittest.TestCase):
    def test_macos_arm64(self):
        self.assertEqual(package.platform_tag("Darwin", "arm64"), "macos-arm64")

    def test_linux_x64(self):
        self.assertEqual(package.platform_tag("Linux", "x86_64"), "linux-x64")
        self.assertEqual(package.platform_tag("Linux", "amd64"), "linux-x64")

    def test_unknown_platform_rejected(self):
        with self.assertRaises(ValueError):
            package.platform_tag("Windows", "x86_64")
        with self.assertRaises(ValueError):
            package.platform_tag("Linux", "i386")


class ArchiveNameTest(unittest.TestCase):
    def test_archive_name(self):
        name = package.archive_name("1.3.0", "Darwin", "arm64")
        self.assertEqual(name, "dap-1.3.0-macos-arm64.tar.gz")
        self.assertEqual(
            package.archive_name("1.2.0", "Linux", "aarch64"),
            "dap-1.2.0-linux-arm64.tar.gz",
        )


class ChecksumLinesTest(unittest.TestCase):
    def test_hashes_every_regular_file_in_stable_order(self):
        with tempfile.TemporaryDirectory(prefix="dap-package-test-") as directory:
            root = pathlib.Path(directory)
            (root / "sub").mkdir()
            (root / "a.txt").write_bytes(b"a")
            (root / "sub" / "b.txt").write_bytes(b"bb")
            lines = package.checksum_lines(root)
            self.assertEqual(lines, [
                hashlib.sha256(b"a").hexdigest() + "  a.txt",
                hashlib.sha256(b"bb").hexdigest() + "  sub/b.txt",
            ])

    def test_skips_existing_checksum_file(self):
        with tempfile.TemporaryDirectory(prefix="dap-package-test-") as directory:
            root = pathlib.Path(directory)
            (root / "VERSION").write_text("1.3.0", encoding="utf-8")
            (root / "SHA256SUMS").write_text("stale", encoding="utf-8")
            lines = package.checksum_lines(root)
            self.assertEqual(len(lines), 1)
            self.assertTrue(lines[0].endswith("  VERSION"))

    def test_skips_symlinks(self):
        with tempfile.TemporaryDirectory(prefix="dap-package-test-") as directory:
            root = pathlib.Path(directory)
            (root / "a.txt").write_bytes(b"a")
            (root / "link.txt").symlink_to("a.txt")
            lines = package.checksum_lines(root)
            self.assertEqual(len(lines), 1)
            self.assertTrue(lines[0].endswith("  a.txt"))


class StripPycacheTest(unittest.TestCase):
    def test_removes_bytecode_caches_but_keeps_sources(self):
        with tempfile.TemporaryDirectory(prefix="dap-package-test-") as directory:
            root = pathlib.Path(directory)
            (root / "sub" / "__pycache__").mkdir(parents=True)
            (root / "sub" / "mod.py").write_text("x = 1", encoding="utf-8")
            (root / "sub" / "__pycache__" / "mod.pyc").write_bytes(b"cache")
            package.strip_pycache(root)
            self.assertFalse((root / "sub" / "__pycache__").exists())
            self.assertTrue((root / "sub" / "mod.py").is_file())


class LayoutTest(unittest.TestCase):
    def test_layout_paths(self):
        root = pathlib.Path("/stage")
        expected = {
            "bin/dap": root / "bin" / "dap",
            "bin/dap-engine": root / "bin" / "dap-engine",
            "bin/dap-mcp": root / "bin" / "dap-mcp",
            "lib/dap-native.jar": root / "lib" / "dap-native.jar",
            "runtime": root / "runtime",
            "python": root / "python",
            "adapters/mcp": root / "adapters" / "mcp",
            "api/openapi.yaml": root / "api" / "openapi.yaml",
            "recipes": root / "recipes",
            "examples": root / "examples",
            "VERSION": root / "VERSION",
            "LICENSE": root / "LICENSE",
            "NOTICE": root / "NOTICE",
            "README.md": root / "README.md",
            "SHA256SUMS": root / "SHA256SUMS",
        }
        self.assertEqual(package.layout(root), expected)

    def test_layout_is_relative_to_root(self):
        root = pathlib.Path("/tmp/another-stage")
        for name, path in package.layout(root).items():
            self.assertTrue(path.is_relative_to(root), name)


if __name__ == "__main__":
    unittest.main()
