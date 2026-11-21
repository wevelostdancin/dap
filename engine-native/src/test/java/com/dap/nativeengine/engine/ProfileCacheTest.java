package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Profiles are shared across a run, but never across runs, nodes or column selections. */
class ProfileCacheTest {
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("id", "Int64"), ColumnSpec.of("city", "Utf8"));

    @Test
    void reuseIsScopedToRunNodeAndColumns() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, "cache_scope");
            ProfileData first = profile(plan, "wf", "node", COLUMNS);
            assertSame(first, profile(plan, "wf", "node", COLUMNS),
                    "same run/node/columns hits the cache");
            assertNotSame(first, profile(plan, "wf", "other", COLUMNS),
                    "another node has its own entry");
            assertNotSame(first, profile(plan, "other-wf", "node", COLUMNS),
                    "another workflow has its own entry");
            assertNotSame(first, profile(plan, "wf", "node", COLUMNS.subList(0, 1)),
                    "a narrower column selection has its own entry");
            assertEquals(first.columns(), profile(plan, "wf", "node", COLUMNS).columns());

            db.invalidateProfiles("wf");
            assertNotSame(first, profile(plan, "wf", "node", COLUMNS), "a new run recomputes");
            assertSame(profile(plan, "other-wf", "node", COLUMNS),
                    profile(plan, "other-wf", "node", COLUMNS), "other workflows are not invalidated");
        }
    }

    @Test
    void changedPlanSqlDoesNotHitAnOldEntry() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            LazyPlan plan = plan(db, "cache_plan");
            ProfileData first = profile(plan, "wf", "node", COLUMNS);
            LazyPlan filtered = new LazyPlan(db, plan.sql() + " WHERE \"id\" > 1", COLUMNS);
            assertNotSame(first, profile(filtered, "wf", "node", COLUMNS),
                    "a config change producing new SQL must not reuse the old profile");
        }
    }

    @Test
    void concurrentReadersShareOneEntry() throws Exception {
        try (DuckDbSession db = new DuckDbSession();
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            LazyPlan plan = plan(db, "cache_concurrent");
            List<Future<ProfileData>> reads = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                reads.add(pool.submit(() -> profile(plan, "wf", "node", COLUMNS)));
            }
            ProfileData first = reads.get(0).get();
            for (Future<ProfileData> read : reads) {
                assertEquals(first, read.get(), "parallel materializations must return the same data");
            }
        }
    }

    /** A plan over an explicit column list, as the report profile section builds. */
    private static ProfileData profile(LazyPlan plan, String workflowId, String nodeId,
                                       List<ColumnSpec> columns) throws SQLException {
        LazyPlan selected = columns.equals(plan.columns())
                ? plan
                : new LazyPlan(plan.session(), plan.sql(), columns);
        return selected.profile(0L, workflowId, nodeId);
    }

    private static LazyPlan plan(DuckDbSession db, String table) throws Exception {
        List<Object[]> rows = List.of(new Object[]{1L, "a"}, new Object[]{2L, null}, new Object[]{3L, "b"});
        try (ArrowTable arrow = ArrowTable.of(COLUMNS, rows)) {
            String name = db.load(arrow, table);
            return new LazyPlan(db, "SELECT * FROM " + DuckDbSession.quote(name), COLUMNS);
        }
    }
}
