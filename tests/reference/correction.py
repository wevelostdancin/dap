#!/usr/bin/env python3
"""Independent reference for dap.correlation p_adjusted (multiple comparisons).

Run from the repository root:

    /Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/correction.py

Deterministic and offline. Five numeric columns over 15 rows (the exact vectors used by
CorrelationExecutorTest) are pearson-correlated with scipy.stats.pearsonr; the ten raw
p-values are then adjusted with statsmodels.stats.multitest.multipletests for
bonferroni, holm and fdr_bh (DAP calls the latter "bh"). ``none`` is the identity and
needs no reference. The printed p_value/p_adjusted arrays are asserted by the JUnit
test with tolerance 1e-9 and are paired in output order: a|b, a|c, ... , d|e.
"""

import json

from scipy.stats import pearsonr
from statsmodels.stats.multitest import multipletests

N = 15
I = list(range(N))
A = [float(i + 1) for i in I]
B = [A[i] + n for i, n in enumerate(
    [5, -3, 6, -4, 2, -6, 4, 0, 2, -2, 4, -4, 6, -2, 0])]
C = [-A[i] + n for i, n in enumerate(
    [2, -4, 4, -2, 6, 0, -4, 2, -2, 4, -6, 2, 4, -4, 0])]
D = [float((i * 7) % N + 1) for i in I]
E = [float((i * 4) % N + 1) + 0.5 * (i % 3) for i in I]
COLUMNS = {"a": A, "b": B, "c": C, "d": D, "e": E}
PAIRS = [
    ("a", "b"), ("a", "c"), ("a", "d"), ("a", "e"),
    ("b", "c"), ("b", "d"), ("b", "e"),
    ("c", "d"), ("c", "e"),
    ("d", "e"),
]


def main():
    p_values = [float(pearsonr(COLUMNS[first], COLUMNS[second]).pvalue)
                for first, second in PAIRS]
    adjusted = {}
    for method in ("bonferroni", "holm", "fdr_bh"):
        _, p_corrected, _, _ = multipletests(p_values, alpha=0.05, method=method)
        adjusted[method] = [float(value) for value in p_corrected]
    print(json.dumps({
        "pairs": [first + "|" + second for first, second in PAIRS],
        "p_value": p_values,
        "p_adjusted": adjusted,
    }, indent=2))


if __name__ == "__main__":
    main()
