package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** dap.text_normalize: Unicode normalization and text clean-up. */
class TextNormalizeExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"));

    private static LazyPlan normalize(DuckDbSession db, List<Object[]> rows, String config)
            throws Exception {
        LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows);
        NodeContext context = NodeTestSupport.context(db, config);
        return new TextNormalizeExecutor().execute(context, List.of(input));
    }

    private static List<Object[]> one(String value) {
        return List.<Object[]>of(new Object[]{1L, value});
    }

    @Test
    void nfkcTurnsFullWidthCharactersIntoAscii() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one("ＡＢＣ１２３"),
                    "{\"columns\":[\"name\"],\"mode\":\"nfkc\"}");

            assertEquals(List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8"),
                    ColumnSpec.of("name_normalized", "Utf8")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("1", "ＡＢＣ１２３", "ABC123"), data.data().get(0));
        }
    }

    @Test
    void stripAccentsAndLowercaseProducePlainAscii() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one("Café"), """
                    {"columns":["name"],"strip_accents":true,"case":"lower"}
                    """);

            assertEquals(List.of("cafe"), plan.preview(10).data().get(0).subList(2, 3));
        }
    }

    @Test
    void uppercaseIsApplied() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one("Ann"), "{\"columns\":[\"name\"],\"case\":\"upper\"}");

            assertEquals("ANN", plan.preview(10).data().get(0).get(2));
        }
    }

    @Test
    void collapseWhitespaceAndTrim() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one("  a   b  "), """
                    {"columns":["name"],"collapse_whitespace":true,"trim":true}
                    """);

            assertEquals("a b", plan.preview(10).data().get(0).get(2));
        }
    }

    @Test
    void removePunctuation() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one("a,b!c?"), """
                    {"columns":["name"],"remove_punctuation":true}
                    """);

            assertEquals("abc", plan.preview(10).data().get(0).get(2));
        }
    }

    @Test
    void nfcComposesDecomposedTextInSql() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one("e\u0301"), "{\"columns\":[\"name\"],\"mode\":\"nfc\"}");

            assertEquals("é", plan.preview(10).data().get(0).get(2));
        }
    }

    @Test
    void replaceInPlaceKeepsTheOriginalSchema() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one("  Ann  "), """
                    {"columns":["name"],"trim":true,"replace":true}
                    """);

            assertEquals(COLUMNS, plan.columns());
            assertEquals("Ann", plan.preview(10).data().get(0).get(1));
        }
    }

    @Test
    void customSuffixNamesTheNewColumn() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one("Ann"), """
                    {"columns":["name"],"case":"lower","suffix":"_clean"}
                    """);

            assertEquals("name_clean", plan.columns().get(2).name());
            assertEquals("ann", plan.preview(10).data().get(0).get(2));
        }
    }

    @Test
    void unknownColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> normalize(db, one("x"), "{\"columns\":[\"nope\"]}"));
            assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
        }
    }

    @Test
    void nonTextColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> normalize(db, one("x"), "{\"columns\":[\"id\"]}"));
            assertTrue(failure.getMessage().contains("id"), failure.getMessage());
        }
    }

    @Test
    void unknownModeIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> normalize(db, one("x"), "{\"columns\":[\"name\"],\"mode\":\"nfe\"}"));
            assertTrue(failure.getMessage().contains("mode"), failure.getMessage());
        }
    }

    @Test
    void nullValuesStayNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = normalize(db, one(null), "{\"columns\":[\"name\"],\"case\":\"lower\"}");

            assertEquals(null, plan.preview(10).data().get(0).get(2));
        }
    }

    @Test
    void emptyColumnsListIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> normalize(db, one("x"), "{\"columns\":[]}"));
            assertTrue(failure.getMessage().contains("columns"), failure.getMessage());
            assertFalse(failure.getMessage().isBlank());
        }
    }
}
