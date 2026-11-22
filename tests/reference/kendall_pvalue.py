#!/usr/bin/env python3
"""Independent reference for Kendall tau-b p-values (normal approximation).

Run from the repository root:

    /Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/kendall_pvalue.py

Deterministic and offline. ``tau`` comes from scipy.stats.kendalltau (tau-b with
the usual tie correction); ``p_value`` is the two-sided normal tail of
z = 3*tau*sqrt(n(n-1)) / sqrt(2(2n+5)), i.e. p = 2*(1 - Phi(|z|)), evaluated
with the numerically equivalent survival function. These are the numbers the
JUnit tests in CorrelationExecutorTest assert, with tolerance 1e-9.

Data provenance: the n=100 series is x=1..100 and y=round(x+U(-120,120), 6)
from random.Random(42); the resulting y values are frozen below so the script
and the Java test consume bit-identical inputs. Other vectors are literal.
"""

import json
import math

from scipy.stats import kendalltau, norm

# (a) The original n=4 unit-test vector (one tie in y).
X4 = [1.0, 2.0, 3.0, 4.0]
Y4 = [1.0, 2.0, 2.0, 3.0]

# (b) n=100, tau ~= 0.3, frozen deterministic noise.
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

# (c) n=10 with ties in both x and y.
X10_TIES = [1.0, 1.0, 2.0, 2.0, 3.0, 4.0, 4.0, 5.0, 6.0, 6.0]
Y10_TIES = [2.0, 3.0, 1.0, 3.0, 5.0, 4.0, 4.0, 2.0, 6.0, 5.0]


def reference(x, y):
    n = len(x)
    tau = float(kendalltau(x, y).statistic)
    z = 3.0 * tau * math.sqrt(n * (n - 1.0)) / math.sqrt(2.0 * (2.0 * n + 5.0))
    se = math.sqrt(2.0 * (2.0 * n + 5.0) / (9.0 * n * (n - 1.0)))
    return {
        "n": n,
        "tau": tau,
        "z": z,
        "standard_error": se,
        "p_value": 2.0 * norm.sf(abs(z)),
        "abs_z_minus_abs_tau_over_se": abs(abs(z) - abs(tau) / se),
    }


def main():
    cases = {
        "n4": reference(X4, Y4),
        "n100": reference(X100, Y100),
        "n10_ties": reference(X10_TIES, Y10_TIES),
    }
    print(json.dumps(cases, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
