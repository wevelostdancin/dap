package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dap.correlation. Reference values are R/independent double-precision computations:
 * Pearson p-values use the t approximation, Kendall the normal z approximation, the CI
 * Fisher's z transform (Kendall: the asymptotic normal standard error).
 */
class CorrelationExecutorTest {
    private static final List<ColumnSpec> OUTPUT = List.of(
            ColumnSpec.of("column_a", "Utf8"),
            ColumnSpec.of("column_b", "Utf8"),
            ColumnSpec.of("method", "Utf8"),
            ColumnSpec.of("n", "Int64"),
            ColumnSpec.of("correlation", "Float64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("ci_low", "Float64"),
            ColumnSpec.of("ci_high", "Float64"),
            ColumnSpec.of("partial", "Utf8"));

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

    private static TableData run(DuckDbSession db, LazyPlan input, String configJson) throws Exception {
        LazyPlan plan = new CorrelationExecutor().execute(
                NodeTestSupport.context(db, configJson), List.of(input));
        return plan.preview(20);
    }

    private static double number(TableData data, int row, int column) {
        return Double.parseDouble(data.data().get(row).get(column));
    }

    private static List<String> pairs(TableData data) {
        return data.data().stream().map(row -> row.get(0) + "|" + row.get(1)).toList();
    }

    private static List<String> row(TableData data, String first, String second) {
        return data.data().stream()
                .filter(row -> first.equals(row.get(0)) && second.equals(row.get(1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + first + "|" + second + " in " + data.data()));
    }

    private static LazyPlan line(DuckDbSession db) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        double[] ys = {2.1, 3.9, 6.2, 8.1, 9.8};
        for (int i = 0; i < ys.length; i++) {
            rows.add(new Object[]{(double) (i + 1), ys[i]});
        }
        return NodeTestSupport.table(db,
                List.of(ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64")), rows);
    }

    @Test
    void pearsonCorrelatesOnePair() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            LazyPlan plan = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db, "{\"columns\":[\"x\",\"y\"],\"method\":\"pearson\"}"),
                    List.of(input));

            assertEquals(OUTPUT, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(1, data.rows());
            assertEquals(List.of("x|y"), pairs(data));
            List<String> pair = row(data, "x", "y");
            assertEquals("pearson", pair.get(2));
            assertEquals("4", pair.get(3));
            assertEquals(1.0, Double.parseDouble(pair.get(4)), 1e-9);
            assertEquals(0.0, Double.parseDouble(pair.get(5)), 1e-9);
            assertEquals(1.0, Double.parseDouble(pair.get(6)), 1e-9);
            assertEquals(1.0, Double.parseDouble(pair.get(7)), 1e-9);
            assertNull(pair.get(8));
        }
    }

