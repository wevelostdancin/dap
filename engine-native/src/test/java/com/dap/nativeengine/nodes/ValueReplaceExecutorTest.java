package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueReplaceExecutorTest {
    private static List<ColumnSpec> columns() {
        return List.of(ColumnSpec.of("name", "Utf8"), ColumnSpec.of("n", "Int64"));
    }

    private static LazyPlan replace(DuckDbSession db, List<Object[]> rows, String config) throws Exception {
        return new ValueReplaceExecutor().execute(NodeTestSupport.context(db, config),
                List.of(NodeTestSupport.table(db, columns(), rows)));
    }

    private static List<String> values(TableData data, int index) {
        return data.data().stream().map(row -> row.get(index)).sorted(java.util.Comparator.nullsFirst(String::compareTo)).toList();
    }

    @Test
    void replacesTextValueAndKeepsColumnType() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = replace(db, List.of(
                    new Object[]{"x", 1L},
                    new Object[]{"y", 2L},
                    new Object[]{"z", 3L}), """
                    {"rules":[{"column":"name","from":"y","to":"Y"}]}
                    """);

            assertEquals(columns(), plan.columns());
            assertEquals(List.of("Y", "x", "z"), values(plan.preview(10), 0));
        }
    }

    @Test
    void replacesNumericValueWithTypedLiteral() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = replace(db, List.of(
                    new Object[]{"x", 1L},
                    new Object[]{"y", 2L},
                    new Object[]{"z", 3L}), """
                    {"rules":[{"column":"n","from":2,"to":99}]}
                    """);

            assertEquals(List.of("Utf8", "Int64"),
                    plan.columns().stream().map(ColumnSpec::arrowType).toList());
            assertEquals(List.of("1", "3", "99"), values(plan.preview(10), 1));
        }
    }

    @Test
    void nullCanBeMatchedAndWritten() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan matched = replace(db, List.of(
                    new Object[]{"x", 1L},
                    new Object[]{null, 2L}), """
                    {"rules":[{"column":"name","from":null,"to":"missing"}]}
                    """);
            assertEquals(List.of("missing", "x"), values(matched.preview(10), 0));

            LazyPlan cleared = replace(db, List.of(
                    new Object[]{"x", 1L},
                    new Object[]{"y", 2L}), """
                    {"rules":[{"column":"name","from":"x","to":null}]}
                    """);
            assertEquals(java.util.Arrays.asList(null, "y"), values(cleared.preview(10), 0));
        }
    }

    @Test
    void rulesForTheSameColumnApplyInOrder() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = replace(db, List.of(
                    new Object[]{"x", 1L},
                    new Object[]{"y", 2L},
                    new Object[]{"z", 3L}), """
                    {"rules":[{"column":"name","from":"x","to":"X"},{"column":"name","from":"y","to":"Y"}]}
                    """);

            assertEquals(List.of("X", "Y", "z"), values(plan.preview(10), 0));
        }
    }

    @Test
    void unknownColumnNonScalarAndMissingFieldAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<Object[]> rows = List.<Object[]>of(new Object[]{"x", 1L});

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> replace(db, rows, """
                            {"rules":[{"column":"nope","from":"x","to":"y"}]}
                            """));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException nonScalar = assertThrows(IllegalArgumentException.class,
                    () -> replace(db, rows, """
                            {"rules":[{"column":"name","from":{"a":1},"to":"y"}]}
                            """));
            assertTrue(nonScalar.getMessage().contains("scalar"), nonScalar.getMessage());

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> replace(db, rows, """
                            {"rules":[{"column":"name","from":"x"}]}
                            """));
            assertTrue(missing.getMessage().contains("'to'"), missing.getMessage());
        }
    }
}
