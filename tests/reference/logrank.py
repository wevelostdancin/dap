#!/usr/bin/env python3
"""Independent reference for the dap.survival log-rank statistic and p-value.

Run from anywhere:

    /Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/logrank.py

Deterministic and offline. The primary oracle is lifelines 0.30.3:
``logrank_test`` for the two-group cases and ``multivariate_logrank_test`` for
the three-group case. The three-group case is additionally re-derived from the
Mantel-Haenszel definition with numpy/scipy (risk set = every observation with
time >= t, hypergeometric covariance accumulated over *all* event times) as a
second, independent check. DAP must match both to 1e-9.

The datasets below are frozen literals shared with SurvivalExecutorTest.
``event=1`` is an event, ``event=0`` is right-censoring. The three-group case
has censoring in more than one group and group ``c`` has no events at all.
"""

import json

import numpy as np
from lifelines.statistics import logrank_test, multivariate_logrank_test
from scipy.stats import chi2

# (a) two groups, no censoring, one tie at every time.
TWO = {
    "a": ([1.0, 2.0, 3.0, 4.0, 5.0], [1, 1, 1, 1, 1]),
    "b": ([2.0, 3.0, 4.0, 5.0, 6.0], [1, 1, 1, 1, 1]),
}

# (b) two groups, censoring in b at t=4 (the original unit-test vector).
TWO_CENSORED = {
    "a": ([1.0, 2.0, 3.0], [1, 1, 1]),
    "b": ([1.0, 2.0, 4.0], [1, 1, 0]),
}

# (c) three groups; c is censored at 1,2,3,5,7 and never fails. a/b fail at
# several shared event times so the off-diagonal covariance accumulates.
THREE = {
    "a": ([1.0, 2.0, 3.0, 4.0, 5.0], [1, 1, 1, 1, 0]),
    "b": ([1.0, 2.0, 3.0, 4.0, 6.0], [1, 1, 0, 1, 0]),
    "c": ([1.0, 2.0, 3.0, 5.0, 7.0], [0, 0, 0, 0, 0]),
}


def flat(dataset):
    durations = [t for times, _ in dataset.values() for t in times]
    events = [e for _, ev in dataset.values() for e in ev]
    return durations, events


def mantel_haenszel(dataset):
    """Mantel-Haenszel (O-E)' V^-1 (O-E) from the definition, first k-1 groups."""
    durations, events = flat(dataset)
    durations = np.asarray(durations, dtype=float)
    events = np.asarray(events, dtype=int)
    groups = np.asarray([g for g, (times, _) in dataset.items() for _ in times])
    levels = list(dataset)
    groups = np.asarray([levels.index(g) for g in groups], dtype=int)
    k = len(levels)

    observed = np.zeros(k)
    expected = np.zeros(k)
    variance = np.zeros((k, k))
    for at_time in sorted(set(durations[events == 1])):
        n_j = np.array([np.count_nonzero((groups == j) & (durations >= at_time))
                        for j in range(k)], dtype=float)
        d_j = np.array([np.count_nonzero((groups == j) & (durations == at_time)
                                                & (events == 1))
                        for j in range(k)], dtype=float)
        n = n_j.sum()
        d = d_j.sum()
        if n <= 1 or d == 0:
            continue
        factor = d * (n - d) / (n - 1.0)
        proportion = n_j / n
        observed += d_j
        expected += d * proportion
        variance += factor * (np.diag(proportion) - np.outer(proportion, proportion))

    difference = observed[:k - 1] - expected[:k - 1]
    reduced = variance[:k - 1, :k - 1]
    statistic = float(difference @ np.linalg.solve(reduced, difference))
    df = k - 1
    return {"statistic": statistic, "p": float(chi2.sf(statistic, df)), "df": df}


def lifelines_result(dataset, two_group):
    if two_group:
        (times_a, events_a), (times_b, events_b) = dataset.values()
        result = logrank_test(times_a, times_b, events_a, events_b)
        df = 1
    else:
        durations, events = flat(dataset)
        result = multivariate_logrank_test(durations, flat_groups(dataset), events)
        df = len(dataset) - 1
    return {"statistic": float(result.test_statistic), "p": float(result.p_value), "df": df}


def flat_groups(dataset):
    return [g for g, (times, _) in dataset.items() for _ in times]


def main():
    cases = {}
    for name, dataset, two_group in (
        ("two_groups", TWO, True),
        ("two_groups_censored", TWO_CENSORED, True),
        ("three_groups", THREE, False),
    ):
        reference = lifelines_result(dataset, two_group)
        if name == "three_groups":
            independent = mantel_haenszel(dataset)
            assert abs(reference["statistic"] - independent["statistic"]) < 1e-9
            assert abs(reference["p"] - independent["p"]) < 1e-9
            reference["numpy_check"] = {
                "statistic": independent["statistic"],
                "p": independent["p"],
                "abs_statistic_diff": abs(reference["statistic"] - independent["statistic"]),
                "abs_p_diff": abs(reference["p"] - independent["p"]),
            }
        cases[name] = reference
    print(json.dumps(cases, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
