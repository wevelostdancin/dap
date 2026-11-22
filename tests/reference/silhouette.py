#!/usr/bin/env python3
"""Independent reference for dap.cluster silhouette (scikit-learn 1.9.1).

Run from the repository root:

    /Users/vitalypesnev/Projects/dap/build/refvenv/bin/python tests/reference/silhouette.py

Deterministic and offline. Per-point values come from
``sklearn.metrics.silhouette_samples``; the mean comes from
``sklearn.metrics.silhouette_score``. Both use the default Euclidean metric,
which matches ``StatsSupport.distance`` in DAP.

The frozen datasets are shared with ClusterExecutorTest (tolerance 1e-9):

  (a) one isolated point at (0,0) with labels [0,1,1,1]: a singleton cluster.
      scikit-learn sets s = 0 for singleton clusters (Rousseeuw; documented in
      silhouette_samples: "The silhouette is set to zero for singleton
      clusters"). This is the value DAP must produce instead of 1.0.
  (b) two clusters with duplicate feature vectors: duplicates are legitimate
      rows and take part in the mean like any other point.
  (c) three well separated clusters of three points each: the exact per-point
      and mean values asserted by ClusterExecutorTest at 1e-9.
  (d) all points in one cluster: silhouette is undefined; scikit-learn raises
      ValueError, DAP emits NULL for every row.

DAP computes silhouette on the *raw* points when standardize=false, so the
coordinates below match the Java test inputs exactly. DAP's output rows are
sorted lexicographically by feature vector and deduplicated by feature vector,
which does not change the per-point silhouette values.
"""

import json

from sklearn.metrics import silhouette_samples, silhouette_score

# (a) singleton: the isolated point (0,0) is the only member of label 0.
SINGLETON_X = [[0.0, 0.0], [10.0, 0.0], [10.0, 1.0], [11.0, 0.0]]
SINGLETON_LABELS = [0, 1, 1, 1]

# (b) two clusters with duplicates.
DUPLICATES_X = [
    [0.0, 0.0], [0.0, 0.0], [1.0, 0.0],
    [10.0, 0.0], [10.0, 0.0], [11.0, 0.0],
]
DUPLICATES_LABELS = [0, 0, 0, 1, 1, 1]

# (c) three well separated clusters of three points each. Input order matches
# the Java test (ids 1..9); DAP's k-means (seed 42) yields exactly this partition,
# up to label numbering (silhouette is invariant under label renaming).
THREE_CLUSTERS_X = [
    [0.0, 0.0], [0.0, 1.0], [1.0, 0.0],
    [10.0, 0.0], [10.0, 1.0], [11.0, 0.0],
    [0.0, 10.0], [1.0, 10.0], [0.0, 11.0],
]
THREE_CLUSTERS_LABELS = [0, 0, 0, 1, 1, 1, 2, 2, 2]

# (d) one cluster only (silhouette undefined).
ONE_CLUSTER_X = [[0.0, 0.0], [1.0, 0.0], [0.0, 1.0]]
ONE_CLUSTER_LABELS = [0, 0, 0]


def defined_case(x, labels):
    samples = [float(value) for value in silhouette_samples(x, labels)]
    return {
        "labels": labels,
        "samples": samples,
        "mean": float(silhouette_score(x, labels)),
        "mean_of_samples": sum(samples) / len(samples),
    }


def undefined_case(x, labels):
    try:
        samples = [float(value) for value in silhouette_samples(x, labels)]
        mean = float(silhouette_score(x, labels))
        error = None
    except ValueError as exc:
        samples = None
        mean = None
        error = str(exc)
    return {
        "labels": labels,
        "samples": samples,
        "mean": mean,
        "error": error,
        "dap_behavior": "all silhouettes NULL (fewer than two clusters)",
    }


def main():
    cases = {
        "singleton": {
            **defined_case(SINGLETON_X, SINGLETON_LABELS),
            "singleton_sample": float(silhouette_samples(SINGLETON_X, SINGLETON_LABELS)[0]),
            "rule": "scikit-learn sets s = 0 for singleton clusters",
        },
        "two_clusters_with_duplicates": defined_case(DUPLICATES_X, DUPLICATES_LABELS),
        "three_clusters": defined_case(THREE_CLUSTERS_X, THREE_CLUSTERS_LABELS),
        "one_cluster_undefined": undefined_case(ONE_CLUSTER_X, ONE_CLUSTER_LABELS),
    }
    print(json.dumps(cases, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
