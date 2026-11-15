package com.dap.nativeengine.explore;

import com.dap.nativeengine.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 5.1: profile and quality scan of the explore service. */
class ExploreServiceTest {
    @TempDir
    Path dir;

    private final ExploreService service = new ExploreService();

    @Test
    void irisProfileCountsRowsColumnsAndDistinctValues() throws Exception {
        ExploreResult result = service.explore(ExploreTestSupport.iris(dir), null,
                ExploreService.DEFAULT_MAX_COLUMNS, ExploreService.DEFAULT_SAMPLE_ROWS);

        assertEquals(150L, result.rows());
        assertEquals(List.of("sepal_length", "sepal_width", "petal_length", "petal_width", "species"),
                result.columns().stream().map(ExploreResult.Column::name).toList());

        ExploreResult.Column petal = ExploreTestSupport.column(result, "petal_length");
        assertEquals("Float64", petal.type());
        assertEquals(0L, petal.missing());
        assertEquals(43L, petal.distinct());
        assertEquals(1.0, petal.min(), 1e-9);
        assertEquals(6.9, petal.max(), 1e-9);
        assertEquals(3.758, petal.mean(), 1e-3);
        assertEquals(1.76442, petal.stddev(), 1e-4);
        assertEquals(0L, petal.zeros());
        assertEquals(0L, petal.negatives());

        ExploreResult.Column species = ExploreTestSupport.column(result, "species");
        assertEquals("Utf8", species.type());
        assertEquals(3L, species.distinct());
        assertEquals(0L, species.missing());
        assertNull(species.mean(), "non-numeric columns carry no mean");
        assertNull(species.stddev(), "non-numeric columns carry no stddev");
    }

    @Test
    void qualityScoreIsBoundedAndIrisHasNoStructuralIssues() throws Exception {
        ExploreResult result = service.explore(ExploreTestSupport.iris(dir), null, 50, 1000);

        assertTrue(result.quality().score() >= 0 && result.quality().score() <= 100,
                "score out of range: " + result.quality().score());
        assertNotNull(result.quality().issues());
        for (ExploreResult.Issue issue : result.quality().issues()) {
            assertTrue(List.of("high", "medium", "low").contains(issue.severity()),
                    "unexpected severity: " + issue.severity());
            assertNotNull(issue.kind());
            assertNotNull(issue.detail());
        }
        assertFalse(result.quality().issues().stream().anyMatch(issue -> "empty".equals(issue.kind())),
                "iris has no empty column");
        assertFalse(result.quality().issues().stream().anyMatch(issue -> "constant".equals(issue.kind())),
                "iris has no constant column");
        // The classic iris fixture has three repeated rows; they are reported but do not
        // count as a structural problem.
        assertEquals(3L, result.quality().issues().stream()
                .filter(issue -> "duplicate_rows".equals(issue.kind()))
                .map(ExploreResult.Issue::count)
                .findFirst().orElseThrow(), "three repeated iris rows expected");
    }

