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

class CorrelationExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("x", "Float64"),
            ColumnSpec.of("y", "Float64"),
            ColumnSpec.of("z", "Float64"),
            ColumnSpec.of("label", "Utf8"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{1.0, 2.0, 1.0, "a"},
            new Object[]{2.0, 4.0, 8.0, "b"},
            new Object[]{3.0, 6.0, 27.0, "c"},
            new Object[]{4.0, 8.0, 64.0, "d"});

    private static double correlation(LazyPlan plan, int row) throws Exception {
        return Double.parseDouble(plan.preview(10).data().get(row).get(2));
    }

    private static List<String> pairs(TableData data) {
        return data.data().stream().map(row -> row.get(0) + "|" + row.get(1)).toList();
    }

    @Test
    void pearsonCorrelatesOnePair() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan plan = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db, "{\"columns\":[\"x\",\"y\"],\"method\":\"pearson\"}"),
                    List.of(input));

            assertEquals(List.of(
                    ColumnSpec.of("column_a", "Utf8"),
                    ColumnSpec.of("column_b", "Utf8"),
                    ColumnSpec.of("correlation", "Float64")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals(List.of("x|y"), pairs(data));
            assertEquals(1.0, correlation(plan, 0), 1e-9);
        }
    }

    @Test
    void everyPairIsEmittedInColumnOrder() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            TableData data = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db, "{\"columns\":[\"x\",\"y\",\"z\"]}"),
                    List.of(input)).preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("x|y", "x|z", "y|z"), pairs(data));
        }
    }

    @Test
    void spearmanRanksNonLinearMonotonicDataWhilePearsonDoesNot() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan spearman = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db, "{\"columns\":[\"x\",\"z\"],\"method\":\"spearman\"}"),
                    List.of(input));
            assertEquals(1.0, correlation(spearman, 0), 1e-9);

            LazyPlan pearson = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db, "{\"columns\":[\"x\",\"z\"],\"method\":\"pearson\"}"),
                    List.of(input));
            double value = correlation(pearson, 0);
            assertTrue(value > 0.9 && value < 1.0, "expected strong but imperfect pearson, got " + value);
        }
    }

    @Test
    void nullsAreDroppedPerPair() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("a", "Float64"), ColumnSpec.of("b", "Float64")),
                    List.of(
                            new Object[]{1.0, 1.0},
                            new Object[]{2.0, 2.0},
                            new Object[]{null, 3.0},
                            new Object[]{4.0, 4.0}));

            LazyPlan pearson = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db, "{\"columns\":[\"a\",\"b\"]}"), List.of(input));
            assertEquals(1.0, correlation(pearson, 0), 1e-9);

            LazyPlan spearman = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db, "{\"columns\":[\"a\",\"b\"],\"method\":\"spearman\"}"),
                    List.of(input));
            assertEquals(1.0, correlation(spearman, 0), 1e-9);
        }
    }

    @Test
    void invalidColumnsAndMethodAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException single = assertThrows(IllegalArgumentException.class,
                    () -> new CorrelationExecutor().execute(
                            NodeTestSupport.context(db, "{\"columns\":[\"x\"]}"), List.of(input)));
            assertTrue(single.getMessage().contains("two"), single.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> new CorrelationExecutor().execute(
                            NodeTestSupport.context(db, "{\"columns\":[\"x\",\"nope\"]}"), List.of(input)));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> new CorrelationExecutor().execute(
                            NodeTestSupport.context(db, "{\"columns\":[\"x\",\"label\"]}"), List.of(input)));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> new CorrelationExecutor().execute(
                            NodeTestSupport.context(db, "{\"columns\":[\"x\",\"x\"]}"), List.of(input)));
            assertTrue(duplicate.getMessage().contains("duplicate"), duplicate.getMessage());

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> new CorrelationExecutor().execute(
                            NodeTestSupport.context(db,
                                    "{\"columns\":[\"x\",\"y\"],\"method\":\"kendall\"}"), List.of(input)));
            assertTrue(method.getMessage().contains("kendall"), method.getMessage());
        }
    }
}
