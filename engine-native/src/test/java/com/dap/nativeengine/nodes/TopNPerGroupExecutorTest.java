package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopNPerGroupExecutorTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("grp", "Utf8"),
            ColumnSpec.of("v", "Int64"));

    private static final List<Object[]> ROWS = List.of(
            new Object[]{"a", 1L},
            new Object[]{"a", 2L},
            new Object[]{"a", 3L},
            new Object[]{"b", 10L},
            new Object[]{"b", 20L});

    private static List<String> canonicalRows(TableData data) {
        return data.data().stream().map(row -> {
            StringJoiner joiner = new StringJoiner("|");
            for (String value : row) {
                joiner.add(value == null ? "<null>" : value);
            }
            return joiner.toString();
        }).sorted().toList();
    }

    @Test
    void keepsTheTopNRowsPerGroup() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"groupBy":["grp"],"orderBy":[{"column":"v","direction":"desc"}],"n":2}
                    """);

            LazyPlan plan = new TopNPerGroupExecutor().execute(context, List.of(input));

            assertEquals(COLUMNS, plan.columns());
            TableData data = plan.preview(10);
            assertEquals(4, data.rows());
            assertEquals(List.of("a|2", "a|3", "b|10", "b|20"), canonicalRows(data));
        }
    }

    @Test
    void nLargerThanAGroupKeepsEveryRow() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);
            NodeContext context = NodeTestSupport.context(db, """
                    {"groupBy":["grp"],"orderBy":[{"column":"v","direction":"asc"}],"n":100}
                    """);

            LazyPlan plan = new TopNPerGroupExecutor().execute(context, List.of(input));

            assertEquals(5, plan.preview(10).rows());
        }
    }

    @Test
    void nMustBePositive() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                    () -> new TopNPerGroupExecutor().execute(NodeTestSupport.context(db, """
                            {"groupBy":["grp"],"orderBy":[{"column":"v","direction":"desc"}],"n":0}
                            """), List.of(input)));
            assertTrue(zero.getMessage().contains("greater than zero"), zero.getMessage());

            IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                    () -> new TopNPerGroupExecutor().execute(NodeTestSupport.context(db, """
                            {"groupBy":["grp"],"orderBy":[{"column":"v","direction":"desc"}],"n":-3}
                            """), List.of(input)));
            assertTrue(negative.getMessage().contains("greater than zero"), negative.getMessage());

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> new TopNPerGroupExecutor().execute(NodeTestSupport.context(db, """
                            {"groupBy":["grp"],"orderBy":[{"column":"v","direction":"desc"}]}
                            """), List.of(input)));
            assertTrue(missing.getMessage().contains("'n'"), missing.getMessage());
        }
    }

    @Test
    void invalidConfigIsRejectedWithTheProblemNamed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan input = NodeTestSupport.table(db, COLUMNS, ROWS);

            IllegalArgumentException unknownGroup = assertThrows(IllegalArgumentException.class,
                    () -> new TopNPerGroupExecutor().execute(NodeTestSupport.context(db, """
                            {"groupBy":["nope"],"orderBy":[{"column":"v","direction":"desc"}],"n":1}
                            """), List.of(input)));
            assertTrue(unknownGroup.getMessage().contains("nope"), unknownGroup.getMessage());

            IllegalArgumentException emptyGroup = assertThrows(IllegalArgumentException.class,
                    () -> new TopNPerGroupExecutor().execute(NodeTestSupport.context(db, """
                            {"groupBy":[],"orderBy":[{"column":"v","direction":"desc"}],"n":1}
                            """), List.of(input)));
            assertTrue(emptyGroup.getMessage().contains("'groupBy'"), emptyGroup.getMessage());

            IllegalArgumentException badDirection = assertThrows(IllegalArgumentException.class,
                    () -> new TopNPerGroupExecutor().execute(NodeTestSupport.context(db, """
                            {"groupBy":["grp"],"orderBy":[{"column":"v","direction":"sideways"}],"n":1}
                            """), List.of(input)));
            assertTrue(badDirection.getMessage().contains("sideways"), badDirection.getMessage());
        }
    }
}
