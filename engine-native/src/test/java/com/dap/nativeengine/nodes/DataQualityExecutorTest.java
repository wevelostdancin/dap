package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataQualityExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("name", "Utf8"),
            ColumnSpec.of("score", "Float64"),
            ColumnSpec.of("category", "Utf8"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{1L, "alpha", 1.0, "A"},
            new Object[]{2L, "beta", 2.0, "B"},
            new Object[]{3L, "gamma", 3.0, "A"},
            new Object[]{4L, "delta", 4.0, "C"},
            new Object[]{3L, "gamma", 3.0, "A"},
            new Object[]{5L, "epsilon", 5.0, "B"});

    private static final String ALL_CHECKS = """
            [
              {"kind": "not_null", "column": "id"},
              {"kind": "unique", "column": "id"},
              {"kind": "range", "column": "score", "min": 0, "max": 4},
              {"kind": "pattern", "column": "name", "regex": "^[a-z]+$"},
              {"kind": "allowed_values", "column": "category", "values": ["A", "B", "C"]},
              {"kind": "row_count", "min": 1, "max": 10},
              {"kind": "referential", "column": "id"}
            ]
            """;

    private static LazyPlan people(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, COLUMNS, ROWS);
    }

    private static LazyPlan reference(DuckDbSession db, List<Object[]> ids) throws Exception {
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("id", "Int64")), ids);
    }

    private static String config(String checks, String extra) {
        return "{\"checks\":" + checks + extra + "}";
    }

    private static TableData run(DuckDbSession db, Connection job, String config, LazyPlan... inputs)
            throws Exception {
        NodeContext context = NodeTestSupport.context(db, job, config);
        return new DataQualityExecutor().execute(context, List.of(inputs)).preview(100);
    }

    @Test
    void longOutputReportsOneRowPerCheck() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);
            LazyPlan reference = reference(db, List.of(
                    new Object[]{1L}, new Object[]{2L}, new Object[]{3L}));

            TableData data = run(db, job, config(ALL_CHECKS, ",\"fail_on\":\"never\""), input, reference);

            assertEquals(List.of(
                    ColumnSpec.of("check", "Utf8"),
                    ColumnSpec.of("column", "Utf8"),
                    ColumnSpec.of("status", "Utf8"),
                    ColumnSpec.of("message", "Utf8"),
                    ColumnSpec.of("failed_rows", "Int64"),
                    ColumnSpec.of("total_rows", "Int64")), data.columns());
            assertEquals(7, data.rows());
            List<List<String>> rows = data.data();
            assertEquals(List.of("not_null", "id", "PASS", "ok", "0", "6"), rows.get(0));
            assertEquals(List.of("unique", "id", "FAIL", "2 rows sharing a duplicated value", "2", "6"),
                    rows.get(1));
            assertEquals(List.of("range", "score", "FAIL", "1 row outside [0, 4]", "1", "6"), rows.get(2));
            assertEquals(List.of("pattern", "name", "PASS", "ok", "0", "6"), rows.get(3));
            assertEquals(List.of("allowed_values", "category", "PASS", "ok", "0", "6"), rows.get(4));
            assertEquals(Arrays.asList("row_count", null, "PASS", "ok", "0", "6"), rows.get(5));
            assertEquals(List.of("referential", "id", "FAIL",
                    "2 rows missing from the reference table", "2", "6"), rows.get(6));
        }
    }

    @Test
    void summaryOutputAggregatesEveryCheck() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);
            LazyPlan reference = reference(db, List.of(
                    new Object[]{1L}, new Object[]{2L}, new Object[]{3L}));

            TableData data = run(db, job, config(ALL_CHECKS,
                    ",\"fail_on\":\"never\",\"output\":\"summary\""), input, reference);

            assertEquals(1, data.rows());
            List<String> row = data.data().get(0);
            assertEquals("summary", row.get(0));
            assertNull(row.get(1));
            assertEquals("FAIL", row.get(2));
            assertEquals("7 checks: 4 passed, 3 failed", row.get(3));
            assertEquals("5", row.get(4));
            assertEquals("6", row.get(5));
        }
    }

    @Test
    void failOnErrorAbortsTheRun() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);
            LazyPlan reference = reference(db, List.of(
                    new Object[]{1L}, new Object[]{2L}, new Object[]{3L}));
            NodeContext context = NodeTestSupport.context(db, job, config(ALL_CHECKS, ""));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new DataQualityExecutor().execute(context, List.of(input, reference)));
            assertTrue(failure.getMessage().contains("3 checks failed"), failure.getMessage());
            assertTrue(failure.getMessage().contains("unique(id)"), failure.getMessage());
            assertTrue(failure.getMessage().contains("referential(id)"), failure.getMessage());
        }
    }

    @Test
    void failOnWarnTreatsWarningsAsFailures() throws Exception {
        List<Object[]> rows = List.of(
                new Object[]{1L, "ann", 1.0, "A"},
                new Object[]{2L, null, 2.0, "B"});
        String checks = "[{\"kind\":\"not_null\",\"column\":\"name\",\"severity\":\"warn\"}]";
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, rows);

            TableData never = run(db, job, config(checks, ",\"fail_on\":\"never\""), input);
            assertEquals(List.of("not_null", "name", "FAIL", "1 row with a null value", "1", "2"),
                    never.data().get(0));

            TableData errors = run(db, job, config(checks, ",\"fail_on\":\"error\""), input);
            assertEquals("FAIL", errors.data().get(0).get(2), "warnings do not abort fail_on=error");

            NodeContext context = NodeTestSupport.context(db, job, config(checks, ",\"fail_on\":\"warn\""));
            assertThrows(IllegalStateException.class,
                    () -> new DataQualityExecutor().execute(context, List.of(input)));
        }
    }

    @Test
    void referentialCheckComparesAgainstTheReferenceInput() throws Exception {
        String checks = "[{\"kind\":\"referential\",\"column\":\"id\"}]";
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);

            TableData complete = run(db, job, config(checks, ""),
                    input, reference(db, List.of(new Object[]{1L}, new Object[]{2L}, new Object[]{3L},
                            new Object[]{4L}, new Object[]{5L})));
            assertEquals(List.of("referential", "id", "PASS", "ok", "0", "6"), complete.data().get(0));

            TableData partial = run(db, job, config(checks, ",\"fail_on\":\"never\""),
                    input, reference(db, List.of(new Object[]{1L}, new Object[]{2L})));
            assertEquals(List.of("referential", "id", "FAIL",
                    "4 rows missing from the reference table", "4", "6"), partial.data().get(0));

            NodeContext context = NodeTestSupport.context(db, job, config(checks, ",\"fail_on\":\"never\""));
            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new DataQualityExecutor().execute(context, List.of(input)));
            assertTrue(missing.getMessage().contains("Reference"), missing.getMessage());
        }
    }

    @Test
    void rowCountCheckUsesTheConfiguredBounds() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);

            TableData tooFew = run(db, job,
                    config("[{\"kind\":\"row_count\",\"min\":10}]", ",\"fail_on\":\"never\""), input);
            assertEquals(Arrays.asList("row_count", null, "FAIL", "row count 6 below 10", "6", "6"),
                    tooFew.data().get(0));

            TableData tooMany = run(db, job,
                    config("[{\"kind\":\"row_count\",\"max\":3}]", ",\"fail_on\":\"never\""), input);
            assertEquals(Arrays.asList("row_count", null, "FAIL", "row count 6 above 3", "6", "6"),
                    tooMany.data().get(0));
        }
    }

    @Test
    void rejectsInvalidChecks() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            LazyPlan input = people(db);

            assertMessage(db, job, input, config("[]", ""), "checks");
            assertMessage(db, job, input, config("[{\"kind\":\"regexp\"}]", ""), "kind");
            assertMessage(db, job, input, config("[{\"kind\":\"not_null\"}]", ""), "column");
            assertMessage(db, job, input,
                    config("[{\"kind\":\"not_null\",\"column\":\"nope\"}]", ""), "nope");
            assertMessage(db, job, input,
                    config("[{\"kind\":\"pattern\",\"column\":\"name\"}]", ""), "regex");
            assertMessage(db, job, input,
                    config("[{\"kind\":\"allowed_values\",\"column\":\"category\",\"values\":[]}]", ""),
                    "values");
            assertMessage(db, job, input,
                    config("[{\"kind\":\"range\",\"column\":\"score\"}]", ""), "min");
            assertMessage(db, job, input,
                    config("[{\"kind\":\"row_count\",\"min\":1,\"column\":\"id\"}]", ""), "column");
            assertMessage(db, job, input, config("[{\"kind\":\"not_null\",\"column\":\"id\"}]",
                    ",\"output\":\"wide\""), "output");
            assertMessage(db, job, input, config("[{\"kind\":\"not_null\",\"column\":\"id\"}]",
                    ",\"fail_on\":\"sometimes\""), "fail_on");
        }
    }

    private static void assertMessage(DuckDbSession db, Connection job, LazyPlan input,
                                      String config, String fragment) throws Exception {
        NodeContext context = NodeTestSupport.context(db, job, config);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new DataQualityExecutor().execute(context, List.of(input)));
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }
}
