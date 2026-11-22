package com.dap.nativeengine.explore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for the four confirmed /v1/explore defects (wave b4). */
class ExploreRegressionsTest {
    /** From tests/reference/explore_stats.py (statsmodels fdr_bh), tolerance 1e-9. */
    private static final List<Double> REFERENCE_ADJUSTED = List.of(
            0.001, 0.0025, 0.006666666666666667, 0.008749999999999999, 0.022,
            0.04, 0.05285714285714286, 0.06, 0.2333333333333333, 0.93);

    @TempDir
    Path dir;

    private final ExploreService service = new ExploreService();

    @Test
    void sampledQualityOutlierRatioUsesScannedRows() throws Exception {
        // 5M source rows; every fifth row is extreme, so any 100k-row quality scan
        // (DuckDB may hand out parallel chunks, not the first rows by i) holds 20k (20%).
        Path parquet = ExploreTestSupport.parquet(dir, "large_outliers.parquet", """
                SELECT CASE WHEN i % 5 = 0 THEN 1000.0 ELSE (i % 100)::DOUBLE END AS v
                FROM range(5000000) AS t(i)""");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertTrue(result.qualitySampled(), "quality scans over 5M rows must be marked sampled");
        assertEquals(100_000L, result.qualityScannedRows());
        ExploreResult.Issue outliers = result.quality().issues().stream()
                .filter(issue -> "outliers".equals(issue.kind()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "outlier issue missing: " + result.quality().issues()));
        assertEquals(20_000L, outliers.count(), 100L, "parallel chunk boundaries shift a few rows");
        assertEquals("medium", outliers.severity());
        assertEquals(0.2, (double) outliers.count() / result.qualityScannedRows(), 1e-3,
                "reference: tests/reference/explore_stats.py -> outlier_ratio.expected");

        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) result.toMap();
        assertEquals(true, view.get("quality_sampled"));
        assertEquals(100_000L, view.get("quality_scanned_rows"));
    }

    @Test
    void smallSampleHasNoHighHypothesisOrModelRecommendation() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "three_rows.parquet", """
                SELECT * FROM (VALUES (1.0, 1.0), (2.0, 2.0), (3.0, 3.0)) AS t(x, y)""");

        ExploreResult result = service.explore(parquet, null, 50, 3);

        ExploreResult.Relationship pair = result.relationships().get(0);
        assertEquals(3L, pair.n());
        assertEquals(1.0, pair.value(), 1e-9, "the old code called this 'very strong' / high");
        assertTrue(result.hypotheses().stream().noneMatch(h -> "high".equals(h.priority())),
                "n=3 must not yield a high hypothesis: " + result.hypotheses());
        assertFalse(result.recommendations().stream().anyMatch(text -> text.startsWith("Model ")),
                "modelling needs n>=20 and p<0.05: " + result.recommendations());
    }

    @Test
    void approximateDistinctIsFlaggedAndLowCardinalityIsRefined() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "distinct.parquet",
                "SELECT i % 50000 AS many, i % 5 AS few FROM range(200000) AS t(i)");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        ExploreResult.Column many = ExploreTestSupport.column(result, "many");
        assertFalse(many.distinctExact(), "50k categories stay a sketch estimate");
        // DuckDB's sketch has a few percent error at this scale; the point is that the
        // estimate is reported (and flagged), not that it is exact.
        assertEquals(50_000L, many.distinct(), 10_000L, "sketch estimate should stay near 50k");

        ExploreResult.Column few = ExploreTestSupport.column(result, "few");
        assertTrue(few.distinctExact());
        assertEquals(5L, few.distinct());

        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) ((List<?>) result.toMap().get("columns")).get(0);
        assertEquals(false, view.get("distinct_exact"));
    }

    @Test
    void benjaminiHochbergMatchesStatsmodelsReference() {
        List<Double> reference = List.of(0.0001, 0.0005, 0.002, 0.0035, 0.011,
                0.024, 0.037, 0.048, 0.21, 0.93);

        List<Double> adjusted = ExploreService.benjaminiHochberg(reference);

        assertEquals(reference.size(), adjusted.size());
        for (int index = 0; index < REFERENCE_ADJUSTED.size(); index++) {
            assertEquals(REFERENCE_ADJUSTED.get(index), adjusted.get(index), 1e-9,
                    "BH mismatch at index " + index);
        }
    }

    @Test
    void groupDifferencesCarryMonotoneAdjustedPValues() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "groups.parquet", """
                SELECT i AS n1, i * 2 AS n2, i * 3 AS n3, i * 4 AS n4,
                       CAST(i % 2 AS VARCHAR) AS c1, CAST(i % 3 AS VARCHAR) AS c2,
                       CAST(i % 5 AS VARCHAR) AS c3
                FROM range(30) AS t(i)""");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertFalse(result.groupDifferences().isEmpty());
        for (ExploreResult.GroupDifference difference : result.groupDifferences()) {
            assertNotNull(difference.pAdjusted());
            assertTrue(difference.pAdjusted() >= difference.pValue() - 1e-12,
                    "adjustment must not decrease a p-value: " + difference);
            assertTrue(difference.pAdjusted() <= 1.0);
        }
        for (int index = 1; index < result.groupDifferences().size(); index++) {
            assertTrue(result.groupDifferences().get(index - 1).pAdjusted()
                            <= result.groupDifferences().get(index).pAdjusted() + 1e-12,
                    "group differences must be ordered by adjusted p");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) ((List<?>) result.toMap()
                .get("group_differences")).get(0);
        assertNotNull(view.get("p_adjusted"));
    }
}
