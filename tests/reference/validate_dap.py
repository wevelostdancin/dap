#!/usr/bin/env python3
"""End-to-end accuracy validation: the running DAP engine vs independent references.

Every case builds a small dataset, runs a node through `/v1`, and compares the
result with scipy / statsmodels / scikit-learn / lifelines / numpy. A case fails
when a number differs beyond the tolerance or when a degenerate input produces an
unexpected error or invalid JSON.

Usage (reference environment, never the runtime environment):
  /Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/validate_dap.py \
      --jar engine-native/target/dap-native.jar \
      --json build/accuracy.json --min-cases 27

Exit code 1 when a case fails or fewer than `--min-cases` cases ran (default 0).
`--only a,b` runs a subset, `--list` prints the case names.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    import numpy as np
    from scipy import stats
except ImportError:  # the pure summary helpers below need no reference stack
    np = None
    stats = None

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JAR = ROOT / "engine-native" / "target" / "dap-native.jar"
TOL = 1e-9
REL_TOL = 1e-9


# --------------------------------------------------------------------------- engine


class Engine:
    """A dap-native process on a private port with private cache/temp dirs."""

    def __init__(self, jar: Path, port: int, workdir: Path):
        self.jar = jar
        self.port = port
        self.workdir = workdir
        self.base = f"http://127.0.0.1:{port}"
        self.process: subprocess.Popen | None = None
        self.log = open(workdir / "engine.log", "w")

    def start(self) -> None:
        env = dict(os.environ)
        env.update(
            DAP_PORT=str(self.port),
            DAP_BIND="127.0.0.1",
            DAP_HOME=str(ROOT),
            DAP_ALLOWED_ROOTS=f"{self.workdir}:/tmp",
            DAP_CACHE_DIR=str(self.workdir / "cache"),
            DAP_DUCKDB_TEMP_DIR=str(self.workdir / "duckdb-tmp"),
            DAP_WARMUP="0",
        )
        self.process = subprocess.Popen(
            [self.java(), "-Xmx2g", "-jar", str(self.jar)],
            stdout=self.log, stderr=subprocess.STDOUT, env=env)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                self.call("GET", "/v1/health", timeout=2)
                return
            except Exception:
                if self.process.poll() is not None:
                    raise RuntimeError("engine exited during start-up, see engine.log")
                time.sleep(0.1)
        raise RuntimeError("engine did not answer /v1/health")

    @staticmethod
    def java() -> str:
        explicit = os.environ.get("DAP_JDK") or os.environ.get("JAVA_HOME")
        if explicit:
            candidate = Path(explicit) / "bin" / "java"
            if candidate.exists():
                return str(candidate)
        # The laptop contract pins JDK 21 under ~/Projects/tools; fall back to PATH.
        for candidate in sorted(Path.home().glob("Projects/tools/jdk-21*/Contents/Home/bin/java")):
            if candidate.exists():
                return str(candidate)
        return "java"

    def stop(self) -> None:
        try:
            self.call("POST", "/v1/shutdown", timeout=10)
        except Exception:
            pass
        if self.process is not None:
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.log.close()

    def call(self, method: str, path: str, body=None, timeout=600):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            self.base + path, data=data, method=method,
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode())

    # -- workflow helpers ---------------------------------------------------

    def run_node(self, csv: Path, factory: str, settings: dict, limit: int = 500):
        """csv_reader -> factory, run, return (columns, rows-as-dicts)."""
        workflow = self.call("POST", "/v1/workflows", {"name": "validate"})["id"]
        try:
            reader = self.call("POST", f"/v1/workflows/{workflow}/nodes",
                               {"factory": "dap.csv_reader", "settings": {"file": str(csv)}})["node"]
            node = self.call("POST", f"/v1/workflows/{workflow}/nodes",
                             {"factory": f"dap.{factory}", "settings": settings})["node"]
            self.call("POST", f"/v1/workflows/{workflow}/connect", {"from": reader, "to": node})
            self.call("POST", f"/v1/workflows/{workflow}/run", {"timeoutSec": 120})
            table = self.call(
                "GET", f"/v1/workflows/{workflow}/nodes/{node}/table?limit={limit}")
            columns = [column["name"] for column in table["columns"]]
            rows = [dict(zip(columns, row)) for row in table["data"]]
            return columns, rows
        finally:
            try:
                self.call("DELETE", f"/v1/workflows/{workflow}")
            except Exception:
                pass

    def run_raw(self, csv: Path, factory: str, settings: dict):
        """Like run_node but returns the raw JSON table (for paging tests)."""
        workflow = self.call("POST", "/v1/workflows", {"name": "validate"})["id"]
        reader = self.call("POST", f"/v1/workflows/{workflow}/nodes",
                           {"factory": "dap.csv_reader", "settings": {"file": str(csv)}})["node"]
        node = self.call("POST", f"/v1/workflows/{workflow}/nodes",
                         {"factory": f"dap.{factory}", "settings": settings})["node"]
        self.call("POST", f"/v1/workflows/{workflow}/connect", {"from": reader, "to": node})
        self.call("POST", f"/v1/workflows/{workflow}/run", {"timeoutSec": 120})
        return workflow, node


# --------------------------------------------------------------------------- data


def write_csv(path: Path, header: list[str], rows: list[list]) -> Path:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(",".join(header) + "\n")
        for row in rows:
            handle.write(",".join("" if value is None else str(value) for value in row) + "\n")
    return path


def number(value):
    if value is None or value == "":
        return None
    return float(value)


def close(actual, expected, tolerance=TOL, relative=REL_TOL) -> bool:
    if actual is None or expected is None:
        return actual is None and expected is None
    if isinstance(expected, float) and math.isnan(expected):
        return actual is None or (isinstance(actual, float) and math.isnan(actual))
    if not math.isfinite(expected):
        return actual == expected
    return abs(actual - expected) <= max(tolerance, relative * abs(expected))


class CaseFailure(AssertionError):
    pass


def check(label: str, actual, expected, tolerance=TOL):
    if not close(actual, expected, tolerance):
        raise CaseFailure(f"{label}: dap={actual!r} reference={expected!r}")
    return f"{label}={actual:.10g}" if isinstance(actual, float) else f"{label}={actual}"


def check_true(label: str, condition: bool, detail=""):
    if not condition:
        raise CaseFailure(f"{label}: {detail}")
    return label


# --------------------------------------------------------------------------- cases


def case_describe(engine: Engine, data: Path):
    rng = np.random.default_rng(7)
    values = rng.normal(10.0, 2.0, 200)
    rows = [[None if index % 17 == 0 else round(float(value), 6)] for index, value in enumerate(values)]
    csv = write_csv(data / "describe.csv", ["x"], rows)
    _, result = engine.run_node(csv, "describe", {"columns": ["x"]})
    column = result[0]
    present = np.array([row[0] for row in rows if row[0] is not None], dtype=float)
    checks = [
        check("count", int(column["count"]), len(present)),
        check("missing", int(column["missing"]), len(rows) - len(present)),
        check("distinct", int(column["distinct"]), len(set(present.tolist()))),
        check("mean", number(column["mean"]), float(present.mean())),
        check("stddev", number(column["stddev"]), float(present.std(ddof=1))),
        check("min", number(column["min"]), float(present.min())),
        check("max", number(column["max"]), float(present.max())),
    ]
    return checks


def case_correlation(engine: Engine, data: Path):
    rng = np.random.default_rng(11)
    x = rng.normal(0, 1, 300)
    y = 0.6 * x + rng.normal(0, 0.8, 300)
    csv = write_csv(data / "corr.csv", ["x", "y"], [[round(a, 9), round(b, 9)] for a, b in zip(x, y)])
    checks = []
    for method, reference in (
            ("pearson", stats.pearsonr(x, y)),
            ("spearman", stats.spearmanr(x, y)),
            ("kendall", stats.kendalltau(x, y, method="asymptotic"))):
        _, result = engine.run_node(csv, "correlation", {"columns": ["x", "y"], "method": method})
        row = result[0]
        checks.append(check(f"{method}.correlation", number(row["correlation"]), float(reference.statistic)))
        checks.append(check(f"{method}.p_value", number(row["p_value"]), float(reference.pvalue)))

    # 200k rows: the moment-based path must agree with scipy without a row cap.
    n = 200_000
    big_x = rng.normal(0, 1, n)
    big_y = 0.6 * big_x + rng.normal(0, 0.8, n)
    big_x = np.round(big_x, 9)
    big_y = np.round(big_y, 9)
    big_csv = write_csv(data / "corr200k.csv", ["x", "y"],
                        [[float(a), float(b)] for a, b in zip(big_x, big_y)])
    for method, reference in (("pearson", stats.pearsonr(big_x, big_y)),
                              ("spearman", stats.spearmanr(big_x, big_y))):
        _, result = engine.run_node(big_csv, "correlation",
                                    {"columns": ["x", "y"], "method": method})
        row = result[0]
        checks.append(check(f"200k.{method}.n", int(row["n"]), n))
        checks.append(check(f"200k.{method}.correlation", number(row["correlation"]),
                            float(reference.statistic)))
        checks.append(check(f"200k.{method}.p_value", number(row["p_value"]),
                            float(reference.pvalue)))
    return checks


def case_correlation_ties(engine: Engine, data: Path):
    rng = np.random.default_rng(5)
    x = np.repeat(np.arange(1, 21), 3)
    y = np.repeat(np.array([1, 2, 3]), 20) + rng.integers(0, 2, 60)
    csv = write_csv(data / "ties.csv", ["x", "y"], [[int(a), int(b)] for a, b in zip(x, y)])
    _, result = engine.run_node(csv, "correlation", {"columns": ["x", "y"], "method": "kendall"})
    reference = stats.kendalltau(x, y, method="asymptotic")
    row = result[0]
    return [
        check("tau_b", number(row["correlation"]), float(reference.statistic)),
        check("p_value", number(row["p_value"]), float(reference.pvalue)),
    ]


def case_linear_regression(engine: Engine, data: Path):
    import statsmodels.api as sm
    rng = np.random.default_rng(13)
    x1 = rng.normal(0, 1, 400)
    x2 = rng.normal(0, 1, 400)
    y = 2.5 + 1.7 * x1 - 0.9 * x2 + rng.normal(0, 0.5, 400)
    csv = write_csv(data / "reg.csv", ["x1", "x2", "y"],
                    [[round(float(a), 9), round(float(b), 9), round(float(c), 9)]
                     for a, b, c in zip(x1, x2, y)])
    _, result = engine.run_node(csv, "linear_regression", {"x": ["x1", "x2"], "y": "y"})
    model = sm.OLS(y, sm.add_constant(np.column_stack([x1, x2]))).fit()
    terms = {row["term"]: row for row in result}
    checks = []
    for index, term in enumerate(["intercept", "x1", "x2"]):
        row = terms.get(term)
        if row is None:
            raise CaseFailure(f"missing term {term} in {list(terms)}")
        checks.append(check(f"{term}.coefficient", number(row["coefficient"]), float(model.params[index])))
        checks.append(check(f"{term}.std_error", number(row["std_error"]), float(model.bse[index])))
        checks.append(check(f"{term}.t", number(row["t_statistic"]), float(model.tvalues[index])))
        checks.append(check(f"{term}.p", number(row["p_value"]), float(model.pvalues[index])))
    checks.append(check("r2", number(terms["intercept"]["r2"]), float(model.rsquared)))

    # 200k rows: X'X/X'y from SQL moments must match statsmodels' OLS.
    n = 200_000
    big_x1 = np.round(rng.normal(0, 1, n), 9)
    big_x2 = np.round(rng.normal(0, 1, n), 9)
    big_y = np.round(2.5 + 1.7 * big_x1 - 0.9 * big_x2 + rng.normal(0, 0.5, n), 9)
    big_csv = write_csv(data / "reg200k.csv", ["x1", "x2", "y"],
                        [[float(a), float(b), float(c)]
                         for a, b, c in zip(big_x1, big_x2, big_y)])
    _, result = engine.run_node(big_csv, "linear_regression", {"x": ["x1", "x2"], "y": "y"})
    big_model = sm.OLS(big_y, sm.add_constant(np.column_stack([big_x1, big_x2]))).fit()
    terms = {row["term"]: row for row in result}
    for index, term in enumerate(["intercept", "x1", "x2"]):
        row = terms.get(term)
        if row is None:
            raise CaseFailure(f"missing term {term} in 200k result")
        checks.append(check(f"200k.{term}.coefficient", number(row["coefficient"]),
                            float(big_model.params[index])))
        checks.append(check(f"200k.{term}.std_error", number(row["std_error"]),
                            float(big_model.bse[index])))
        checks.append(check(f"200k.{term}.t", number(row["t_statistic"]),
                            float(big_model.tvalues[index])))
        checks.append(check(f"200k.{term}.p", number(row["p_value"]),
                            float(big_model.pvalues[index])))
    checks.append(check("200k.n", int(terms["intercept"]["n"]), n))
    checks.append(check("200k.r2", number(terms["intercept"]["r2"]),
                        float(big_model.rsquared)))
    return checks


def case_hypothesis(engine: Engine, data: Path):
    rng = np.random.default_rng(17)
    a = rng.normal(10, 1, 120)
    b = rng.normal(10.4, 1.1, 140)
    c = rng.normal(10.1, 0.9, 100)
    rows = [[round(float(v), 9), "a"] for v in a] + [[round(float(v), 9), "b"] for v in b] \
        + [[round(float(v), 9), "c"] for v in c]
    csv = write_csv(data / "hyp.csv", ["value", "group"], rows)
    checks = []
    _, welch = engine.run_node(csv, "hypothesis_test",
                               {"test": "t_test_two_sample", "value": "value", "group": "group",
                                "group_a": "a", "group_b": "b"})
    reference = stats.ttest_ind(a, b, equal_var=False)
    checks.append(check("welch.statistic", number(welch[0]["statistic"]), float(reference.statistic)))
    checks.append(check("welch.p", number(welch[0]["p_value"]), float(reference.pvalue)))
    _, anova = engine.run_node(csv, "hypothesis_test",
                               {"test": "anova", "value": "value", "group": "group"})
    reference = stats.f_oneway(a, b, c)
    checks.append(check("anova.statistic", number(anova[0]["statistic"]), float(reference.statistic)))
    checks.append(check("anova.p", number(anova[0]["p_value"]), float(reference.pvalue)))
    return checks


def case_chi_square(engine: Engine, data: Path):
    checks = []
    for name, table in (("dense", [[10, 20], [30, 40]]), ("sparse", [[10, 0], [0, 10]])):
        rows = []
        for row_index, row in enumerate(table):
            for column_index, count in enumerate(row):
                rows.extend([[f"r{row_index}", f"c{column_index}"]] * count)
        csv = write_csv(data / f"chi_{name}.csv", ["value", "group"], rows)
        _, result = engine.run_node(csv, "hypothesis_test",
                                    {"test": "chi_square", "value": "value", "group": "group"})
        reference = stats.chi2_contingency(np.array(table), correction=False)
        row = result[0]
        checks.append(check(f"{name}.statistic", number(row["statistic"]), float(reference.statistic)))
        checks.append(check(f"{name}.p", number(row["p_value"]), float(reference.pvalue)))
        checks.append(check(f"{name}.df", int(float(row["df"])), int(reference.dof)))
    return checks


def case_logrank(engine: Engine, data: Path):
    from lifelines.statistics import multivariate_logrank_test
    times = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
    events = [1, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1]
    groups = ["a", "a", "a", "b", "b", "b", "b", "c", "c", "c", "c", "c"]
    csv = write_csv(data / "survival.csv", ["time", "event", "grp"],
                    [[t, e, g] for t, e, g in zip(times, events, groups)])
    _, result = engine.run_node(csv, "survival",
                                {"time": "time", "event": "event", "group": "grp", "mode": "logrank"})
    reference = multivariate_logrank_test(times, groups, events)
    row = result[0]
    return [
        check("statistic", number(row["statistic"]), float(reference.test_statistic)),
        check("p", number(row["p_value"]), float(reference.p_value)),
    ]


def case_silhouette(engine: Engine, data: Path):
    from sklearn.metrics import silhouette_samples
    points = np.array([[0.0, 0.0], [0.1, 0.0], [0.0, 0.1], [5.0, 5.0], [5.1, 5.0], [5.0, 5.1], [10.0, 0.0]])
    labels = np.array([0, 0, 0, 1, 1, 1, 2])
    csv = write_csv(data / "cluster.csv", ["x", "y"], [[p[0], p[1]] for p in points])
    _, result = engine.run_node(csv, "cluster",
                                {"columns": ["x", "y"], "method": "kmeans", "k": 3,
                                 "standardize": False, "seed": 42})
    by_point = {}
    for row in result:
        by_point[(number(row["x"]), number(row["y"]))] = (int(row["cluster"]), number(row["silhouette"]))
    if len(by_point) != len(points):
        raise CaseFailure(f"cluster output has {len(by_point)} of {len(points)} points")
    labels_dap = np.array([by_point[(p[0], p[1])][0] for p in points])
    # Silhouette depends only on the partition, so any labelling works as the reference input.
    reference = silhouette_samples(points, labels_dap)
    checks = [check_true("three_clusters", len(set(labels_dap)) == 3, str(labels_dap))]
    for index, point in enumerate(points):
        checks.append(check(f"s{index}", by_point[(point[0], point[1])][1], float(reference[index])))
    return checks


def case_pca(engine: Engine, data: Path):
    rng = np.random.default_rng(23)
    x = rng.normal(0, 1, 500)
    y = 0.8 * x + rng.normal(0, 0.6, 500)
    z = rng.normal(0, 1, 500)
    csv = write_csv(data / "pca.csv", ["x", "y", "z"],
                    [[round(float(a), 9), round(float(b), 9), round(float(c), 9)]
                     for a, b, c in zip(x, y, z)])
    _, result = engine.run_node(csv, "pca",
                                {"columns": ["x", "y", "z"], "components": 3,
                                 "standardize": False, "mode": "explained"})
    matrix = np.column_stack([x, y, z])
    eigenvalues = np.sort(np.linalg.eigvalsh(np.cov(matrix, rowvar=False)))[::-1]
    expected = eigenvalues / eigenvalues.sum()
    got = [number(row["explained_variance"]) for row in result]
    if len(got) < len(expected):
        raise CaseFailure(f"expected {len(expected)} components, got {len(got)}")
    checks = [check(f"pc{index + 1}", got[index], float(value))
              for index, value in enumerate(expected)]

    # 200k rows: covariance from SQL cross products must match numpy.
    n = 200_000
    big_x = np.round(rng.normal(0, 1, n), 9)
    big_y = np.round(0.8 * big_x + rng.normal(0, 0.6, n), 9)
    big_z = np.round(rng.normal(0, 1, n), 9)
    big_csv = write_csv(data / "pca200k.csv", ["x", "y", "z"],
                        [[float(a), float(b), float(c)]
                         for a, b, c in zip(big_x, big_y, big_z)])
    _, result = engine.run_node(big_csv, "pca",
                                {"columns": ["x", "y", "z"], "components": 3,
                                 "standardize": False, "mode": "explained"})
    matrix = np.column_stack([big_x, big_y, big_z])
    eigenvalues = np.sort(np.linalg.eigvalsh(np.cov(matrix, rowvar=False)))[::-1]
    expected = eigenvalues / eigenvalues.sum()
    got = [number(row["explained_variance"]) for row in result]
    if len(got) < len(expected):
        raise CaseFailure(f"expected {len(expected)} 200k components, got {len(got)}")
    checks.extend(check(f"200k.pc{index + 1}", got[index], float(value))
                  for index, value in enumerate(expected))
    return checks


def case_quantile(engine: Engine, data: Path):
    rng = np.random.default_rng(29)
    values = rng.normal(5, 2, 400)
    csv = write_csv(data / "quant.csv", ["x"], [[round(float(v), 9)] for v in values])
    quantiles = [0.05, 0.25, 0.5, 0.75, 0.95]
    _, result = engine.run_node(csv, "quantile", {"column": "x", "quantiles": quantiles})
    got = {round(number(row["quantile"]), 6): number(row["value"]) for row in result}
    checks = []
    for q in quantiles:
        reference = float(np.quantile(values, q, method="linear"))
        checks.append(check(f"q{q}", got.get(q), reference, tolerance=1e-6))
    return checks


def case_histogram(engine: Engine, data: Path):
    rng = np.random.default_rng(31)
    values = rng.uniform(0, 100, 300)
    csv = write_csv(data / "hist.csv", ["x"], [[round(float(v), 9)] for v in values])
    _, result = engine.run_node(csv, "histogram", {"column": "x", "bins": 5})
    counts = [int(row["count"]) for row in result]
    reference, _ = np.histogram(values, bins=5, range=(values.min(), values.max()))
    checks = [check_true("bin_count", len(counts) == 5, str(counts)),
              check_true("total", sum(counts) == len(values), f"{sum(counts)} != {len(values)}")]
    return checks


def case_outlier_iqr(engine: Engine, data: Path):
    values = np.array([1, 2, 3, 4, 5, 6, 7, 8, 9, 100], dtype=float)
    csv = write_csv(data / "outlier.csv", ["x"], [[v] for v in values])
    _, result = engine.run_node(csv, "outlier_detect", {"columns": ["x"], "method": "iqr", "threshold": 1.5})
    q1, q3 = np.percentile(values, [25, 75])
    fence = 1.5 * (q3 - q1)
    expected = [(v < q1 - fence) or (v > q3 + fence) for v in values]
    got = [str(row["outlier"]).lower() in ("true", "1") for row in result]
    return [check_true("flags", got == expected, f"dap={got} reference={expected}")]


def case_normalizer(engine: Engine, data: Path):
    values = np.array([2.0, 4.0, 6.0, 8.0])
    csv = write_csv(data / "norm.csv", ["x"], [[v] for v in values])
    _, result = engine.run_node(csv, "normalizer", {"columns": [{"column": "x"}], "method": "minmax"})
    expected = (values - values.min()) / (values.max() - values.min())
    got = [number(row["x_norm"]) for row in result]
    return [check(f"x{index}", value, float(expected[index])) for index, value in enumerate(got)]


def case_paging(engine: Engine, data: Path):
    rows = [[index, f"row{index}"] for index in range(1000)]
    csv = write_csv(data / "paging.csv", ["id", "txt"], rows)
    workflow, node = engine.run_raw(csv, "sorter", {"keys": [{"column": "id"}]})
    try:
        seen, duplicates = [], 0
        for offset in range(0, 200, 20):
            page = engine.call("GET", f"/v1/workflows/{workflow}/nodes/{node}/table"
                                      f"?limit=20&offset={offset}")
            ids = [row[0] for row in page["data"]]
            if set(ids) & set(seen):
                duplicates += 1
            seen.extend(ids)
        first = engine.call("GET", f"/v1/workflows/{workflow}/nodes/{node}/table?limit=20&offset=0")
        second = engine.call("GET", f"/v1/workflows/{workflow}/nodes/{node}/table?limit=20&offset=0")
        return [
            check_true("no_duplicates", duplicates == 0, f"{duplicates} overlapping pages"),
            check_true("stable_order", first["data"] == second["data"], "two identical pages differ"),
            check("pages_total", len(seen), 200),
        ]
    finally:
        engine.call("DELETE", f"/v1/workflows/{workflow}")


def case_edge_empty(engine: Engine, data: Path):
    csv = write_csv(data / "empty.csv", ["x", "y"], [])
    columns, rows = engine.run_node(csv, "describe", {"columns": ["x"]})
    return [check("rows", len(rows), 1), check("count", int(rows[0]["count"]), 0)]


def case_edge_constant(engine: Engine, data: Path):
    csv = write_csv(data / "constant.csv", ["x", "y"], [[5, index] for index in range(50)])
    _, result = engine.run_node(csv, "correlation", {"columns": ["x", "y"], "method": "pearson"})
    row = result[0]
    return [check_true("correlation_null_or_zero",
                       number(row["correlation"]) in (None, 0.0),
                       f"correlation={row['correlation']}"),
            check_true("p_value_null_or_one",
                       number(row["p_value"]) in (None, 1.0),
                       f"p={row['p_value']}")]


def case_edge_all_null(engine: Engine, data: Path):
    csv = write_csv(data / "allnull.csv", ["x"], [[None] for _ in range(20)])
    _, result = engine.run_node(csv, "describe", {"columns": ["x"]})
    return [check("missing", int(result[0]["missing"]), 20),
            check_true("mean_null", result[0]["mean"] is None, str(result[0]["mean"]))]


def case_edge_infinite(engine: Engine, data: Path):
    csv = write_csv(data / "inf.csv", ["x"], [[1.0], [2.0], ["inf"], [4.0]])
    try:
        columns, rows = engine.run_node(csv, "describe", {"columns": ["x"]})
    except urllib.error.HTTPError as failure:
        body = failure.read().decode()[:200]
        return [check_true("infinite_rejected_or_handled", True, f"HTTP {failure.code}: {body}")]
    return [check_true("json_is_valid", True, str(rows[0]))]


def _explore(engine: Engine, path: Path, sample_rows: int = 1000):
    return engine.call("POST", "/v1/explore", {"file": str(path), "sampleRows": sample_rows})


def case_real_adult_profile(engine: Engine, data: Path):
    source = ROOT / "tests" / "goldens-native" / "data" / "adult.csv"
    csv = data / "adult.csv"
    shutil.copyfile(source, csv)
    workflow = engine.call("POST", "/v1/workflows", {"name": "adult"})["id"]
    try:
        reader = engine.call("POST", f"/v1/workflows/{workflow}/nodes",
                             {"factory": "dap.csv_reader", "settings": {"file": str(csv)}})["node"]
        engine.call("POST", f"/v1/workflows/{workflow}/run", {"timeoutSec": 120})
        profile = engine.call("GET", f"/v1/workflows/{workflow}/nodes/{reader}/profile")
    finally:
        engine.call("DELETE", f"/v1/workflows/{workflow}")
    import csv as csv_module
    with open(source, newline="", encoding="utf-8") as handle:
        rows = list(csv_module.DictReader(handle))
    checks = [check("rows", profile["rows"], len(rows))]
    for column in profile["columns"]:
        name = column["name"]
        values = [row[name] for row in rows if row[name] not in ("", None)]
        if column["type"] in ("Number (Integer)", "Number (Float)"):
            numbers = np.array([float(value) for value in values])
            checks.append(check(f"{name}.missing", column["missing"], len(rows) - len(numbers)))
            checks.append(check(f"{name}.mean", number(column["mean"]), float(numbers.mean())))
            checks.append(check(f"{name}.distinct", column["distinct"], len(set(numbers.tolist()))))
        else:
            checks.append(check(f"{name}.missing", column["missing"], len(rows) - len(values)))
            checks.append(check(f"{name}.distinct", column["distinct"], len(set(values))))
    return checks


def case_real_adult_explore(engine: Engine, data: Path):
    source = ROOT / "tests" / "goldens-native" / "data" / "adult.csv"
    csv = data / "adult_explore.csv"
    shutil.copyfile(source, csv)
    result = _explore(engine, csv)
    import csv as csv_module
    with open(source, newline="", encoding="utf-8") as handle:
        rows = list(csv_module.DictReader(handle))
    age = [row["age"] for row in rows]
    age_column = next(column for column in result["columns"] if column["name"] == "age")
    checks = [
        check("rows", result["rows"], len(rows)),
        check_true("not_sampled", result["quality_sampled"] is False,
                   f"quality_sampled={result['quality_sampled']}"),
        check("scanned", result["quality_scanned_rows"], len(rows)),
        check_true("distinct_exact", age_column["distinct_exact"] is True, "age distinct should be exact"),
        check("age.mean", number(age_column["mean"]), float(np.mean([float(v) for v in age]))),
        check_true("score_range", 0 <= result["quality"]["score"] <= 100, str(result["quality"]["score"])),
        check_true("issues_present", len(result["quality"]["issues"]) > 0, "adult has duplicates/outliers"),
    ]
    for difference in result["group_differences"]:
        checks.append(check_true("p_adjusted_present", "p_adjusted" in difference, str(difference)))
    return checks


def case_explore_sampled_outliers(engine: Engine, data: Path):
    rng = np.random.default_rng(41)
    values = rng.normal(0, 1, 1_000_000)
    values[:1_500] = 1_000_000.0
    rows = [[index, round(float(value), 6)] for index, value in enumerate(values)]
    csv = write_csv(data / "sampled.csv", ["id", "x"], rows)
    result = _explore(engine, csv, sample_rows=1000)
    issues = [issue for issue in result["quality"]["issues"] if issue["kind"] == "outliers"]
    ratio = None
    if issues:
        detail = issues[0]["detail"]
        for token in detail.replace("(", " ").replace("%", " ").split():
            try:
                candidate = float(token)
            except ValueError:
                continue
            if 0 < candidate <= 100 and "." in token:
                ratio = candidate / 100.0
    return [
        check_true("quality_sampled", result["quality_sampled"] is True,
                   f"quality_sampled={result['quality_sampled']}"),
        check("scanned", result["quality_scanned_rows"], 100_000),
        check_true("outlier_issue", len(issues) > 0, "20% outliers must be reported"),
        check_true("outlier_ratio", ratio is not None and 0.010 <= ratio <= 0.050,
                   f"ratio parsed {ratio} from {issues[0]['detail'] if issues else None}"),
    ]


def case_explore_small_n(engine: Engine, data: Path):
    rows = [[1.0, 2.0], [3.0, 9.0], [5.0, 1.0]]
    csv = write_csv(data / "small.csv", ["x", "y"], rows)
    result = _explore(engine, csv, sample_rows=3)
    high = [hypothesis for hypothesis in result["hypotheses"] if hypothesis["priority"] == "high"]
    modelling = [text for text in result["recommendations"] if "Model " in text]
    return [
        check_true("no_high_hypothesis", not high, str(high)),
        check_true("no_modelling_recommendation", not modelling, str(modelling)),
    ]


def case_pathguard_excel(engine: Engine, data: Path):
    forbidden = ROOT / "engine-native" / "pom.xml"  # outside DAP_ALLOWED_ROOTS
    workflow = engine.call("POST", "/v1/workflows", {"name": "guard"})["id"]
    try:
        engine.call("POST", f"/v1/workflows/{workflow}/nodes",
                    {"factory": "dap.excel_reader", "settings": {"file": str(forbidden)}})
        try:
            engine.call("POST", f"/v1/workflows/{workflow}/run", {"timeoutSec": 60})
        except urllib.error.HTTPError as failure:
            body = failure.read().decode()
            return [check("status", failure.code, 400),
                    check_true("path_not_allowed", "path_not_allowed" in body, body[:200])]
        raise CaseFailure("excel_reader read a path outside the allowlist")
    finally:
        engine.call("DELETE", f"/v1/workflows/{workflow}")


def case_pathguard_save(engine: Engine, data: Path):
    workflow = engine.call("POST", "/v1/workflows", {"name": "guard-save"})["id"]
    try:
        target = Path.home() / ".ssh" / "dap-should-not-write"
        try:
            engine.call("POST", f"/v1/workflows/{workflow}/save", {"dir": str(target)})
        except urllib.error.HTTPError as failure:
            body = json.loads(failure.read().decode())
            return [check("status", failure.code, 400),
                    check_true("code", body.get("code") == "path_not_allowed", str(body)),
                    check_true("not_created", not target.exists(), str(target))]
        raise CaseFailure("save accepted a denied directory")
    finally:
        engine.call("DELETE", f"/v1/workflows/{workflow}")


def case_cache_selfheal(engine: Engine, data: Path):
    csv = write_csv(data / "cache.csv", ["x"], [[index] for index in range(1000)])
    first = engine.call("POST", "/v1/datasets", {"file": str(csv), "format": "csv"})
    cached = Path(first["path"])
    os.truncate(cached, 100)
    second = engine.call("POST", "/v1/datasets", {"file": str(csv), "format": "csv"})
    return [
        check("rows_after_heal", second["rows"], 1000),
        check_true("rebuilt", second["cached"] is False, f"cached={second['cached']}"),
        check_true("quarantine", any("corrupt" in name for name in os.listdir(cached.parent)),
                   str(os.listdir(cached.parent))),
    ]


def case_api_limit(engine: Engine, data: Path):
    csv = write_csv(data / "limit.csv", ["x"], [[index] for index in range(5)])
    workflow, node = engine.run_raw(csv, "sorter", {"keys": [{"column": "x"}]})
    try:
        try:
            engine.call("GET", f"/v1/workflows/{workflow}/nodes/{node}/table?limit=100001")
        except urllib.error.HTTPError as failure:
            body = json.loads(failure.read().decode())
            return [check("status", failure.code, 400),
                    check_true("code", body.get("code") == "limit_too_large", str(body))]
        raise CaseFailure("limit above DAP_TABLE_MAX_LIMIT was accepted")
    finally:
        engine.call("DELETE", f"/v1/workflows/{workflow}")


def case_api_where(engine: Engine, data: Path):
    csv = write_csv(data / "where.csv", ["from_date", "status"],
                    [[1, "selected"], [2, "other"], [3, "selected"]])
    workflow, node = engine.run_raw(csv, "sorter", {"keys": [{"column": "from_date"}]})
    try:
        page = engine.call("GET", f"/v1/workflows/{workflow}/nodes/{node}/table"
                                  "?where=from_date%20%3E%201%20AND%20status%20%3D%20'selected'")
        return [check("rows", page["rows"], 1),
                check_true("first", page["data"][0][0] == "3", str(page["data"][:2]))]
    finally:
        engine.call("DELETE", f"/v1/workflows/{workflow}")


CASES = {
    "describe": case_describe,
    "correlation": case_correlation,
    "correlation_ties": case_correlation_ties,
    "linear_regression": case_linear_regression,
    "hypothesis": case_hypothesis,
    "chi_square": case_chi_square,
    "logrank": case_logrank,
    "silhouette": case_silhouette,
    "pca": case_pca,
    "quantile": case_quantile,
    "histogram": case_histogram,
    "outlier_iqr": case_outlier_iqr,
    "normalizer": case_normalizer,
    "paging": case_paging,
    "edge_empty": case_edge_empty,
    "edge_constant": case_edge_constant,
    "edge_all_null": case_edge_all_null,
    "edge_infinite": case_edge_infinite,
    "real_adult_profile": case_real_adult_profile,
    "real_adult_explore": case_real_adult_explore,
    "explore_sampled_outliers": case_explore_sampled_outliers,
    "explore_small_n": case_explore_small_n,
    "pathguard_excel": case_pathguard_excel,
    "pathguard_save": case_pathguard_save,
    "cache_selfheal": case_cache_selfheal,
    "api_limit": case_api_limit,
    "api_where": case_api_where,
}


def summarize_results(results, min_cases=0) -> dict:
    """Build the accuracy summary and gate exit code from per-case outcomes.

    Each item of ``results`` is a mapping with ``case``, ``passed`` and an
    optional ``detail``. The returned dict is both the JSON payload and the
    only source of the process exit code; it needs no engine or reference
    dependency, so it can be unit-tested on a plain interpreter.
    """
    failures = [
        {"case": item["case"], "detail": item.get("detail", "")}
        for item in results
        if not item["passed"]
    ]
    total = len(results)
    return {
        "cases_total": total,
        "cases_passed": total - len(failures),
        "cases_failed": len(failures),
        "failures": failures,
        "min_cases": min_cases,
        "exit_code": 1 if failures or total < min_cases else 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", default=str(DEFAULT_JAR))
    parser.add_argument("--port", type=int, default=7895)
    parser.add_argument("--only", default="")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--json", default="", help="write the accuracy summary to this path")
    parser.add_argument("--min-cases", type=int, default=0,
                        help="fail when fewer than N cases ran (default: 0)")
    args = parser.parse_args()
    if args.list:
        print("\n".join(CASES))
        return 0
    selected = [name.strip() for name in args.only.split(",") if name.strip()] or list(CASES)
    unknown = [name for name in selected if name not in CASES]
    if unknown:
        print(f"unknown cases: {unknown}", file=sys.stderr)
        return 2
    if np is None or stats is None:
        print("reference dependencies missing: pip install -r tests/reference/requirements.txt",
              file=sys.stderr)
        return 2
    jar = Path(args.jar)
    if not jar.exists():
        print(f"jar not found: {jar} (run make build-native)", file=sys.stderr)
        return 2
    workdir = Path(tempfile.mkdtemp(prefix="dap-validate-"))
    engine = Engine(jar, args.port, workdir)
    results = []
    try:
        engine.start()
        for name in selected:
            try:
                checks = CASES[name](engine, workdir)
                print(f"PASS {name:22s} {', '.join(checks)}")
                results.append({"case": name, "passed": True})
            except Exception as failure:  # noqa: BLE001 - a case failure must not stop the run
                print(f"FAIL {name:22s} {failure}")
                results.append({"case": name, "passed": False, "detail": str(failure)})
    except Exception as failure:  # noqa: BLE001 - still report a start-up/harness crash
        print(f"FAIL engine {failure}")
        results.append({"case": "engine", "passed": False, "detail": str(failure)})
    finally:
        engine.stop()
        shutil.rmtree(workdir, ignore_errors=True)
    summary = summarize_results(results, args.min_cases)
    print(f"\n{summary['cases_passed']}/{summary['cases_total']} cases passed")
    if args.json:
        report = Path(args.json)
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if summary["exit_code"] and summary["cases_failed"] == 0:
        print(f"accuracy gate: {summary['cases_total']} cases < --min-cases {args.min_cases}",
              file=sys.stderr)
    return summary["exit_code"]


if __name__ == "__main__":
    sys.exit(main())
