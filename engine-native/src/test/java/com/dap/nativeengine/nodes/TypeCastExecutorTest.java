package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeCastExecutorTest {
    private static List<String> values(TableData data, int index) {
        return data.data().stream().map(row -> row.get(index)).sorted().toList();
    }

    @Test
    void castsTextToIntegerAndKeepsOtherColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("name", "Utf8"), ColumnSpec.of("qty", "Utf8"),
                    ColumnSpec.of("price", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"a", "7", 1.5},
                    new Object[]{"b", "9", 2.5}));

            LazyPlan plan = new TypeCastExecutor().execute(NodeTestSupport.context(db, """
                    {"columns":[{"column":"qty","type":"Int64"}]}
                    """), List.of(input));

            assertEquals(List.of("name", "qty", "price"), plan.columns().stream().map(ColumnSpec::name).toList());
            assertEquals(List.of("Utf8", "Int64", "Float64"),
                    plan.columns().stream().map(ColumnSpec::arrowType).toList());
            assertEquals(List.of("7", "9"), values(plan.preview(10), 1));
        }
    }

    @Test
    void castsToDecimalAndDateWithTypedSchema() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("day", "Utf8"), ColumnSpec.of("amount", "Float64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.of(
                    new Object[]{"2024-01-15", 1.5},
                    new Object[]{"2024-02-20", 2.25}));

            LazyPlan plan = new TypeCastExecutor().execute(NodeTestSupport.context(db, """
                    {"columns":[{"column":"day","type":"Date32"},{"column":"amount","type":"Decimal128(10,2)"}]}
                    """), List.of(input));

            assertEquals(List.of("Date32", "Decimal128(10,2)"),
                    plan.columns().stream().map(ColumnSpec::arrowType).toList());
            assertEquals("Number (Decimal)", plan.columns().get(1).knimeType());
            TableData data = plan.preview(10);
            assertEquals(List.of("2024-01-15", "2024-02-20"), values(data, 0));
            assertEquals(List.of("1.50", "2.25"), values(data, 1));
        }
    }

    @Test
    void unknownColumnIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("qty", "Utf8")),
                    List.<Object[]>of(new Object[]{"7"}));

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> new TypeCastExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[{"column":"nope","type":"Int64"}]}
                            """), List.of(input)));
            assertTrue(failure.getMessage().contains("nope"), failure.getMessage());
        }
    }

    @Test
    void unsupportedOrMalformedTypeIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("qty", "Utf8")),
                    List.<Object[]>of(new Object[]{"7"}));

            IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
                    () -> new TypeCastExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[{"column":"qty","type":"Int32"}]}
                            """), List.of(input)));
            assertTrue(unsupported.getMessage().contains("Int32"), unsupported.getMessage());
            assertTrue(unsupported.getMessage().contains("Decimal128"), unsupported.getMessage());

            IllegalArgumentException malformed = assertThrows(IllegalArgumentException.class,
                    () -> new TypeCastExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[{"column":"qty","type":"Decimal128(10)"}]}
                            """), List.of(input)));
            assertTrue(malformed.getMessage().contains("Decimal128(precision,scale)"), malformed.getMessage());

            IllegalArgumentException scale = assertThrows(IllegalArgumentException.class,
                    () -> new TypeCastExecutor().execute(NodeTestSupport.context(db, """
                            {"columns":[{"column":"qty","type":"Decimal128(5,7)"}]}
                            """), List.of(input)));
            assertTrue(scale.getMessage().contains("scale"), scale.getMessage());
        }
    }

    @Test
    void emptyColumnListAndMissingConfigBehaveAsExpected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, List.of(ColumnSpec.of("qty", "Utf8")),
                    List.<Object[]>of(new Object[]{"7"}));

            LazyPlan unchanged = new TypeCastExecutor().execute(NodeTestSupport.context(db, """
                    {"columns":[]}
                    """), List.of(input));
            assertSame(input, unchanged);

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new TypeCastExecutor().execute(NodeTestSupport.context(db, "{}"), List.of(input)));
            assertTrue(missing.getMessage().contains("columns"), missing.getMessage());
        }
    }
}
