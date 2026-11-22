#!/usr/bin/env python3
"""Independent reference for Kendall tau-b p-values (scipy asymptotic method).

Run from the repository root:

    /Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/kendall_pvalue.py

Deterministic and offline. ``tau`` and ``p_value`` come from
``scipy.stats.kendalltau(x, y, method='asymptotic')``: tau-b with the usual tie
correlation and the tie-corrected asymptotic variance of concordant minus
discordant pairs (scipy/stats/_stats_py.py, ``_kendalltau``: v0/vt/vu/v1/v2 ->
``var`` -> ``z``). The printed ``z``/``standard_error`` are the equivalent
tau-b scale values used by DAP: se = sqrt(var / ((tot-xtie)*(tot-ytie))),
z = tau/se, p = 2*(1 - Phi(|z|)). ``ci_low``/``ci_high`` use alpha=0.05 and are
clipped to [-1, 1]. These are the numbers asserted by CorrelationExecutorTest
(tolerance 1e-9). DAP never computes the exact permutation p-value.

Data provenance: n=100 y is round(x+U(-120,120), 6) from random.Random(42);
n=50 y is a 3-label series from random.Random(1) with score = i+U(-40,40) and
cutoffs 20/35. Both are frozen below as literals shared with the Java test.
"""

import json
import math
from collections import Counter

from scipy.stats import kendalltau, norm

# (a) The original n=4 unit-test vector (one tie in y).
X4 = [1.0, 2.0, 3.0, 4.0]
Y4 = [1.0, 2.0, 2.0, 3.0]

# (a2) n=4 without ties: scipy's default 'auto' would use the exact p-value;
# the reference deliberately requests the asymptotic method DAP implements.
X4_NO_TIES = [1.0, 2.0, 3.0, 4.0]
Y4_NO_TIES = [1.0, 2.0, 4.0, 3.0]

# (b) n=100, tau ~= 0.3, frozen deterministic noise, no ties.
X100 = [float(i) for i in range(1, 101)]
Y100 = [
    34.462432, -111.997419, -50.992964, -62.429423, 61.753091,
    48.407877, 101.123096, -91.13468, -9.738763, -102.848667,
    -56.526886, 13.285269, -100.631367, -58.278964, 50.972265,
    26.785955, -50.094251, 39.423764, 93.26331, -98.440298,
    94.39662, 69.553455, -15.339876, -58.68492, 134.731137,
    -13.217309, -70.740998, -68.78807, 112.398648, 54.894248,
    104.710786, 87.135629, 41.694742, 147.547783, 5.848251,
    48.489752, 116.057119, 66.444741, 125.809656, 58.564515,
    90.097241, -67.002148, -22.304414, -6.546889, -55.849926,
    -18.130187, -48.759657, -5.286335, 81.564267, 17.559723,
    19.843432, -17.718313, -2.925323, 158.797101, 90.528492,
    82.191441, -21.926724, 112.990432, -21.783401, 31.069306,
    178.485604, 95.599942, 76.667939, 108.30742, 147.284461,
    132.239979, 1.971537, -44.295941, 24.708732, 14.25781,
    1.635882, 178.298331, 163.32823, 29.522691, 112.30528,
    50.951656, 176.491422, 68.124445, 22.57124, 19.190602,
    95.728352, 25.057986, 103.300638, 179.477492, 60.856121,
    18.636982, 206.409026, 90.28631, -9.181741, -18.69207,
    -2.684209, 122.58705, 163.099047, 75.318392, -9.753351,
    67.588629, 216.069131, 104.987443, 212.058811, 186.587129,
]

# (c) n=10 with ties in both columns.
X10_TIES = [1.0, 1.0, 2.0, 2.0, 3.0, 4.0, 4.0, 5.0, 6.0, 6.0]
Y10_TIES = [2.0, 3.0, 1.0, 3.0, 5.0, 4.0, 4.0, 2.0, 6.0, 5.0]

# (d) n=50, y has only three distinct values (strong ties).
X50 = [float(i) for i in range(1, 51)]
Y50 = [
    0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0,
    2.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 2.0, 2.0, 0.0,
    0.0, 1.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0,
    0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 2.0, 2.0, 0.0,
    2.0, 2.0, 0.0, 1.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0,
]


def tie_stats(values):
    """(sum t(t-1)/2, sum t(t-1)(t-2), sum t(t-1)(2t+5)) over tie groups."""
    counts = Counter(values)
    return (
        sum(c * (c - 1) // 2 for c in counts.values()),
        sum(c * (c - 1) * (c - 2) for c in counts.values()),
        sum(c * (c - 1) * (2 * c + 5) for c in counts.values()),
    )


def reference(x, y):
    n = len(x)
    result = kendalltau(x, y, method="asymptotic")
    tau = float(result.statistic)
    p = float(result.pvalue)
    m = n * (n - 1.0)
    tot = m / 2.0
    xtie, x0, x1 = tie_stats(x)
    ytie, y0, y1 = tie_stats(y)
    # scipy/stats/_stats_py.py::_kendalltau (v0, vt, vu -> v1, v2 -> var).
    var = ((m * (2 * n + 5) - x1 - y1) / 18.0
           + (2.0 * xtie * ytie) / m
           + x0 * y0 / (9.0 * m * (n - 2)))
    se = math.sqrt(var / ((tot - xtie) * (tot - ytie)))
    z = tau / se
    crit = float(norm.ppf(0.975))
    return {
        "n": n,
        "tau": tau,
        "p_value": p,
        "z": z,
        "z_from_scipy_p": float(norm.isf(p / 2.0)),
        "standard_error": se,
        "ci_low": max(-1.0, tau - crit * se),
        "ci_high": min(1.0, tau + crit * se),
        "abs_z_minus_abs_tau_over_se": abs(abs(z) - abs(tau) / se),
    }


def main():
    cases = {
        "n4": reference(X4, Y4),
        "n4_no_ties": reference(X4_NO_TIES, Y4_NO_TIES),
        "n100": reference(X100, Y100),
        "n10_ties": reference(X10_TIES, Y10_TIES),
        "n50_three_labels": reference(X50, Y50),
    }
    print(json.dumps(cases, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
