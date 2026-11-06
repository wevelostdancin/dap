package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncoderExecutorTest {
    private static LazyPlan strings(DuckDbSession db, String... values) throws Exception {
        List<Object[]> rows = java.util.Arrays.stream(values).map(value -> new Object[]{value}).toList();
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("v", "Utf8")), rows);
    }

    @Test
    void labelUsesDenseRankAndAppendsInt64() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new EncoderExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"v","output":"code","method":"label"}
                    """), List.of(strings(db, "b", "a", "b", "c")));

            assertEquals(List.of(ColumnSpec.of("v", "Utf8"), ColumnSpec.of("code", "Int64")),
                    plan.columns());
            java.util.Map<String, String> codes = new java.util.LinkedHashMap<>();
            for (List<String> row : plan.preview(10).data()) {
                codes.put(row.get(0), row.get(1));
            }
            assertEquals(java.util.Map.of("a", "1", "b", "2", "c", "3"), codes);
        }
    }

    @Test
    void oneHotCreatesOneInt64ColumnPerCategoryFromConfig() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new EncoderExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"v","output":"kind","method":"onehot","categories":["a","b"]}
                    """), List.of(strings(db, "a", "b", "a")));

            assertEquals(List.of(ColumnSpec.of("v", "Utf8"), ColumnSpec.of("kind_a", "Int64"),
                    ColumnSpec.of("kind_b", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("a", "1", "0"), data.data().get(0));
            assertEquals(List.of("b", "0", "1"), data.data().get(1));
            assertEquals(List.of("a", "1", "0"), data.data().get(2));
        }
    }

    @Test
    void oneHotWorksForNumericColumnsAndAbsentCategoriesStayZero() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("n", "Int64")),
                    List.of(new Object[]{1L}, new Object[]{3L}));
            LazyPlan plan = new EncoderExecutor().execute(NodeTestSupport.context(db, """
                    {"column":"n","output":"enc","method":"onehot","categories":[1,2]}
                    """), List.of(input));

            assertEquals(List.of(ColumnSpec.of("n", "Int64"), ColumnSpec.of("enc_1", "Int64"),
                    ColumnSpec.of("enc_2", "Int64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(List.of("1", "1", "0"), data.data().get(0));
            // value 3 matches no configured category, category 2 never occurs
            assertEquals(List.of("3", "0", "0"), data.data().get(1));
        }
    }

    @Test
    void oneHotRequiresCategoriesAndRejectsDuplicates() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = strings(db, "a");

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new EncoderExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"kind","method":"onehot"}
                            """), List.of(input)));
            assertTrue(missing.getMessage().contains("categories"), missing.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> new EncoderExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"kind","method":"onehot","categories":["a","a"]}
                            """), List.of(input)));
            assertTrue(duplicate.getMessage().contains("duplicate"), duplicate.getMessage());
        }
    }

    @Test
    void invalidMethodCategoryAndOutputAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = strings(db, "a");

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> new EncoderExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"x","method":"binary"}
                            """), List.of(input)));
            assertTrue(method.getMessage().contains("binary"), method.getMessage());

            IllegalArgumentException collision = assertThrows(IllegalArgumentException.class,
                    () -> new EncoderExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"v","method":"label"}
                            """), List.of(input)));
            assertTrue(collision.getMessage().contains("v"), collision.getMessage());

            IllegalArgumentException container = assertThrows(IllegalArgumentException.class,
                    () -> new EncoderExecutor().execute(NodeTestSupport.context(db, """
                            {"column":"v","output":"kind","method":"onehot","categories":[["a"]]}
                            """), List.of(input)));
            assertTrue(container.getMessage().contains("scalar"), container.getMessage());
        }
    }
}