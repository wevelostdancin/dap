#!/usr/bin/env python3
"""Independent reference for the chi-square test of independence.

Run from the repository root:

    /Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/chi_square.py

Deterministic and offline. chi2, df and p come from
``scipy.stats.chi2_contingency(table, correction=False)``; Cramer's V is
``sqrt(chi2 / (n * min(r - 1, c - 1)))`` with n the sum of all cells. These are
the numbers asserted by HypothesisTestExecutorTest (tolerance 1e-9). DAP never
applies Yates' correction and never drops unobserved cells: the statistic runs
over the full Cartesian product of the row and column categories, so a missing
cell contributes its expected frequency E to the sum.

A zero row or column gives a zero expected frequency; scipy rejects that with
``ValueError: The internally computed table of expected frequencies has a zero
element``. DAP cannot form such a table at all: categories are discovered from
the observed pairs, so an empty row/column is simply absent from the input.
"""

import json

from scipy.stats import chi2_contingency

# Dense 2x2 (all four cells observed): the existing JUnit case.
DENSE_2X2 = [[10, 20], [20, 10]]

# Sparse 2x2: (a,g1)=10 and (b,g2)=10, the other two cells are unobserved.
# Summing only observed cells would give 10; the full product gives 20.
SPARSE_2X2 = [[10, 0], [0, 10]]

# Sparse 3x3: six observed cells of 5 and three unobserved cells.
SPARSE_3X3 = [[5, 0, 5], [0, 5, 5], [5, 5, 0]]

# Degenerate 2x3 tables: a zero margin makes every expected frequency in that
# row/column zero, so scipy refuses to compute the statistic.
ZERO_ROW_2X3 = [[5, 5, 10], [0, 0, 0]]
ZERO_COLUMN_2X3 = [[5, 0, 5], [5, 0, 5]]


def reference(table):
    chi2, p, dof, expected = chi2_contingency(table, correction=False)
    n = sum(sum(row) for row in table)
    cramers_v = (chi2 / (n * min(len(table) - 1, len(table[0]) - 1))) ** 0.5
    return {
        "table": table,
        "chi2": float(chi2),
        "df": int(dof),
        "p_value": float(p),
        "cramers_v": float(cramers_v),
        "expected": expected.tolist(),
        "n": n,
    }


def main():
    cases = {
        "dense_2x2": reference(DENSE_2X2),
        "sparse_2x2": reference(SPARSE_2X2),
        "sparse_3x3": reference(SPARSE_3X3),
    }
    for name, table in (("zero_row_2x3", ZERO_ROW_2X3),
                        ("zero_column_2x3", ZERO_COLUMN_2X3)):
        try:
            cases[name] = reference(table)
        except ValueError as error:
            cases[name] = {"table": table, "error": f"ValueError: {error}"}
    print(json.dumps(cases, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
