#!/usr/bin/env python3
"""Independent reference for two /v1/explore statistics.

Run from the repository root:

    /Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/explore_stats.py

Deterministic and offline. ``adjusted`` is the Benjamini-Hochberg FDR correction of
``p_values`` from statsmodels (``multipletests(method='fdr_bh')``). The JUnit test
``ExploreRegressionsTest.benjaminiHochbergMatchesStatsmodelsReference`` compares all
ten values with tolerance 1e-9. ``outlier_ratio`` is the share of outliers in the
first 100k rows scanned by explore's quality pass: 20k extreme rows out of 100k
give 0.2, while dividing by all 5M source rows would wrongly give 0.004.
The Java fixture spreads extremes every fifth row so the ratio holds for any 100k chunk.
"""

import json

from statsmodels.stats.multitest import multipletests

# Frozen list shared with the Java test; deliberately includes a mix of significant
# and insignificant p-values and a p-value near the 0.05 threshold.
P_VALUES = [0.0001, 0.0005, 0.002, 0.0035, 0.011, 0.024, 0.037, 0.048, 0.21, 0.93]


def main():
    _, adjusted, _, _ = multipletests(P_VALUES, alpha=0.05, method="fdr_bh")
    print(json.dumps({
        "p_values": P_VALUES,
        "adjusted": [float(value) for value in adjusted],
        "outlier_ratio": {
            "quality_scanned_rows": 100_000,
            "outlier_rows": 20_000,
            "source_rows": 5_000_000,
            "expected": 0.2,
            "full_source_divisor_would_be": 0.004,
        },
    }, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
