package com.dap.nativeengine.explore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 5.2: relationships, group differences, time span, hypotheses and recommendations. */
class ExploreRelationshipsTest {
    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");

    @TempDir
    Path dir;

    private final ExploreService service = new ExploreService();

    private ExploreResult iris() throws Exception {
        return service.explore(ExploreTestSupport.iris(dir), null, 50, 1000);
    }

    private static ExploreResult.Relationship relationship(ExploreResult result,
                                                           String left, String right) {
        return result.relationships().stream()
                .filter(pair -> pair.left().equals(left) && pair.right().equals(right))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing relationship " + left + "/" + right + ": " + result.relationships()));
    }

    private static ExploreResult.GroupDifference difference(ExploreResult result,
                                                            String value, String group) {
        return result.groupDifferences().stream()
                .filter(pair -> pair.value().equals(value) && pair.group().equals(group))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing group difference " + value + "/"
                        + group + ": " + result.groupDifferences()));
    }

    @Test
    void irisNumericPairsCarryPearsonRAndPValue() throws Exception {
        ExploreResult.Relationship pair = relationship(iris(), "petal_length", "petal_width");

        assertEquals("pearson", pair.method());
        assertTrue(pair.value() > 0.9, "r must exceed 0.9, got " + pair.value());
        assertNotNull(pair.pValue());
        assertTrue(pair.pValue() < 1e-10, "p must be tiny, got " + pair.pValue());
        assertEquals(150L, pair.n());
        assertEquals("very strong", pair.strength());
    }

    @Test
    void relationshipsAreSortedByAbsoluteValueThenNames() throws Exception {
        List<ExploreResult.Relationship> pairs = iris().relationships();

        assertFalse(pairs.isEmpty());
        for (int index = 1; index < pairs.size(); index++) {
            ExploreResult.Relationship previous = pairs.get(index - 1);
            ExploreResult.Relationship current = pairs.get(index);
            double previousValue = Math.abs(previous.value());
            double currentValue = Math.abs(current.value());
            assertTrue(previousValue > currentValue
                            || (previousValue == currentValue && previous.left().compareTo(current.left()) < 0)
                            || (previousValue == currentValue && previous.left().equals(current.left())
                            && previous.right().compareTo(current.right()) <= 0),
                    "relationships are not ordered at " + index + ": " + previous + " then " + current);
        }
        for (ExploreResult.Relationship pair : pairs) {
            assertTrue(Set.of("pearson", "cramers_v").contains(pair.method()),
                    "unexpected method: " + pair.method());
            assertTrue(Set.of("very strong", "strong", "moderate", "weak", "very weak")
                    .contains(pair.strength()), "unexpected strength: " + pair.strength());
        }
    }

    @Test
    void relationshipsAreCappedAtTwenty() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "wide.parquet", """
                SELECT i AS a, i + 1 AS b, i + 2 AS c, i + 3 AS d,
                       i + 4 AS e, i + 5 AS f, i + 6 AS g
                FROM range(10) AS t(i)""");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertEquals(20, result.relationships().size(),
                "seven numeric columns give 21 pairs, capped at 20");
    }

    @Test
    void speciesAnovaExplainsPetalLength() throws Exception {
        ExploreResult.GroupDifference difference = difference(iris(), "petal_length", "species");

        assertEquals("anova", difference.test());
        assertTrue(difference.statistic() > 100, "F must be large, got " + difference.statistic());
        assertTrue(difference.pValue() < 1e-6, "p must be tiny, got " + difference.pValue());
        assertNotNull(difference.effectSize());
        assertTrue(difference.effectSize() > 0.9,
                "eta squared must exceed 0.9, got " + difference.effectSize());
    }

    @Test
    void groupDifferencesAreSortedByPValueAndCapped() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "groups.parquet", """
                SELECT i AS n1, i * 2 AS n2, i * 3 AS n3, i * 4 AS n4,
                       CAST(i % 2 AS VARCHAR) AS c1,
                       CAST(i % 3 AS VARCHAR) AS c2,
                       CAST(i % 5 AS VARCHAR) AS c3
                FROM range(30) AS t(i)""");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertEquals(10, result.groupDifferences().size(),
                "four numeric times three categorical pairs are capped at 10");
        for (int index = 1; index < result.groupDifferences().size(); index++) {
            ExploreResult.GroupDifference previous = result.groupDifferences().get(index - 1);
            ExploreResult.GroupDifference current = result.groupDifferences().get(index);
            assertTrue(previous.pValue() < current.pValue()
                            || (previous.pValue() == current.pValue()
                            && previous.value().compareTo(current.value()) <= 0),
                    "group differences are not ordered by p: " + previous + " then " + current);
        }
    }

    @Test
    void categoricalPairsUseCramersVAndChiSquare() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "cats.parquet", """
                SELECT * FROM (VALUES
                    ('a', 'x'), ('a', 'x'), ('a', 'x'),
                    ('b', 'y'), ('b', 'y'), ('b', 'y'),
                    ('c', 'z'), ('c', 'z'), ('c', 'z')) AS t(left_cat, right_cat)""");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        ExploreResult.Relationship pair = relationship(result, "left_cat", "right_cat");
        assertEquals("cramers_v", pair.method());
        assertEquals(1.0, pair.value(), 1e-9);
        assertTrue(pair.pValue() < 0.01, "chi-square p must be small, got " + pair.pValue());
        assertEquals(9L, pair.n());
        assertEquals("very strong", pair.strength());
    }

    @Test
    void numericVersusCategoricalUsesKruskalForSmallGroups() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "small_groups.parquet", """
                SELECT * FROM (VALUES
                    ('a', 1.0), ('a', 2.0), ('a', 3.0),
                    ('b', 10.0), ('b', 11.0), ('b', 12.0),
                    ('c', 20.0), ('c', 21.0), ('c', 22.0)) AS t(g, v)""");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        ExploreResult.GroupDifference difference = difference(result, "v", "g");
        assertEquals("kruskal", difference.test(), "groups of three use Kruskal-Wallis");
        assertEquals(7.2, difference.statistic(), 1e-6);
        assertTrue(difference.pValue() > 0.01 && difference.pValue() < 0.05,
                "H=7.2 on two degrees of freedom gives p about 0.027, got " + difference.pValue());
    }

    @Test
    void relationshipCountsOnlyCompletePairs() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "nulls.parquet", """
                SELECT * FROM (VALUES
                    (1.0, 1.0), (2.0, NULL), (3.0, 3.0), (4.0, 4.0), (5.0, 5.0))
                    AS t(a, b)""");

        ExploreResult.Relationship pair = relationship(service.explore(parquet, null, 50, 1000), "a", "b");

        assertEquals(4L, pair.n(), "the NULL pair must be dropped");
        assertEquals(1.0, pair.value(), 1e-9);
    }

    @Test
    void hypothesesAreNonEmptyStableAndOrdered() throws Exception {
        ExploreResult first = iris();
        ExploreResult second = iris();

        assertEquals(first.hypotheses(), second.hypotheses(), "hypotheses must be stable");
        assertTrue(first.hypotheses().size() >= 3 && first.hypotheses().size() <= 5,
                "expected 3 to 5 hypotheses, got " + first.hypotheses().size());
        assertEquals(first.hypotheses().size(),
                first.hypotheses().stream().map(ExploreResult.Hypothesis::id).distinct().count(),
                "hypothesis ids must be unique");
        for (ExploreResult.Hypothesis hypothesis : first.hypotheses()) {
            assertTrue(hypothesis.id().matches("h[0-9]+"), "bad id: " + hypothesis.id());
            assertFalse(hypothesis.question().isBlank());
            assertFalse(hypothesis.test().isBlank());
            assertFalse(hypothesis.rationale().isBlank());
            assertTrue(PRIORITIES.contains(hypothesis.priority()),
                    "bad priority: " + hypothesis.priority());
            assertFalse(hypothesis.columns().isEmpty(), "hypothesis must name its columns");
        }
        List<String> order = first.hypotheses().stream()
                .map(hypothesis -> hypothesis.priority() + "/" + hypothesis.id())
                .toList();
        assertEquals(order.stream().sorted((left, right) -> {
            int byPriority = Integer.compare(rank(left), rank(right));
            return byPriority != 0 ? byPriority : left.compareTo(right);
        }).toList(), order, "hypotheses are not ordered by priority then id");
    }

    private static int rank(String priorityAndId) {
        return switch (priorityAndId.substring(0, priorityAndId.indexOf('/'))) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    @Test
    void recommendationsMentionTopFindings() throws Exception {
        ExploreResult result = iris();

        assertFalse(result.recommendations().isEmpty());
        assertTrue(result.recommendations().size() <= 8,
                "recommendations are capped at 8, got " + result.recommendations().size());
        for (String recommendation : result.recommendations()) {
            assertFalse(recommendation.isBlank());
        }
        assertTrue(result.recommendations().stream().anyMatch(text -> text.contains("petal_length")),
                "top correlation must be recommended: " + result.recommendations());
        assertTrue(result.recommendations().stream().anyMatch(text -> text.contains("species")),
                "species difference must be recommended: " + result.recommendations());
    }

    @Test
    void timeSpanIsDetectedForTheFirstDateColumn() throws Exception {
        Path parquet = ExploreTestSupport.parquet(dir, "dates.parquet", """
                SELECT * FROM (VALUES
                    (DATE '2024-01-01', 1.0),
                    (DATE '2024-02-01', 2.0),
                    (DATE '2024-03-01', 3.0)) AS t(day, value)""");

        ExploreResult result = service.explore(parquet, null, 50, 1000);

        assertNotNull(result.time());
        assertEquals("day", result.time().column());
        assertEquals("month", result.time().granularity());
        assertEquals("2024-01-01", result.time().from());
        assertEquals("2024-03-01", result.time().to());
        assertTrue(result.recommendations().stream().anyMatch(text -> text.contains("day")),
                "time recommendation expected: " + result.recommendations());
    }

    @Test
    void datasetsWithoutDatesHaveNoTimeSpan() throws Exception {
        assertNull(iris().time());
    }
}
