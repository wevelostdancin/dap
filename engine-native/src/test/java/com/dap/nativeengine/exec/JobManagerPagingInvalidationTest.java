package com.dap.nativeengine.exec;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A new run must not keep serving /table pages materialized by the previous run. */
class JobManagerPagingInvalidationTest {
    private static final String SQL = "SELECT id, v FROM mutate_src";
    private static final List<ColumnSpec> COLUMNS =
            List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("v", "Utf8"));

    private DuckDbSession db;
    private WorkflowStore workflows;
    private JobManager jobs;
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        db = new DuckDbSession();
        workflows = new WorkflowStore(10);
        Executors registry = new Executors().register(ExecTestSupport.fake("dap.mutate", (context, inputs) -> {
            int value = runs.incrementAndGet();
            db.execute("CREATE OR REPLACE TABLE mutate_src AS SELECT " + value + " AS id, 'v"
                    + value + "' AS v");
            return new LazyPlan(db, SQL, COLUMNS);
        }));
        jobs = new JobManager(workflows, registry);
    }

    @AfterEach
    void tearDown() {
        if (jobs != null) {
            jobs.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void startingAnotherRunInvalidatesTheCachedPage() throws Exception {
        Workflow workflow = workflows.create("s1", "mutate");
        String node = workflow.addNode("dap.mutate", "M", null).id();

        Job first = jobs.submit(workflow, db, 0);
        assertTrue(first.await(10_000));
        TableData before = db.page(SQL, COLUMNS, 10, 0, 0, workflow.id(), node);
        assertEquals("v1", before.data().get(0).get(1));

        Job second = jobs.submit(workflow, db, 0);
        assertTrue(second.await(10_000));
        TableData after = db.page(SQL, COLUMNS, 10, 0, 0, workflow.id(), node);
        assertEquals("v2", after.data().get(0).get(1), "a run must bump the page cache generation");
    }
}
