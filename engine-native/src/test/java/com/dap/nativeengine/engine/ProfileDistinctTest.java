package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /profile distinct: exact COUNT(DISTINCT) at or below the row cap, APPROX_COUNT_DISTINCT above it.
 *
 * <p>DuckDB 1.5.6 implements APPROX_COUNT_DISTINCT with a 64-byte HyperLogLog sketch (P=10,
 * duckdb#20916) whose standard error is ~3.2 %, so the 1 % tolerance promised by PARITY.md
 * cannot be asserted against this dependency. The large-table tests therefore pin the sketch
 * to its actually measured error and, more importantly, pin that the approximate path is the
 * one that runs above the cap (the exact value is not returned there).
 */
class ProfileDistinctTest {
    private static final int ROW_CAP = 100_000;

    @Test
    void smallTableCountsExactDistinctWithNullsAndEmptyStrings() throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("city", "Utf8"));
        List<Object[]> rows = List.of(
                new Object[]{"a"}, new Object[]{""}, new Object[]{"a"},
                new Object[]{null}, new Object[]{"b"}, new Object[]{""});
        try (DuckDbSession db = new DuckDbSession();
             ArrowTable table = ArrowTable.of(columns, rows)) {
            String name = db.load(table, "profile_small");
            LazyPlan plan = new LazyPlan(db, "SELECT * FROM " + DuckDbSession.quote(name), columns);

            ProfileData profile = plan.profile();

            assertEquals(6, profile.rows());
            assertEquals(1, profile.columns().size());
            ProfileColumn city = profile.columns().get(0);
            assertEquals("city", city.name());
            assertEquals("String", city.type());
            assertEquals(1, city.missing(), "NULL is missing but not a distinct value");
            assertEquals(3, city.distinct(), "a, the empty string and b are three values");
            assertNull(city.min());
            assertNull(city.max());
            assertNull(city.mean());
        }
    }

    @Test
    void atTheRowCapTheExactCountIsStillUsed() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = sequentialPlan(db, "profile_cap_exact", ROW_CAP);
            long approximate = approximateDistinct(plan);

            ProfileData profile = plan.profile();

            assertEquals(ROW_CAP, profile.rows());
            ProfileColumn id = profile.columns().get(0);
            assertEquals(ROW_CAP, id.distinct(), "at exactly 100 000 rows the exact count must be returned");
            assertNotEquals(approximate, id.distinct(),
                    "the approximate sketch reports " + approximate + " here, so the exact value proves the exact path");
        }
    }

    @Test
    void aboveTheRowCapTheApproximateCountIsUsed() throws Exception {
        int rows = 150_000;
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = sequentialPlan(db, "profile_cap_approx", rows);
            long approximate = approximateDistinct(plan);

            ProfileData profile = plan.profile();

            assertEquals(rows, profile.rows());
            ProfileColumn id = profile.columns().get(0);
            assertEquals(approximate, id.distinct(),
                    "above the cap the profile must return APPROX_COUNT_DISTINCT, not the exact " + rows);
            assertNotEquals(rows, approximate, "the test dataset must make the sketch differ from the exact count");
            assertTrue(relativeError(id.distinct(), rows) <= 0.10,
                    "approximate distinct " + id.distinct() + " for " + rows + " rows is outside the measured sketch error");
        }
    }

    @Test
    void duplicateHeavyLargeTableStaysCloseToTheExactCount() throws Exception {
        int rows = 150_000;
        int distinct = 100;
        List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Int64"));
        List<Object[]> data = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            data.add(new Object[]{(long) (i % distinct)});
        }
        try (DuckDbSession db = new DuckDbSession();
             ArrowTable table = ArrowTable.of(columns, data)) {
            String name = db.load(table, "profile_duplicates");
            LazyPlan plan = new LazyPlan(db, "SELECT * FROM " + DuckDbSession.quote(name), columns);
            long approximate = approximateDistinct(plan);

            ProfileData profile = plan.profile();

            assertEquals(rows, profile.rows());
            ProfileColumn v = profile.columns().get(0);
            assertEquals(approximate, v.distinct(),
                    "above the cap the duplicate-heavy column must also use APPROX_COUNT_DISTINCT");
            assertNotEquals(distinct, approximate, "the chosen dataset must make the sketch differ from the exact count");
            assertTrue(relativeError(v.distinct(), distinct) <= 0.05,
                    "approximate distinct " + v.distinct() + " must stay close to the exact " + distinct);
        }
    }

    private static LazyPlan sequentialPlan(DuckDbSession db, String tablePrefix, int rows) throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("id", "Int64"));
        List<Object[]> data = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            data.add(new Object[]{(long) i});
        }
        try (ArrowTable table = ArrowTable.of(columns, data)) {
            String name = db.load(table, tablePrefix);
            return new LazyPlan(db, "SELECT * FROM " + DuckDbSession.quote(name), columns);
        }
    }

    private static long approximateDistinct(LazyPlan plan) throws Exception {
        try (Connection connection = plan.session().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT APPROX_COUNT_DISTINCT(" + DuckDbSession.quote(plan.columns().get(0).name()) + ")"
                             + " FROM (" + plan.sql() + ") AS dap_plan")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static double relativeError(long estimate, long exact) {
        return Math.abs(estimate - exact) / (double) exact;
    }
}
