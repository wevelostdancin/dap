package com.dap.nativeengine.explore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        assertNotNull(pair.pAdjusted());
        assertTrue(pair.pAdjusted() >= pair.pValue());
        assertEquals(150L, pair.n());
        assertEquals("very strong", pair.strength());
    }

    @Test
    void relationshipFdrAdjustmentGatesPriorityAndRecommendations() throws Exception {
        // Eight independent columns (numpy seed 2, 25 rows): d/h has r=-0.5442,
        // raw p=0.0049 but statsmodels fdr_bh over all 28 pairs gives p_adjusted=0.1377.
        Path parquet = ExploreTestSupport.parquet(dir, "bh.parquet", """
                SELECT * FROM (VALUES
                    (0.189053,-0.522748,-0.413064,-2.441467,1.799707,1.144166,-0.325423,0.773807),
                    (0.281211,-0.553823,0.977567,-0.310557,-0.328824,-0.792147,0.454958,-0.099198),
                    (0.545289,-0.607186,0.126828,-0.892274,0.841465,0.188035,0.330571,0.410504),
                    (-1.010758,0.783181,2.056703,-1.638443,-1.729411,-1.504831,0.841459,0.128716),
                    (1.078342,0.722431,0.210572,0.284038,-0.169760,0.868460,-1.129716,-0.421859),
                    (0.242939,1.801421,-0.764464,-1.079060,-0.563287,0.969272,-0.235006,1.324347),
                    (-1.872530,1.128523,1.034866,-1.418666,0.153582,1.215758,0.087919,0.999701),
                    (2.374887,0.273932,-0.280382,-0.771052,0.648065,-0.196730,-0.178746,-0.105260),
                    (0.649870,-1.066340,-1.529876,-2.433877,1.198671,0.073793,1.510119,-0.008956),
                    (-0.742155,0.477929,-0.076588,-1.254187,-0.885067,1.766779,0.354351,0.416387),
                    (-0.276552,-0.689720,0.891656,-0.104626,-0.759108,-0.134129,-0.905606,0.190046),
                    (1.129228,-0.836085,1.428544,-0.667620,0.153412,-0.836387,-0.222235,0.047404),
                    (-0.434685,-0.702888,-0.677886,-0.821160,-1.570080,-0.262970,0.401158,0.908402),
                    (0.647103,2.457337,0.318682,-0.456334,1.871635,-1.047218,0.968478,-0.955145),
                    (0.354112,-1.968397,0.899274,-0.158248,-0.967681,1.678419,0.765355,0.045808),
                    (-0.745443,-0.043363,-0.163929,0.724776,0.798075,-0.667756,-0.549371,-0.532065),
                    (-1.349317,-0.591575,-0.092408,0.690952,1.320379,-0.808346,0.550977,-0.444457),
                    (2.075188,-0.048724,0.502127,-0.936162,-0.810689,0.201695,-0.384054,0.353289),
                    (-1.561824,0.662005,-0.899480,1.722423,-0.288467,1.097265,-1.394983,0.428946),
                    (-0.888030,-0.486889,0.024596,-0.317075,0.264256,0.747655,0.652782,-0.087481),
                    (-1.363431,-0.804646,-0.195756,-2.121826,0.752081,-0.239638,-0.251319,0.948376),
                    (0.667911,0.221001,-1.001334,0.310422,0.346178,-0.884773,1.043312,0.507887),
                    (-1.969624,0.121949,-1.264698,1.136315,0.807028,-1.103591,-0.884873,0.101853),
                    (-0.238811,1.525203,0.740974,-0.282427,0.577396,-2.032279,0.309865,0.866407),
                    (-0.178953,-0.710214,0.565833,1.958615,0.204869,-1.120678,1.445805,-1.878547)
                ) AS t(a,b,c,d,e,f,g,h)""");
        ExploreResult result = service.explore(parquet, null, 50, 1000);
        ExploreResult.Relationship pair = relationship(result, "d", "h");

        assertEquals(-0.5442026231829851, pair.value(), 1e-6);
        assertTrue(pair.pValue() < 0.01, "raw p must be significant: " + pair);
        assertEquals(0.13772614624642993, pair.pAdjusted(), 1e-6,
                "BH must span all 28 tested pairs: " + pair);
        assertTrue(result.recommendations().stream().noneMatch(text -> text.contains("Model h with d")),
                "a raw-only significant correlation must not be recommended: "
                        + result.recommendations());
        assertTrue(result.hypotheses().stream()
                        .filter(hypothesis -> hypothesis.columns().equals(List.of("d", "h")))
                        .noneMatch(hypothesis -> "high".equals(hypothesis.priority())),
                "priority must use the adjusted p: " + result.hypotheses());

        Map<?, ?> view = (Map<?, ?>) ((List<?>) result.toMap().get("relationships")).stream()
                .filter(item -> "d".equals(((Map<?, ?>) item).get("left"))
                        && "h".equals(((Map<?, ?>) item).get("right")))
                .findFirst().orElseThrow();
        assertEquals(pair.pAdjusted(), (Double) view.get("p_adjusted"), 1e-12);
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
