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
 * /profile distinct: exact COUNT(DISTINCT) up to the 100k cap, APPROX_COUNT_DISTINCT only for
 * genuinely large cardinality.
 *
 * <p>DuckDB 1.5.6 implements APPROX_COUNT_DISTINCT with a 64-byte HyperLogLog sketch (P=10,
 * duckdb#20916) whose standard error is ~3.2 %. Above the row cap the profile therefore
 * refines the sketch with an exact count whenever the estimate is still near the cap, so the
 * "exact up to 100k distinct values" promise holds; the tests pin both the refined case and
 * the large-cardinality case where the sketch is returned (within its measured ~10 % error).
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
    void duplicateHeavyLargeTableIsRefinedToTheExactCount() throws Exception {
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
            assertEquals(distinct, v.distinct(),
                    "a low-cardinality column above the row cap must be refined to the exact count");
            assertNotEquals(distinct, approximate, "the sketch is imprecise here, which is why refinement runs");
        }
    }

    @Test
    void lowCardinalityAboveTheRowCapIsRefinedToTheExactCount() throws Exception {
        int rows = 120_000;
        int distinct = 90_000;
        List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Int64"));
        List<Object[]> data = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            data.add(new Object[]{(long) (i % distinct)});
        }
        try (DuckDbSession db = new DuckDbSession();
             ArrowTable table = ArrowTable.of(columns, data)) {
            String name = db.load(table, "profile_low_cardinality");
            LazyPlan plan = new LazyPlan(db, "SELECT * FROM " + DuckDbSession.quote(name), columns);

            ProfileData profile = plan.profile();

            assertEquals(rows, profile.rows());
            assertEquals(distinct, profile.columns().get(0).distinct(),
                    "90k distinct values are below the cap, so the exact count must be returned");
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
