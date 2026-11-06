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

class SampleRowsExecutorTest {
    private static LazyPlan input(DuckDbSession db) throws Exception {
        return NodeTestSupport.table(db, List.of(ColumnSpec.of("id", "Int64")),
                List.of(new Object[]{1L}, new Object[]{2L}, new Object[]{3L},
                        new Object[]{4L}, new Object[]{5L}));
    }

    private static List<String> ids(LazyPlan plan) throws Exception {
        return plan.preview(10).data().stream().map(row -> row.get(0)).toList();
    }

    @Test
    void headKeepsTheFirstNRows() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new SampleRowsExecutor().execute(NodeTestSupport.context(db, """
                    {"mode":"head","n":2}
                    """), List.of(input(db)));

            assertEquals(List.of(ColumnSpec.of("id", "Int64")), plan.columns());
            assertEquals(List.of("1", "2"), ids(plan));
        }
    }

    @Test
    void randomSampleWithSeedIsReproducibleAndBounded() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);
            NodeContext context = NodeTestSupport.context(db, """
                    {"mode":"random","n":2,"seed":42}
                    """);
            LazyPlan first = new SampleRowsExecutor().execute(context, List.of(input));
            LazyPlan second = new SampleRowsExecutor().execute(context, List.of(input));

            List<String> firstIds = ids(first);
            assertEquals(2, firstIds.size());
            assertEquals(firstIds, ids(second));
            assertTrue(List.of("1", "2", "3", "4", "5").containsAll(firstIds), firstIds.toString());
        }
    }

    @Test
    void randomSampleLargerThanTheTableKeepsEverything() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = new SampleRowsExecutor().execute(NodeTestSupport.context(db, """
                    {"mode":"random","n":10}
                    """), List.of(input(db)));

            assertEquals(List.of("1", "2", "3", "4", "5"), ids(plan).stream().sorted().toList());
        }
    }

    @Test
    void nonPositiveNUnknownModeAndFractionalSeedAreRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = input(db);

            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                    () -> new SampleRowsExecutor().execute(NodeTestSupport.context(db, """
                            {"mode":"head","n":0}
                            """), List.of(input)));
            assertTrue(zero.getMessage().contains("positive"), zero.getMessage());

            IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                    () -> new SampleRowsExecutor().execute(NodeTestSupport.context(db, """
                            {"mode":"head","n":-3}
                            """), List.of(input)));
            assertTrue(negative.getMessage().contains("positive"), negative.getMessage());

            IllegalArgumentException fraction = assertThrows(IllegalArgumentException.class,
                    () -> new SampleRowsExecutor().execute(NodeTestSupport.context(db, """
                            {"mode":"head","n":1.5}
                            """), List.of(input)));
            assertTrue(fraction.getMessage().contains("integer"), fraction.getMessage());

            IllegalArgumentException mode = assertThrows(IllegalArgumentException.class,
                    () -> new SampleRowsExecutor().execute(NodeTestSupport.context(db, """
                            {"mode":"tail","n":2}
                            """), List.of(input)));
            assertTrue(mode.getMessage().contains("tail"), mode.getMessage());

            IllegalArgumentException seed = assertThrows(IllegalArgumentException.class,
                    () -> new SampleRowsExecutor().execute(NodeTestSupport.context(db, """
                            {"mode":"random","n":2,"seed":1.5}
                            """), List.of(input)));
            assertTrue(seed.getMessage().contains("seed"), seed.getMessage());
        }
    }
}
