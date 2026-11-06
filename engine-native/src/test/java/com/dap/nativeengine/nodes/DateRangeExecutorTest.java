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

class DateRangeExecutorTest {
    private static LazyPlan range(DuckDbSession db, String config) throws Exception {
        return new DateRangeExecutor().execute(NodeTestSupport.context(db, config), List.of());
    }

    @Test
    void dailyRangeIsInclusive() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = range(db, """
                    {"start":"2024-01-01","end":"2024-01-03","step":"1 day"}
                    """);

            assertEquals(List.of(ColumnSpec.of("date", "Timestamp")), plan.columns());
            TableData data = plan.preview(10);
            assertEquals(3L, data.rows());
            assertEquals(List.of("2024-01-01T00:00"), data.data().get(0));
            assertEquals(List.of("2024-01-02T00:00"), data.data().get(1));
            assertEquals(List.of("2024-01-03T00:00"), data.data().get(2));
        }
    }

    @Test
    void timestampsAcceptBothIsoFormsAndHourlySteps() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData data = range(db, """
                    {"start":"2024-01-01T00:00:00","end":"2024-01-01 03:00:00","step":"1 hour"}
                    """).preview(10);
            assertEquals(4L, data.rows());
            assertEquals(List.of("2024-01-01T00:00"), data.data().get(0));
            assertEquals(List.of("2024-01-01T03:00"), data.data().get(3));
        }
    }

    @Test
    void monthlyStepsAndSinglePointRangesWork() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            TableData months = range(db, """
                    {"start":"2024-01-15","end":"2024-04-15","step":"1 month"}
                    """).preview(10);
            assertEquals(4L, months.rows());
            assertEquals(List.of("2024-01-15T00:00"), months.data().get(0));
            assertEquals(List.of("2024-04-15T00:00"), months.data().get(3));

            TableData single = range(db, """
                    {"start":"2024-02-29","end":"2024-02-29","step":"1 day"}
                    """).preview(10);
            assertEquals(1L, single.rows());
            assertEquals(List.of("2024-02-29T00:00"), single.data().get(0));
        }
    }

    @Test
    void invalidConfigIsRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            IllegalArgumentException reversed = assertThrows(IllegalArgumentException.class,
                    () -> range(db, """
                            {"start":"2024-01-05","end":"2024-01-01","step":"1 day"}
                            """));
            assertTrue(reversed.getMessage().contains("after"), reversed.getMessage());

            IllegalArgumentException start = assertThrows(IllegalArgumentException.class,
                    () -> range(db, """
                            {"start":"01/02/2024","end":"2024-01-03","step":"1 day"}
                            """));
            assertTrue(start.getMessage().contains("start"), start.getMessage());

            IllegalArgumentException end = assertThrows(IllegalArgumentException.class,
                    () -> range(db, """
                            {"start":"2024-01-01","end":"tomorrow","step":"1 day"}
                            """));
            assertTrue(end.getMessage().contains("end"), end.getMessage());

            IllegalArgumentException step = assertThrows(IllegalArgumentException.class,
                    () -> range(db, """
                            {"start":"2024-01-01","end":"2024-01-03","step":"1 fortnight"}
                            """));
            assertTrue(step.getMessage().contains("fortnight"), step.getMessage());

            IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                    () -> range(db, """
                            {"start":"2024-01-01","end":"2024-01-03","step":"0 days"}
                            """));
            assertTrue(zero.getMessage().contains("positive"), zero.getMessage());

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> range(db, """
                            {"start":"2024-01-01","end":"2024-01-03"}
                            """));
            assertTrue(missing.getMessage().contains("step"), missing.getMessage());
        }
    }
}
