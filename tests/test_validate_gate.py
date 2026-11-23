#!/usr/bin/env python3
"""Unit tests for the accuracy gate summary and exit code.

No engine is started and no reference dependency is required: the summary is a
pure function in ``tests/reference/validate_dap.py``. The optional check for the
full reference stack (numpy/scipy) skips when it is not installed.

    python3 -m unittest tests.test_validate_gate -v
"""
from __future__ import annotations

import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
VALIDATE = ROOT / "tests" / "reference" / "validate_dap.py"


def load_validate():
    """Load validate_dap.py from disk so the test needs no package imports."""
    spec = importlib.util.spec_from_file_location("validate_dap_under_test", VALIDATE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SummaryGateTest(unittest.TestCase):
    """The gate is decided only by failures and the --min-cases threshold."""

    @classmethod
    def setUpClass(cls):
        cls.validate = load_validate()

    def test_all_cases_pass_at_threshold(self):
        results = [{"case": f"case{i}", "passed": True} for i in range(27)]
        summary = self.validate.summarize_results(results, 27)
        self.assertEqual(summary["cases_total"], 27)
        self.assertEqual(summary["cases_passed"], 27)
        self.assertEqual(summary["cases_failed"], 0)
        self.assertEqual(summary["failures"], [])
        self.assertEqual(summary["exit_code"], 0)

    def test_one_failure_is_listed_and_fails_gate(self):
        results = [
            {"case": "good", "passed": True},
            {"case": "bad", "passed": False, "detail": "delta too large"},
        ]
        summary = self.validate.summarize_results(results, 0)
        self.assertEqual(summary["cases_total"], 2)
        self.assertEqual(summary["cases_passed"], 1)
        self.assertEqual(summary["cases_failed"], 1)
        self.assertEqual(summary["failures"], [{"case": "bad", "detail": "delta too large"}])
        self.assertEqual(summary["exit_code"], 1)

    def test_too_few_cases_fails_gate_without_failures(self):
        summary = self.validate.summarize_results([{"case": "only", "passed": True}], 27)
        self.assertEqual(summary["cases_failed"], 0)
        self.assertEqual(summary["exit_code"], 1)

    def test_engine_start_failure_is_reported(self):
        summary = self.validate.summarize_results(
            [{"case": "engine", "passed": False, "detail": "engine exited during start-up"}], 27)
        self.assertEqual(summary["cases_failed"], 1)
        self.assertEqual(summary["failures"][0]["case"], "engine")
        self.assertEqual(summary["exit_code"], 1)

    def test_empty_results_pass_with_zero_threshold(self):
        summary = self.validate.summarize_results([], 0)
        self.assertEqual(summary["cases_total"], 0)
        self.assertEqual(summary["exit_code"], 0)

    def test_suite_has_at_least_the_gate_threshold(self):
        self.assertGreaterEqual(len(self.validate.CASES), 27)


class ReferenceStackTest(unittest.TestCase):
    """Optional: the full reference stack is needed only to run the cases."""

    @classmethod
    def setUpClass(cls):
        try:
            import numpy  # noqa: F401
            import scipy  # noqa: F401
        except ImportError as exc:
            raise unittest.SkipTest(f"reference dependencies unavailable: {exc}") from exc

    def test_module_exposes_reference_stack(self):
        module = load_validate()
        self.assertIsNotNone(module.np)
        self.assertIsNotNone(module.stats)


if __name__ == "__main__":
    unittest.main()
