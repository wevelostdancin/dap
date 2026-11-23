#!/usr/bin/env python3
"""Unit tests for the repository lint helpers (no engine required)."""
from __future__ import annotations

import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("check_repo", ROOT / "scripts" / "check_repo.py")
check_repo = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check_repo)


class CjkTest(unittest.TestCase):
    def test_detects_chinese(self):
        text = "hello\n" + "\u4f60\u597d" + "\n"
        self.assertEqual(check_repo.find_cjk_lines(text), [(2, "\u4f60\u597d")])

    def test_detects_japanese_and_korean(self):
        text = "\u65e5\u672c\u8a9e\n\ud55c\uad6d\uc5b4\n"
        self.assertEqual(len(check_repo.find_cjk_lines(text)), 2)

    def test_ignores_latin_and_cyrillic(self):
        self.assertEqual(check_repo.find_cjk_lines("\u043f\u0440\u0438\u0432\u0435\u0442\nhello\n"), [])

    def test_doc_suffixes(self):
        self.assertIn(".md", check_repo.DOC_SUFFIXES)
        self.assertIn(".yml", check_repo.DOC_SUFFIXES)
