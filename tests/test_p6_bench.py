#!/usr/bin/env python3
"""Unit tests for the P6 benchmark harness helpers (stdlib unittest).

The harness (tests/p6_bench.py) is stdlib-only and never touches an engine
here: every test covers a pure helper, so this suite runs in milliseconds.

    python3 -m unittest tests.test_p6_bench -v
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
HARNESS = ROOT / "tests" / "p6_bench.py"


def load_harness():
    spec = importlib.util.spec_from_file_location("p6_bench_under_test", HARNESS)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HarnessCase(unittest.TestCase):
    """Loads the harness once; every test below exercises pure helpers."""

    @classmethod
    def setUpClass(cls):
        cls.bench = load_harness()


class ParseDatasetsTest(HarnessCase):
    def test_none_and_empty_mean_all(self):
        self.assertIsNone(self.bench.parse_datasets(None))
        self.assertIsNone(self.bench.parse_datasets(""))
        self.assertIsNone(self.bench.parse_datasets("   "))

    def test_splits_and_strips(self):
        self.assertEqual(self.bench.parse_datasets("iris, p2 ,adult"),
                         ("iris", "p2", "adult"))

    def test_rejects_empty_item(self):
        with self.assertRaises(ValueError):
            self.bench.parse_datasets("iris,,p2")


class SizeTest(HarnessCase):
    def test_approx_tokens_is_bytes_over_four(self):
        self.assertEqual(self.bench.approx_tokens(4000), 1000)
        self.assertEqual(self.bench.approx_tokens(3), 0)
        self.assertEqual(self.bench.approx_tokens(0), 0)

    def test_json_size_counts_utf8_bytes(self):
        self.assertEqual(self.bench.json_size({"a": 1}), 7)
        self.assertEqual(self.bench.json_size({"a": "я"}), 10)

    def test_format_bytes(self):
        self.assertEqual(self.bench.format_bytes(0), "0 B")
        self.assertEqual(self.bench.format_bytes(1024), "1.0 KiB")
        self.assertEqual(self.bench.format_bytes(1536), "1.5 KiB")
        self.assertEqual(self.bench.format_bytes(None), "n/a")


class TimingTest(HarnessCase):
    def test_empty_samples_have_no_numbers(self):
        stats = self.bench.timing([])
        self.assertEqual(stats["count"], 0)
        for key in ("min_ms", "median_ms", "max_ms", "mean_ms"):
            self.assertIsNone(stats[key])

    def test_median_min_max_mean(self):
        stats = self.bench.timing([10.0, 20.0, 30.0])
        self.assertEqual(stats["count"], 3)
        self.assertEqual(stats["min_ms"], 10.0)
        self.assertEqual(stats["median_ms"], 20.0)
        self.assertEqual(stats["max_ms"], 30.0)
        self.assertEqual(stats["mean_ms"], 20.0)

    def test_even_median_is_the_mean_of_the_middle(self):
        self.assertEqual(self.bench.timing([10.0, 20.0, 30.0, 40.0])["median_ms"], 25.0)


class DiffTest(HarnessCase):
    def test_abs_and_rel(self):
        diff = self.bench.diff_value(100, 105)
        self.assertEqual(diff["reference"], 100)
        self.assertEqual(diff["observed"], 105)
        self.assertEqual(diff["abs"], 5.0)
        self.assertEqual(diff["rel"], 0.05)

    def test_zero_reference_has_no_relative_diff(self):
        diff = self.bench.diff_value(0, 5)
        self.assertEqual(diff["abs"], 5.0)
        self.assertIsNone(diff["rel"])

    def test_missing_observed_keeps_reference(self):
        diff = self.bench.diff_value(7, None)
        self.assertEqual(diff["reference"], 7)
        self.assertIsNone(diff["observed"])
        self.assertIsNone(diff["abs"])
        self.assertIsNone(diff["rel"])


class ReferenceDiffTest(HarnessCase):
    def test_rows_means_and_group_counts(self):
        reference = {"rows": 150, "numeric_means": {"a": 1.0}, "group_counts": {"x": 10}}
        observed = {"rows": 151, "numeric_means": {"a": 1.5}, "group_counts": {"x": 9}}
        diff = self.bench.reference_diff(reference, observed)
        self.assertEqual(diff["rows"]["abs"], 1.0)
        self.assertAlmostEqual(diff["rows"]["rel"], 1 / 150)
        self.assertEqual(diff["numeric_means"]["a"]["abs"], 0.5)
        self.assertEqual(diff["group_counts"]["x"]["abs"], 1.0)

    def test_missing_observed_column_is_none(self):
        reference = {"rows": 1, "numeric_means": {"a": 1.0, "b": 2.0}}
        observed = {"rows": 1, "numeric_means": {"a": 1.0}}
        diff = self.bench.reference_diff(reference, observed)
        self.assertIsNone(diff["numeric_means"]["b"]["observed"])
        self.assertIsNone(diff["numeric_means"]["b"]["abs"])

    def test_no_reference_returns_none(self):
        self.assertIsNone(self.bench.reference_diff(None, {"rows": 1}))


class FileSizeTest(HarnessCase):
    def test_missing_path_is_none(self):
        self.assertIsNone(self.bench.file_size(None))
        self.assertIsNone(self.bench.file_size("/nonexistent/definitely/missing"))


class ProcessTest(HarnessCase):
    def test_rss_bytes_from_ps_output(self):
        self.assertEqual(self.bench.rss_bytes("  204800\n"), 204800 * 1024)

    def test_rss_bytes_rejects_garbage(self):
        self.assertIsNone(self.bench.rss_bytes(""))
        self.assertIsNone(self.bench.rss_bytes("not a number"))

    def test_engine_argv(self):
        self.assertEqual(self.bench.engine_argv("/jdk/bin/java", "4g", "/tmp/dap.jar"),
                         ["/jdk/bin/java", "-Xmx4g", "-jar", "/tmp/dap.jar"])


class EngineEnvTest(HarnessCase):
    def test_env_contains_lifecycle_contract(self):
        base = {"PATH": "/usr/bin"}
        env = self.bench.build_engine_env(base, "/repo", "/data", "/out", 7811, "2GB")
        self.assertEqual(env["PATH"], "/usr/bin")
        self.assertEqual(env["DAP_HOME"], "/repo")
        self.assertEqual(env["DAP_PORT"], "7811")
        self.assertEqual(env["DAP_BIND"], "127.0.0.1")
        self.assertEqual(env["DAP_DUCKDB_MEMORY_LIMIT"], "2GB")
        self.assertEqual(env["DAP_DUCKDB_TEMP_DIR"], "/out/duckdb-tmp")
        self.assertEqual(env["DAP_CACHE_DIR"], "/out/cache")
        self.assertEqual(env["DAP_ALLOWED_ROOTS"], "/data:/out:/repo:/tmp")

    def test_env_does_not_mutate_the_input(self):
        base = {"PATH": "/usr/bin"}
        self.bench.build_engine_env(base, "/repo", "/data", "/out", 7811, "1GB")
        self.assertEqual(base, {"PATH": "/usr/bin"})


class BaseUrlTest(HarnessCase):
    def test_parse_base(self):
        self.assertEqual(self.bench.parse_base("http://127.0.0.1:7811"), ("127.0.0.1", 7811))
        self.assertEqual(self.bench.parse_base("http://localhost"), ("localhost", 80))
        self.assertEqual(self.bench.parse_base("https://example.test"), ("example.test", 443))

    def test_parse_base_rejects_missing_scheme(self):
        with self.assertRaises(ValueError):
            self.bench.parse_base("127.0.0.1:7811")


class ManifestTest(HarnessCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)
        (self.dir / "data").mkdir()
        (self.dir / "data" / "iris.csv").write_text("a,b\n1,2\n", encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, payload):
        path = self.dir / "manifest.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        return path

    def test_relative_path_resolves_against_root(self):
        path = self.write([{"name": "iris", "path": "data/iris.csv"}])
        entries = self.bench.load_manifest(path, root=self.dir)
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["path"], str(self.dir / "data" / "iris.csv"))
        self.assertEqual(entries[0]["format"], "csv")

    def test_defaults_are_filled(self):
        path = self.write([{"name": "iris", "path": "data/iris.csv"}])
        entry = self.bench.load_manifest(path, root=self.dir)[0]
        self.assertIsNone(entry["sql"])
        self.assertEqual(entry["numeric_columns"], [])
        self.assertIsNone(entry["group_column"])
        self.assertIsNone(entry["value_column"])

    def test_non_array_manifest_raises(self):
        path = self.write({"name": "iris"})
        with self.assertRaises(self.bench.ManifestError):
            self.bench.load_manifest(path, root=self.dir)

    def test_missing_name_raises(self):
        path = self.write([{"path": "data/iris.csv"}])
        with self.assertRaises(self.bench.ManifestError):
            self.bench.load_manifest(path, root=self.dir)

    def test_duplicate_name_raises(self):
        path = self.write([{"name": "iris", "path": "data/iris.csv"},
                           {"name": "iris", "path": "data/iris.csv"}])
        with self.assertRaises(self.bench.ManifestError):
            self.bench.load_manifest(path, root=self.dir)

    def test_unknown_format_raises(self):
        path = self.write([{"name": "iris", "path": "data/iris.csv", "format": "yaml"}])
        with self.assertRaises(self.bench.ManifestError):
            self.bench.load_manifest(path, root=self.dir)

    def test_xlsx_aliases_excel(self):
        path = self.write([{"name": "book", "path": "data/book.xlsx"}])
        self.assertEqual(self.bench.load_manifest(path, root=self.dir)[0]["format"], "excel")

    def test_unknown_extension_raises(self):
        path = self.write([{"name": "notes", "path": "data/notes.txt"}])
        with self.assertRaises(self.bench.ManifestError):
            self.bench.load_manifest(path, root=self.dir)

    def test_missing_manifest_file_raises(self):
        with self.assertRaises(self.bench.ManifestError):
            self.bench.load_manifest(self.dir / "nope.json", root=self.dir)

    def test_broken_json_raises_manifest_error(self):
        path = self.dir / "broken.json"
        path.write_text("{not json", encoding="utf-8")
        with self.assertRaises(self.bench.ManifestError):
            self.bench.load_manifest(path, root=self.dir)

    def test_select_entries_keeps_manifest_order(self):
        path = self.write([{"name": "a", "path": "data/iris.csv"},
                           {"name": "b", "path": "data/iris.csv"}])
        entries = self.bench.load_manifest(path, root=self.dir)
        selected = self.bench.select_entries(entries, ("b",))
        self.assertEqual([entry["name"] for entry in selected], ["b"])
        self.assertEqual(self.bench.select_entries(entries, None), entries)

    def test_select_entries_unknown_name_raises(self):
        path = self.write([{"name": "a", "path": "data/iris.csv"}])
        entries = self.bench.load_manifest(path, root=self.dir)
        with self.assertRaises(self.bench.ManifestError):
            self.bench.select_entries(entries, ("zzz",))


class RegressionSettingsTest(HarnessCase):
    def test_predictors_exclude_value_column(self):
        settings = self.bench.regression_settings({
            "numeric_columns": ["a", "b", "y"], "value_column": "y", "group_column": "g"})
        self.assertEqual(settings["x"], ["a", "b"])
        self.assertEqual(settings["y"], "y")
        self.assertEqual(settings["group_by"], ["g"])
        self.assertEqual(settings["mode"], "coefficients")

    def test_no_value_column_means_no_regression(self):
        self.assertIsNone(self.bench.regression_settings({"numeric_columns": ["a", "b"]}))

    def test_value_column_without_predictors_means_no_regression(self):
        self.assertIsNone(self.bench.regression_settings(
            {"numeric_columns": ["y"], "value_column": "y"}))

    def test_group_column_equal_to_value_is_not_grouped(self):
        settings = self.bench.regression_settings({
            "numeric_columns": ["a", "y"], "value_column": "y", "group_column": "y"})
        self.assertNotIn("group_by", settings)


class GroupCountsTest(HarnessCase):
    def test_parses_string_table_values(self):
        table = {"columns": [{"name": "grp"}, {"name": "n"}],
                 "data": [["a", "10"], ["b", "3"]]}
        self.assertEqual(self.bench.group_counts_from_table(table), {"a": 10, "b": 3})

    def test_ignores_short_rows_and_garbage(self):
        table = {"columns": [{"name": "grp"}, {"name": "n"}],
                 "data": [["a"], ["b", "x"], ["c", "2"]]}
        self.assertEqual(self.bench.group_counts_from_table(table), {"c": 2})

    def test_empty_table(self):
        self.assertEqual(self.bench.group_counts_from_table(None), {})


class QuoteIdentifierTest(HarnessCase):
    def test_quotes_and_escapes(self):
        self.assertEqual(self.bench.quote_identifier("state"), '"state"')
        self.assertEqual(self.bench.quote_identifier('we"ird'), '"we""ird"')


class MemorySnapshotTest(HarnessCase):
    class FakeEngine:
        rss_error = "ps unavailable: boom"

        def rss(self):
            return None

        def health(self, client):
            return {"memory": {"usedMb": 7}}, 0

    def test_snapshot_records_health_and_rss_note(self):
        snapshot = self.bench.memory_snapshot(self.FakeEngine(), object())
        self.assertEqual(snapshot["healthMemory"], {"usedMb": 7})
        self.assertIsNone(snapshot["rssBytes"])
        self.assertEqual(snapshot["rssNote"], "ps unavailable: boom")


class SummaryTest(HarnessCase):
    def summary(self):
        return {
            "format": "dap-p6-bench/1",
            "generatedAt": "2026-11-15T20:00:00+00:00",
            "environment": {"platform": "macOS-arm64", "python": "3.13.18",
                            "java": "Temurin 21.0.13", "heap": "4g",
                            "duckdbMemory": "1GB", "jar": "/repo/engine-native/target/dap-native.jar"},
            "engine": {"base": "http://127.0.0.1:7811", "version": "1.2.0",
                       "startMs": 1234.5, "healthMs": 3.0},
            "datasets": [
                {"name": "iris", "path": "/data/iris.csv", "format": "csv",
                 "steps": {"ingest": {"ms": 12.3, "bytes": 400, "ok": True, "error": None},
                           "profile": {"ms": 30.0, "bytes": 900, "ok": False,
                                       "error": "boom"}},
                 "accuracy": {"rows": {"reference": 150, "observed": 150,
                                       "abs": 0.0, "rel": 0.0},
                              "numeric_means": {"sepal_length": {
                                  "reference": 5.8, "observed": 5.9,
                                  "abs": 0.1, "rel": 0.017}}}},
            ],
            "mcp": {"enabled": True,
                    "calls": [{"tool": "dap_explore", "ms": 100.0, "bytes": 4000,
                               "tokens": 1000, "ok": True, "error": None}],
                    "callCount": 1, "totalMs": 100.0, "totalBytes": 4000,
                    "totalTokens": 1000,
                    "comparison": [{"tool": "dap_explore", "mcpMs": 100.0,
                                    "httpMs": 250.0, "httpSteps": 3, "speedup": 2.5}]},
            "accuracy": {"available": True, "datasets": ["iris"]},
            "totals": {"datasets": 1, "steps": 2, "failed": 1},
        }

    def test_markdown_has_title_and_environment(self):
        md = self.bench.render_summary_md(self.summary())
        self.assertTrue(md.startswith("# P6 benchmark"))
        self.assertIn("| platform | macOS-arm64 |", md)
        self.assertIn("DAP 1.2.0", md)

    def test_markdown_lists_steps_and_failures(self):
        md = self.bench.render_summary_md(self.summary())
        self.assertIn("| iris | ingest | 12.3 | 400 | yes |", md)
        self.assertIn("no: boom", md)

    def test_markdown_lists_mcp_overhead(self):
        md = self.bench.render_summary_md(self.summary())
        self.assertIn("## MCP overhead", md)
        self.assertIn("| dap_explore | 100.0 | 4000 | 1000 | yes |", md)
        self.assertIn("| dap_explore | 100.0 | 250.0 | 3 | 2.5 |", md)

    def test_markdown_lists_accuracy(self):
        md = self.bench.render_summary_md(self.summary())
        self.assertIn("| iris | rows | 150 | 150 | 0.0 | 0.0 |", md)
        self.assertIn("mean:sepal_length", md)

    def test_markdown_keeps_wall_ms_precision(self):
        summary = self.summary()
        summary["datasets"][0]["steps"]["ingest"]["ms"] = 1544.6
        md = self.bench.render_summary_md(summary)
        self.assertIn("| iris | ingest | 1544.6 | 400 | yes |", md)

    def test_markdown_keeps_tiny_diffs_readable(self):
        summary = self.summary()
        summary["datasets"][0]["accuracy"]["numeric_means"]["sepal_length"]["abs"] = 3.33e-07
        md = self.bench.render_summary_md(summary)
        self.assertIn("3.33e-07", md)

    def test_markdown_totals_line(self):
        md = self.bench.render_summary_md(self.summary())
        self.assertIn("steps: 2 (1 failed)", md)

    def test_markdown_marks_skipped_accuracy(self):
        summary = self.summary()
        summary["accuracy"] = {"available": False, "note": "no reference.json"}
        md = self.bench.render_summary_md(summary)
        self.assertIn("no reference.json", md)


class GateTest(HarnessCase):
    """Gate parser and thresholds; pure helpers, no engine required."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, text):
        path = self.dir / name
        path.write_text(text, encoding="utf-8")
        return path

    def summary(self):
        return {
            "accuracy": {"available": True, "datasets": ["iris"]},
            "totals": {"datasets": 1, "steps": 3, "failed": 0},
            "mcp": {"enabled": True, "callCount": 8, "failedCalls": 0,
                     "totalTokens": 2000},
            "datasets": [{"name": "iris", "accuracy": {
                "rows": {"reference": 150, "observed": 150, "abs": 0.0},
                "numeric_means": {"sepal_length": {
                    "reference": 5.84, "observed": 5.84, "abs": 0.0}},
                "group_counts": {"setosa": {
                    "reference": 50, "observed": 50, "abs": 0.0}},
            }}],
        }

    def test_load_gate_reads_thresholds(self):
        path = self.write("gate.json", json.dumps({
            "maxFailedSteps": 2, "maxFailedMcpCalls": 3, "maxMcpCalls": 10,
            "maxMcpTokens": 3500, "maxMeanAbsDiff": 1e-4,
            "requireAccuracy": False,
        }))
        self.assertEqual(self.bench.load_gate(path), {
            "maxFailedSteps": 2, "maxFailedMcpCalls": 3, "maxMcpCalls": 10,
            "maxMcpTokens": 3500, "maxMeanAbsDiff": 1e-4,
            "requireAccuracy": False,
        })

    def test_load_gate_defaults_missing_keys(self):
        gate = self.bench.load_gate(self.write("gate.json", "{}"))
        self.assertEqual(gate, self.bench.DEFAULT_GATE)

    def test_load_gate_rejects_broken_json(self):
        with self.assertRaises(self.bench.BenchError):
            self.bench.load_gate(self.write("bad.json", "{not json"))

    def test_load_gate_rejects_missing_file(self):
        with self.assertRaises(self.bench.BenchError):
            self.bench.load_gate(self.dir / "missing.json")

    def test_load_gate_rejects_wrong_threshold_type(self):
        with self.assertRaises(self.bench.BenchError):
            self.bench.load_gate(self.write("bad.json", '{"maxMcpTokens": "4000"}'))

    def test_load_gate_rejects_non_object(self):
        with self.assertRaises(self.bench.BenchError):
            self.bench.load_gate(self.write("bad.json", "[]"))

    def test_check_gate_passes_clean_summary(self):
        self.assertEqual(self.bench.check_gate(self.summary(), self.bench.DEFAULT_GATE), [])

    def test_check_gate_token_overflow(self):
        summary = self.summary()
        summary["mcp"]["totalTokens"] = 4001
        violations = self.bench.check_gate(summary, self.bench.DEFAULT_GATE)
        self.assertTrue(any("tokens" in violation for violation in violations), violations)

    def test_check_gate_requires_accuracy(self):
        summary = self.summary()
        summary["accuracy"] = {"available": False, "note": "no reference"}
        violations = self.bench.check_gate(summary, self.bench.DEFAULT_GATE)
        self.assertTrue(any("accuracy" in violation for violation in violations), violations)

    def test_check_gate_accuracy_can_be_optional(self):
        summary = self.summary()
        summary["accuracy"] = {"available": False}
        self.assertEqual(self.bench.check_gate(summary, {"requireAccuracy": False}), [])

    def test_check_gate_failed_steps_and_mcp_calls(self):
        summary = self.summary()
        summary["totals"]["failed"] = 1
        summary["mcp"]["failedCalls"] = 1
        summary["mcp"]["callCount"] = 13
        violations = self.bench.check_gate(summary, self.bench.DEFAULT_GATE)
        self.assertEqual(len(violations), 3, violations)
        self.assertTrue(any("steps" in violation for violation in violations))
        self.assertTrue(any("failed MCP calls" in violation for violation in violations))
        self.assertTrue(any("MCP calls" in violation for violation in violations))

    def test_check_gate_rows_must_be_exact(self):
        summary = self.summary()
        summary["datasets"][0]["accuracy"]["rows"]["abs"] = 0.5
        violations = self.bench.check_gate(summary, self.bench.DEFAULT_GATE)
        self.assertTrue(any("rows" in violation for violation in violations), violations)

    def test_check_gate_rows_exact_below_mean_tolerance(self):
        summary = self.summary()
        summary["datasets"][0]["accuracy"]["rows"]["abs"] = 1e-4
        violations = self.bench.check_gate(summary, self.bench.DEFAULT_GATE)
        self.assertTrue(any("rows" in violation for violation in violations), violations)

    def test_check_gate_group_counts_must_be_exact(self):
        summary = self.summary()
        summary["datasets"][0]["accuracy"]["group_counts"]["setosa"]["abs"] = 1
        violations = self.bench.check_gate(summary, self.bench.DEFAULT_GATE)
        self.assertTrue(any("setosa" in violation for violation in violations), violations)

    def test_check_gate_mean_abs_diff(self):
        summary = self.summary()
        summary["datasets"][0]["accuracy"]["numeric_means"]["sepal_length"]["abs"] = 1.01e-3
        violations = self.bench.check_gate(summary, self.bench.DEFAULT_GATE)
        self.assertTrue(any("sepal_length" in violation for violation in violations), violations)

    def test_check_gate_without_mcp_section(self):
        summary = self.summary()
        del summary["mcp"]
        self.assertEqual(self.bench.check_gate(summary, self.bench.DEFAULT_GATE), [])

    def test_check_gate_missing_observed_value(self):
        summary = self.summary()
        rows = summary["datasets"][0]["accuracy"]["rows"]
        rows["observed"] = None
        rows["abs"] = None
        violations = self.bench.check_gate(summary, self.bench.DEFAULT_GATE)
        self.assertTrue(any("rows" in violation for violation in violations), violations)


if __name__ == "__main__":
    unittest.main()