    @Test
    void duplicateConstantAndEmptyColumnsAreReported() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "quality.parquet", """
                SELECT * FROM (VALUES
                    (1, 7, CAST(NULL AS VARCHAR), CAST(NULL AS DOUBLE), 1.0),
                    (1, 7, NULL, NULL, 1.0),
                    (2, 7, NULL, NULL, 2.0),
                    (2, 7, NULL, NULL, 3.0)) AS t(id, const_col, empty_col, empty_num, val)""");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertEquals(4L, result.rows());
        assertTrue(result.quality().issues().stream().anyMatch(issue ->
                        "duplicate_rows".equals(issue.kind()) && issue.count() == 1L),
                "one repeated row expected: " + result.quality().issues());
        assertTrue(result.quality().issues().stream().anyMatch(issue ->
                        "constant".equals(issue.kind()) && "const_col".equals(issue.column())),
                "constant column expected: " + result.quality().issues());
        assertTrue(result.quality().issues().stream().anyMatch(issue ->
                        "empty".equals(issue.kind()) && "empty_col".equals(issue.column())),
                "empty text column expected: " + result.quality().issues());
        assertTrue(result.quality().issues().stream().anyMatch(issue ->
                        "empty".equals(issue.kind()) && "empty_num".equals(issue.column())),
                "empty numeric column expected: " + result.quality().issues());
        assertNull(ExploreTestSupport.column(result, "empty_num").min());
        assertNull(ExploreTestSupport.column(result, "empty_num").mean());
        assertTrue(result.quality().score() < 100, "structural issues must lower the score");
    }

    @Test
    void highCardinalityColumnIsFlagged() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "ids.parquet",
                "SELECT i AS id, i * 2 AS value FROM range(30) AS t(i)");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertTrue(result.quality().issues().stream().anyMatch(issue ->
                        "high_cardinality".equals(issue.kind()) && "id".equals(issue.column())),
                "id column expected to be flagged: " + result.quality().issues());
    }

    @Test
    void iqrOutliersAreCounted() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "outliers.parquet",
                "SELECT i AS v FROM range(1, 21) AS t(i) UNION ALL SELECT 1000");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertEquals(1L, ExploreTestSupport.column(result, "v").outliers());
        assertTrue(result.quality().issues().stream().anyMatch(issue ->
                        "outliers".equals(issue.kind()) && issue.count() == 1L),
                "outlier issue expected: " + result.quality().issues());
    }

    @Test
    void zerosNegativesAndSampleStddevAreReported() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "signed.parquet",
                "SELECT * FROM (VALUES (-2.0), (-1.0), (0.0), (0.0), (1.0), (2.0)) AS t(v)");

        ExploreResult result = service.explore(parquet, null, 50, 1000);
        ExploreResult.Column column = ExploreTestSupport.column(result, "v");

        assertEquals(2L, column.zeros());
        assertEquals(2L, column.negatives());
        assertEquals(0.0, column.mean(), 1e-12);
        assertEquals(Math.sqrt(2.0), column.stddev(), 1e-9);
    }

    @Test
    void sqlQueryIsProfiledOverTheSourceView() throws Exception {
        ExploreResult result = service.explore(ExploreTestSupport.iris(dir),
                "SELECT petal_length * 2 AS pl2, species FROM src", 50, 1000);

        assertEquals(150L, result.rows());
        assertEquals(List.of("pl2", "species"),
                result.columns().stream().map(ExploreResult.Column::name).toList());
        assertEquals(7.517, ExploreTestSupport.column(result, "pl2").mean(), 1e-3);
    }

    @Test
    void maxColumnsCapsTheProfile() throws Exception {
        ExploreResult result = service.explore(ExploreTestSupport.iris(dir), null, 2, 1000);

        assertEquals(2, result.columns().size());
        assertEquals(List.of("sepal_length", "sepal_width"),
                result.columns().stream().map(ExploreResult.Column::name).toList());
    }

    @Test
    void emptySourceIsProfiledWithoutFindings() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "empty.parquet",
                "SELECT CAST(1 AS BIGINT) AS a WHERE 1 = 0");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertEquals(0L, result.rows());
        assertEquals(1, result.columns().size());
        assertEquals(100, result.quality().score());
        assertTrue(result.relationships().isEmpty());
        assertTrue(result.groupDifferences().isEmpty());
        assertTrue(result.hypotheses().isEmpty());
        assertTrue(result.recommendations().isEmpty());
        assertNull(result.time());
    }

    @Test
    void invalidSqlIsRejectedAsBadRequest() {
        ApiException failure = assertThrows(ApiException.class, () ->
                service.explore(null, "DROP TABLE src", 50, 1000));

        assertEquals(400, failure.status());
        assertEquals("invalid_sql", failure.code());
    }

    @Test
    void missingSourceAndSqlAreRejected() {
        ApiException failure = assertThrows(ApiException.class,
                () -> service.explore(null, null, 50, 1000));

        assertEquals(400, failure.status());
    }

    @Test
    void missingParquetIsReportedAsDatasetNotFound() {
        ApiException failure = assertThrows(ApiException.class,
                () -> service.explore(dir.resolve("absent.parquet"), null, 50, 1000));

        assertEquals(404, failure.status());
        assertEquals("dataset_not_found", failure.code());
    }

    @Test
    void invalidLimitsAreRejected() throws Exception {
        Path parquet = ExploreTestSupport.iris(dir);

        assertEquals(400, assertThrows(ApiException.class,
                () -> service.explore(parquet, null, 0, 1000)).status());
        assertEquals(400, assertThrows(ApiException.class,
                () -> service.explore(parquet, null, 50, 0)).status());
    }
}