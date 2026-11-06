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

class DuplicateFilterExecutorTest {
    private static LazyPlan plan(DuckDbSession db, List<Object[]> rows, String config) throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("a", "Utf8"), ColumnSpec.of("b", "Int64"));
        return new DuplicateFilterExecutor().execute(NodeTestSupport.context(db, config),
                List.of(NodeTestSupport.table(db, columns, rows)));
    }

    @Test
    void withoutKeysKeepsOnlyDistinctRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan result = plan(db, List.of(
                    new Object[]{"x", 1L},
                    new Object[]{"x", 1L},
                    new Object[]{"y", 2L}), "{}");

            assertEquals(List.of("a", "b"), result.columns().stream().map(ColumnSpec::name).toList());
            TableData data = result.preview(10);
            assertEquals(2L, data.rows());
            assertEquals(2, data.data().size());
        }
    }

    @Test
    void withKeysKeepsOneArbitraryRowPerKey() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan result = plan(db, List.of(
                    new Object[]{"a", 1L},
                    new Object[]{"a", 1L},
                    new Object[]{"a", 2L},
                    new Object[]{"b", 1L}), """
                    {"keys":["a","b"]}
                    """);

            TableData data = result.preview(10);
            assertEquals(3L, data.rows());
            List<String> keys = data.data().stream().map(row -> row.get(0) + ":" + row.get(1)).sorted().toList();
            assertEquals(List.of("a:1", "a:2", "b:1"), keys);
        }
    }

    @Test
    void nullKeyValuesFormOneGroup() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan result = plan(db, List.of(
                    new Object[]{null, 1L},
                    new Object[]{null, 2L},
                    new Object[]{"x", 1L}), """
                    {"keys":["a"]}
                    """);

            TableData data = result.preview(10);
            assertEquals(2L, data.rows());
            assertEquals(1, data.data().stream().filter(row -> row.get(0) == null).count());
        }
    }

    @Test
    void emptyKeyListFallsBackToDistinct() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan result = plan(db, List.of(
                    new Object[]{"x", 1L},
                    new Object[]{"x", 1L},
                    new Object[]{"y", 2L}), """
                    {"keys":[]}
                    """);

            assertEquals(2L, result.preview(10).rows());
        }
    }

    @Test
    void unknownKeyOrWrongShapeIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> plan(db, List.<Object[]>of(new Object[]{"x", 1L}), """
                            {"keys":["nope"]}
                            """));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException notArray = assertThrows(IllegalArgumentException.class,
                    () -> plan(db, List.<Object[]>of(new Object[]{"x", 1L}), """
                            {"keys":"a"}
                            """));
            assertTrue(notArray.getMessage().contains("array"), notArray.getMessage());
        }
    }
}
