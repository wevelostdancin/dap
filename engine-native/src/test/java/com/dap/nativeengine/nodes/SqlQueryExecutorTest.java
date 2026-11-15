package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlQueryExecutorTest {
    private static final List<ColumnSpec> PEOPLE = List.of(
            ColumnSpec.of("id", "Int64"),
            ColumnSpec.of("name", "Utf8"),
            ColumnSpec.of("score", "Float64"),
            ColumnSpec.of("category", "Utf8"));

    private static final List<Object[]> PEOPLE_ROWS = List.of(
            new Object[]{1L, "ann", 1.0, "A"},
            new Object[]{2L, "bob", 2.0, "A"},
            new Object[]{3L, "cid", 3.0, "B"},
            new Object[]{4L, "dan", 4.0, "B"},
            new Object[]{5L, "eve", 5.0, "C"},
            new Object[]{6L, "fay", 6.0, "C"});

    private static String config(String sql, String... inputs) {
        ObjectNode node = Json.object();
        node.put("sql", sql);
        if (inputs.length > 0) {
            ArrayNode array = node.putArray("inputs");
            for (String input : inputs) {
                array.add(input);
            }
        }
        return Json.write(node);
    }

    private static String configWithLimit(String sql, int limit, String... inputs) {
        ObjectNode node = Json.object();
        node.put("sql", sql);
        node.put("limit", limit);
        if (inputs.length > 0) {
            ArrayNode array = node.putArray("inputs");
            for (String input : inputs) {
                array.add(input);
            }
        }
        return Json.write(node);
    }

    private static LazyPlan plan(DuckDbSession db, Connection connection, List<LazyPlan> inputs,
                                 String config) throws Exception {
        NodeContext context = NodeTestSupport.context(db, connection, config);
        return new SqlQueryExecutor().execute(context, inputs);
    }

    private static LazyPlan planWithInputs(DuckDbSession db, Connection connection, List<LazyPlan> inputs,
                                           String config, String... inputNodeIds) throws Exception {
        NodeContext context = NodeTestSupport.contextWithInputs(db, connection, config, inputNodeIds);
        return new SqlQueryExecutor().execute(context, inputs);
    }

    private static List<String> columnNames(LazyPlan plan) {
        return plan.columns().stream().map(ColumnSpec::name).toList();
    }

    @Test
    void aggregatesOverTwoConnectedInputs() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan left = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            LazyPlan right = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);

            LazyPlan plan = plan(db, connection, List.of(left, right), config("""
                    SELECT l.category, COUNT(*) AS n, SUM(l.score) AS total
                    FROM left_data l JOIN right_data r ON l.id = r.id
                    GROUP BY l.category ORDER BY l.category""", "left_data", "right_data"));

            assertEquals(List.of(
                    ColumnSpec.of("category", "Utf8"),
                    ColumnSpec.of("n", "Int64"),
                    ColumnSpec.of("total", "Float64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of(List.of("A", "2", "3.0"), List.of("B", "2", "7.0"),
                    List.of("C", "2", "11.0")), data.data());
        }
    }

    @Test
    void withoutInputsUsesSourceNodeIdAliases() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            LazyPlan plan = planWithInputs(db, connection, List.of(input),
                    config("SELECT COUNT(*) AS n FROM \"1:1\""), "1:1");
            assertEquals(List.of(ColumnSpec.of("n", "Int64")), plan.columns());
            assertEquals(List.of(List.of("6")), plan.preview(10).data());
        }
    }

    @Test
    void positionalAliasesFollowPortOrder() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan left = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            LazyPlan right = NodeTestSupport.table(db, PEOPLE,
                    List.<Object[]>of(new Object[]{9L, "zed", 9.0, "Z"}));
            // Names are positional: port 0 is "1:2" (left), port 1 is "1:1" (right).
            LazyPlan plan = planWithInputs(db, connection, List.of(left, right),
                    config("SELECT COUNT(*) AS n FROM \"1:1\"", "1:2", "1:1"), "1:1", "1:2");
            assertEquals(List.of(List.of("1")), plan.preview(10).data());
        }
    }

    @Test
    void subsetOfInputsIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan left = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            LazyPlan right = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> planWithInputs(db, connection, List.of(left, right),
                            config("SELECT COUNT(*) FROM \"1:2\"", "1:2"), "1:1", "1:2"));
            assertTrue(failure.getMessage().contains("name every connected input"), failure.getMessage());
        }
    }

    @Test
    void duplicateAliasesAreRejectedCaseInsensitively() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan left = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            LazyPlan right = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> planWithInputs(db, connection, List.of(left, right),
                            config("SELECT 1", "A", "a"), "1:1", "1:2"));
            assertTrue(failure.getMessage().contains("duplicate input alias"), failure.getMessage());
        }
    }

    @Test
    void reservedWrapperAliasIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> planWithInputs(db, connection, List.of(input),
                            config("SELECT COUNT(*) FROM dap_query", "dap_query"), "1:1"));
            assertTrue(failure.getMessage().contains("reserved"), failure.getMessage());
        }
    }

    @Test
    void sameSourceOnBothPortsIsRejectedWithoutInputs() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan left = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            LazyPlan right = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> planWithInputs(db, connection, List.of(left, right),
                            config("SELECT 1"), "1:1", "1:1"));
            assertTrue(failure.getMessage().contains("more than one input port"), failure.getMessage());
        }
    }

    @Test
    void mismatchedInputIdListIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan left = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            LazyPlan right = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> planWithInputs(db, connection, List.of(left, right),
                            config("SELECT 1"), "1:1"));
            assertTrue(failure.getMessage().contains("cannot derive an alias"), failure.getMessage());
        }
    }

    @Test
    void ddlAndMultipleStatementsAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);

            ApiException ddl = assertThrows(ApiException.class,
                    () -> plan(db, connection, List.of(input), config("CREATE TABLE stolen (a INT)")));
            assertEquals(400, ddl.status());
            assertEquals("sql_not_allowed", ddl.code());

            ApiException drop = assertThrows(ApiException.class,
                    () -> plan(db, connection, List.of(input),
                            config("SELECT * FROM data; DROP TABLE data", "data")));
            assertEquals("sql_not_allowed", drop.code());

            ApiException keyword = assertThrows(ApiException.class,
                    () -> plan(db, connection, List.of(input),
                            config("SELECT * FROM data UNION ALL SELECT * FROM data "
                                    + "INSERT INTO data SELECT * FROM data", "data")));
            assertEquals("sql_not_allowed", keyword.code());
        }
    }

    @Test
    void semicolonInsideStringLiteralAndCommentsAreIgnored() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan plan = plan(db, connection, List.of(),
                    config("SELECT 'a;b' AS value, 'create table' AS words -- ; DROP TABLE t"));

            assertEquals(List.of(ColumnSpec.of("value", "Utf8"), ColumnSpec.of("words", "Utf8")),
                    plan.columns());
            assertEquals(List.of(List.of("a;b", "create table")), plan.preview(10).data());
        }
    }

    @Test
    void duplicateResultAliasesAreRenamedDeterministically() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan plan = plan(db, connection, List.of(), config("SELECT 1 AS a, 2 AS a, 3 AS a"));

            assertEquals(List.of("a", "a_1", "a_2"), columnNames(plan));
            assertEquals(List.of(List.of("1", "2", "3")), plan.preview(10).data());
        }
    }

    @Test
    void nestedTypesAreRejectedWithTheColumnName() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            ApiException list = assertThrows(ApiException.class,
                    () -> plan(db, connection, List.of(), config("SELECT [1, 2] AS tags")));
            assertEquals(400, list.status());
            assertEquals("sql_type_not_supported", list.code());
            assertTrue(list.getMessage().contains("tags"), list.getMessage());

            ApiException timestampWithZone = assertThrows(ApiException.class,
                    () -> plan(db, connection, List.of(),
                            config("SELECT TIMESTAMPTZ '2026-01-01 00:00:00+00' AS ts")));
            assertTrue(timestampWithZone.getMessage().contains("ts"), timestampWithZone.getMessage());
        }
    }

    @Test
    void limitIsAppliedAndBounded() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);

            LazyPlan plan = plan(db, connection, List.of(input),
                    configWithLimit("SELECT * FROM data ORDER BY id", 2, "data"));
            assertTrue(plan.sql().contains("LIMIT 2"), plan.sql());
            assertEquals(2, plan.preview(100).rows());

            IllegalArgumentException tooLarge = assertThrows(IllegalArgumentException.class,
                    () -> plan(db, connection, List.of(input),
                            configWithLimit("SELECT * FROM data", 1_000_001, "data")));
            assertTrue(tooLarge.getMessage().contains("limit"), tooLarge.getMessage());
        }
    }

    @Test
    void denyListMatchesWholeWordsOnly() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan plan = plan(db, connection, List.of(),
                    config("SELECT 1 AS created, 2 AS settings, 3 AS loaded"));
            assertEquals(List.of("created", "settings", "loaded"), columnNames(plan));
        }
    }

    @Test
    void sqlReadingADeniedPathIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            NodeContext context = NodeTestSupport.context(db, connection,
                    config("SELECT * FROM read_csv('/etc/passwd')"));

            ApiException failure = assertThrows(ApiException.class,
                    () -> new SqlQueryExecutor().execute(context, List.of()));
            assertEquals(400, failure.status(), failure.getMessage());
            assertEquals("path_not_allowed", failure.code(), failure.getMessage());
            assertTrue(failure.hint().contains("allowed roots"), failure.hint());
        }
    }

    @Test
    void aliasCountMustMatchConnectedInputs() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            LazyPlan input = NodeTestSupport.table(db, PEOPLE, PEOPLE_ROWS);

            IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                    () -> plan(db, connection, List.of(input), config("SELECT * FROM data", "data", "other")));
            assertTrue(mismatch.getMessage().contains("inputs"), mismatch.getMessage());

            IllegalArgumentException unnamed = assertThrows(IllegalArgumentException.class,
                    () -> plan(db, connection, List.of(input), config("SELECT 1")));
            assertTrue(unnamed.getMessage().contains("inputs"), unnamed.getMessage());
        }
    }
}