    @Test
    void everyPairIsEmittedInColumnOrder() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            TableData data = run(db, input, "{\"columns\":[\"x\",\"y\",\"z\"]}");
            assertEquals(3, data.rows());
            assertEquals(List.of("x|y", "x|z", "y|z"), pairs(data));
        }
    }

    @Test
    void spearmanRanksNonLinearMonotonicDataWhilePearsonDoesNot() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            TableData spearman = run(db, input,
                    "{\"columns\":[\"x\",\"z\"],\"method\":\"spearman\"}");
            assertEquals("spearman", spearman.data().get(0).get(2));
            assertEquals(1.0, number(spearman, 0, 4), 1e-9);

            TableData pearson = run(db, input,
                    "{\"columns\":[\"x\",\"z\"],\"method\":\"pearson\"}");
            double value = number(pearson, 0, 4);
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

            TableData pearson = run(db, input, "{\"columns\":[\"a\",\"b\"]}");
            assertEquals(1.0, number(pearson, 0, 4), 1e-9);
            assertEquals("3", pearson.data().get(0).get(3));

            TableData spearman = run(db, input,
                    "{\"columns\":[\"a\",\"b\"],\"method\":\"spearman\"}");
            assertEquals(1.0, number(spearman, 0, 4), 1e-9);
        }
    }

    @Test
    void pearsonPValueAndFisherIntervalMatchTheReference() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = run(db, line(db), "{\"columns\":[\"x\",\"y\"]}");

            // Independent: r = 0.99880472868807146, t = 35.393440807216663 with df = 3.
            assertEquals(0.99880472868807146, number(data, 0, 4), 1e-12);
            assertEquals(4.9597035775228449e-05, number(data, 0, 5), 1e-12);
            assertEquals(0.98106023547141119, number(data, 0, 6), 1e-9);
            assertEquals(0.99992519526086376, number(data, 0, 7), 1e-9);
        }
    }

    @Test
    void kendallTauBHandlesTies() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("x", "Float64"), ColumnSpec.of("y", "Float64")),
                    List.of(
                            new Object[]{1.0, 1.0},
                            new Object[]{2.0, 2.0},
                            new Object[]{3.0, 2.0},
                            new Object[]{4.0, 3.0}));

            TableData data = run(db, input, "{\"columns\":[\"x\",\"y\"],\"method\":\"kendall\"}");
            assertEquals("kendall", data.data().get(0).get(2));
            // tau_b = 5 / sqrt(6 * 5) = 0.9128709291752769; z = 1.3155870289605438.
            assertEquals(0.9128709291752769, number(data, 0, 4), 1e-12);
            assertEquals(0.18831268948891644, number(data, 0, 5), 1e-9);
            assertEquals(-0.048792027384325198, number(data, 0, 6), 1e-9);
            assertEquals(1.0, number(data, 0, 7), 1e-9);
        }
    }

    @Test
    void partialCorrelationControlsForTheGivenColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1.0, 2.0, 1.0, "a"},
                    new Object[]{2.0, 1.0, 3.0, "b"},
                    new Object[]{3.0, 4.0, 2.0, "c"},
                    new Object[]{4.0, 3.0, 6.0, "d"},
                    new Object[]{5.0, 6.0, 4.0, "e"},
                    new Object[]{6.0, 5.0, 5.0, "f"}));

            TableData data = run(db, input,
                    "{\"columns\":[\"x\",\"y\"],\"partial\":[\"z\"]}");

            // r_xy = 0.8285714, r_xz = 0.7714286, r_yz = 0.3714286 -> partial 0.9174772.
            assertEquals(0.91747720319216741, number(data, 0, 4), 1e-9);
            assertEquals("6", data.data().get(0).get(3));
            assertEquals(0.028102475003227489, number(data, 0, 5), 1e-9);
            assertEquals(0.1847940283882554, number(data, 0, 6), 1e-9);
            assertEquals(0.99463060393110303, number(data, 0, 7), 1e-9);
            assertEquals("z", data.data().get(0).get(8));
        }
    }

    @Test
    void matrixOutputHasColumnPlusOneColumnPerVariable() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, List.of(
                    new Object[]{1.0, 2.0, 1.0, "a"},
                    new Object[]{2.0, 1.0, 3.0, "b"},
                    new Object[]{3.0, 4.0, 2.0, "c"},
                    new Object[]{4.0, 3.0, 6.0, "d"},
                    new Object[]{5.0, 6.0, 4.0, "e"},
                    new Object[]{6.0, 5.0, 5.0, "f"}));

            LazyPlan plan = new CorrelationExecutor().execute(
                    NodeTestSupport.context(db,
                            "{\"columns\":[\"x\",\"y\",\"z\"],\"output\":\"matrix\"}"),
                    List.of(input));
            assertEquals(List.of(
                    ColumnSpec.of("column", "Utf8"),
                    ColumnSpec.of("x", "Float64"),
                    ColumnSpec.of("y", "Float64"),
                    ColumnSpec.of("z", "Float64")), plan.columns());

            TableData data = plan.preview(10);
            assertEquals(3, data.rows());
            assertEquals(List.of("x", "y", "z"), data.data().stream().map(r -> r.get(0)).toList());
            for (int i = 0; i < 3; i++) {
                assertEquals(1.0, number(data, i, i + 1), 1e-9);
            }
            assertEquals(0.82857142857142863, number(data, 0, 2), 1e-9);
            assertEquals(0.77142857142857146, number(data, 0, 3), 1e-9);
            assertEquals(0.37142857142857144, number(data, 1, 3), 1e-9);
            assertEquals(number(data, 0, 2), number(data, 1, 1), 1e-9);
        }
    }

    @Test
    void tooFewObservationsLeaveThePValueAndIntervalNull() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db,
                    List.of(ColumnSpec.of("a", "Float64"), ColumnSpec.of("b", "Float64")),
                    List.of(new Object[]{1.0, 2.0}, new Object[]{2.0, 4.0}));

            TableData data = run(db, input, "{\"columns\":[\"a\",\"b\"]}");
            assertEquals(1.0, number(data, 0, 4), 1e-9);
            assertEquals("2", data.data().get(0).get(3));
            assertNull(data.data().get(0).get(5));
            assertNull(data.data().get(0).get(6));
            assertNull(data.data().get(0).get(7));
        }
    }

    @Test
    void alphaWidensOrNarrowsTheInterval() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData ninety = run(db, line(db), "{\"columns\":[\"x\",\"y\"],\"alpha\":0.1}");
            TableData ninetyNine = run(db, line(db), "{\"columns\":[\"x\",\"y\"],\"alpha\":0.01}");
            assertTrue(number(ninety, 0, 6) > number(ninetyNine, 0, 6));
            assertTrue(number(ninety, 0, 7) < number(ninetyNine, 0, 7));
        }
    }

    @Test
    void invalidColumnsAndMethodAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException single = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\"]}"));
            assertTrue(single.getMessage().contains("two"), single.getMessage());

            IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"nope\"]}"));
            assertTrue(unknown.getMessage().contains("nope"), unknown.getMessage());

            IllegalArgumentException nonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"label\"]}"));
            assertTrue(nonNumeric.getMessage().contains("numeric"), nonNumeric.getMessage());

            IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"x\"]}"));
            assertTrue(duplicate.getMessage().contains("duplicate"), duplicate.getMessage());

            IllegalArgumentException method = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"y\"],\"method\":\"distance\"}"));
            assertTrue(method.getMessage().contains("distance"), method.getMessage());

            IllegalArgumentException output = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input,
                            "{\"columns\":[\"x\",\"y\"],\"output\":\"wide\"}"));
            assertTrue(output.getMessage().contains("wide"), output.getMessage());

            IllegalArgumentException partial = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input,
                            "{\"columns\":[\"x\",\"y\"],\"partial\":[\"nope\"]}"));
            assertTrue(partial.getMessage().contains("nope"), partial.getMessage());

            IllegalArgumentException partialNonNumeric = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input,
                            "{\"columns\":[\"x\",\"y\"],\"partial\":[\"label\"]}"));
            assertTrue(partialNonNumeric.getMessage().contains("numeric"),
                    partialNonNumeric.getMessage());

            IllegalArgumentException partialOverlap = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input,
                            "{\"columns\":[\"x\",\"y\"],\"partial\":[\"x\"]}"));
            assertTrue(partialOverlap.getMessage().contains("x"), partialOverlap.getMessage());

            IllegalArgumentException alpha = assertThrows(IllegalArgumentException.class,
                    () -> run(db, input, "{\"columns\":[\"x\",\"y\"],\"alpha\":1.5}"));
            assertTrue(alpha.getMessage().contains("alpha"), alpha.getMessage());
        }
    }
}
