package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dap.fuzzy_join: string similarity joins on keys. */
class FuzzyJoinExecutorTest {
    private static final List<ColumnSpec> LEFT = List.of(
            ColumnSpec.of("name", "Utf8"), ColumnSpec.of("lv", "Int64"));
    private static final List<ColumnSpec> RIGHT = List.of(
            ColumnSpec.of("name", "Utf8"), ColumnSpec.of("rv", "Int64"));

    private static String canonical(List<String> row) {
        StringJoiner joiner = new StringJoiner("|");
        for (String value : row) {
            joiner.add(value == null ? "<null>" : value);
        }
        return joiner.toString();
    }

    private static List<String> rows(TableData data) {
        return data.data().stream().map(FuzzyJoinExecutorTest::canonical).sorted().toList();
    }

    private static LazyPlan join(DuckDbSession db, List<Object[]> leftRows, List<Object[]> rightRows,
                                 String config) throws Exception {
        LazyPlan left = NodeTestSupport.table(db, LEFT, leftRows);
        LazyPlan right = NodeTestSupport.table(db, RIGHT, rightRows);
        NodeContext context = NodeTestSupport.context(db, config);
        return new FuzzyJoinExecutor().execute(context, List.of(left, right));
    }

    private static List<Object[]> rows(String... values) {
        List<Object[]> rows = new java.util.ArrayList<>();
        long id = 1;
        for (String value : values) {
            rows.add(new Object[]{value, id++});
        }
        return rows;
    }

    @Test
    void jaroWinklerMatchesNearIdenticalNames() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, rows("martha"), rows("marhta"), """
                    {"left_key":"name","right_key":"name","method":"jaro_winkler","threshold":0.9}
                    """);

            assertEquals(List.of(ColumnSpec.of("name", "Utf8"), ColumnSpec.of("lv", "Int64"),
                    ColumnSpec.of("name (Right)", "Utf8"), ColumnSpec.of("rv", "Int64"),
                    ColumnSpec.of("similarity", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertTrue(Double.parseDouble(data.data().get(0).get(4)) >= 0.9,
                    data.data().toString());
        }
    }

    @Test
    void thresholdFiltersDissimilarKeys() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, rows("martha"), rows("zzzzzz"), """
                    {"left_key":"name","right_key":"name","method":"jaro_winkler","threshold":0.9}
                    """);

            assertEquals(0, plan.preview(10).rows());
        }
    }

    @Test
    void leftJoinKeepsUnmatchedLeftRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, rows("martha", "zzzzzz"), rows("marhta"), """
                    {"left_key":"name","right_key":"name","method":"jaro_winkler",
                     "threshold":0.9,"how":"left"}
                    """);

            TableData data = plan.preview(10);
            assertEquals(2, data.rows());
            List<String> rows = rows(data);
            assertTrue(rows.get(0).startsWith("martha|1|marhta|1|0.9"), rows.toString());
            assertEquals("zzzzzz|2|<null>|<null>|<null>", rows.get(1), rows.toString());
        }
    }

    @Test
    void maxMatchesOneKeepsOnlyTheBestCandidate() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, rows("abc"), rows("abc", "abd", "abx"), """
                    {"left_key":"name","right_key":"name","method":"levenshtein",
                     "threshold":0.5,"max_matches":1}
                    """);

            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals("abc", data.data().get(0).get(2));
        }
    }

    @Test
    void maxMatchesAllowsSeveralCandidates() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, rows("abc"), rows("abc", "abd"), """
                    {"left_key":"name","right_key":"name","method":"levenshtein",
                     "threshold":0.5,"max_matches":3}
                    """);

            assertEquals(2, plan.preview(10).rows());
        }
    }

    @Test
    void levenshteinSimilarityIsNormalisedByLength() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, rows("kitten"), rows("sitting"), """
                    {"left_key":"name","right_key":"name","method":"levenshtein","threshold":0.5}
                    """);

            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals(1.0 - 3.0 / 7.0, Double.parseDouble(data.data().get(0).get(4)), 1e-9);
        }
    }

    @Test
    void damerauMethodIsAvailable() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = join(db, rows("abcd"), rows("abdc"), """
                    {"left_key":"name","right_key":"name","method":"damerau","threshold":0.7}
                    """);

            assertEquals(1, plan.preview(10).rows());
        }
    }

    @Test
    void unknownMethodIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> join(db, rows("a"), rows("b"), """
                            {"left_key":"name","right_key":"name","method":"cosine"}
                            """));
            for (String method : List.of("jaro_winkler", "levenshtein", "damerau")) {
                assertTrue(failure.getMessage().contains(method), failure.getMessage());
            }
        }
    }

    @Test
    void unknownKeyColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> join(db, rows("a"), rows("b"), """
                            {"left_key":"nope","right_key":"name"}
                            """));
            assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
        }
    }

    @Test
    void thresholdMustBeAProbability() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> join(db, rows("a"), rows("b"), """
                            {"left_key":"name","right_key":"name","threshold":1.5}
                            """));
            assertTrue(failure.getMessage().contains("threshold"), failure.getMessage());
        }
    }

    @Test
    void nonTextKeysAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> join(db, rows("a"), rows("b"), """
                            {"left_key":"lv","right_key":"rv"}
                            """));
            assertTrue(failure.getMessage().contains("lv"), failure.getMessage());
        }
    }
}
