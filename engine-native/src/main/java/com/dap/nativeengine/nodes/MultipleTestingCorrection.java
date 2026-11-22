package com.dap.nativeengine.nodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Multiple-comparison adjustment shared by {@link HypothesisTestExecutor} and
 * {@link CorrelationExecutor}. The three methods match
 * {@code statsmodels.stats.multitest.multipletests} ({@code bonferroni}, {@code holm},
 * {@code fdr_bh}); {@code none} copies the p-value unchanged. Null p-values stay null and
 * every result keeps the position of its input p-value.
 */
public final class MultipleTestingCorrection {
    private static final List<String> METHODS = List.of("none", "bonferroni", "holm", "bh");

    private MultipleTestingCorrection() {
    }

    /** Normalise and validate a correction name, naming the calling node in errors. */
    public static String validate(String correction, String node) {
        String value = correction == null ? "none" : correction.trim().toLowerCase(Locale.ROOT);
        if (!METHODS.contains(value)) {
            throw new IllegalArgumentException(node + ": unknown correction '" + correction
                    + "' (expected none, bonferroni, holm or bh)");
        }
        return value;
    }

    /** Adjusted p-values in input order; a null input p-value stays null. */
    public static Double[] adjust(List<Double> pValues, String correction) {
        Double[] result = new Double[pValues.size()];
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < pValues.size(); i++) {
            result[i] = pValues.get(i);
            if (pValues.get(i) != null) {
                valid.add(i);
            }
        }
        int m = valid.size();
        if ("none".equals(correction) || m == 0) {
            return result;
        }
        valid.sort(Comparator.comparingDouble(index -> pValues.get(index)));
        if ("bonferroni".equals(correction)) {
            for (int index : valid) {
                result[index] = Math.min(1.0, pValues.get(index) * m);
            }
            return result;
        }
        if ("holm".equals(correction)) {
            double previous = 0.0;
            for (int rank = 0; rank < m; rank++) {
                int index = valid.get(rank);
                double adjusted = Math.min(1.0, pValues.get(index) * (m - rank));
                adjusted = Math.max(adjusted, previous);
                previous = adjusted;
                result[index] = adjusted;
            }
            return result;
        }
        // Benjamini-Hochberg: step up, enforcing monotonicity from the largest p-value.
        double next = 1.0;
        for (int rank = m - 1; rank >= 0; rank--) {
            int index = valid.get(rank);
            double adjusted = Math.min(1.0, pValues.get(index) * m / (rank + 1.0));
            adjusted = Math.min(adjusted, next);
            next = adjusted;
            result[index] = adjusted;
        }
        return result;
    }
}
