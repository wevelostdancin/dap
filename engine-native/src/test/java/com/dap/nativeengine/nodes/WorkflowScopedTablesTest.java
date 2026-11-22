package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Node materializations must have stable, workflow-scoped names: re-runs replace their own
 * table and deleting one workflow's tables must not break another's plan.
 */
class WorkflowScopedTablesTest {

    @Test
    void tenSurvivalRunsKeepOneTable() throws Exception {
        try (DuckDbSession db = new DuckDbSession();
             Connection job = db.newConnection()) {
            for (int run = 0; run < 10; run++) {
                survivalPlan(db, job, "wf-a", "1:1").preview(100);
            }
            assertEquals(1, countTables(db, "survival_wf_a_1_1"),
                    "ten runs must replace one stable table, not accumulate ten");
            assertEquals(2, survivalPlan(db, job, "wf-a", "1:1").preview(100).rows(),
                    "the replaced table must still hold the last run's curve");
        }
    }

    @Test
    void deletingOneWorkflowsMappingsLeavesTheOtherWorkflowReadable() throws Exception {
        try (DuckDbSession db = new DuckDbSession();
             Connection job = db.newConnection()) {
            LazyPlan first = textPlan(db, job, "wf-first", "1:1");
            LazyPlan second = textPlan(db, job, "wf-second", "1:1");
            assertEquals(1, countTables(db, "textnorm_wf_first_1_1_0"));
            assertEquals(1, countTables(db, "textnorm_wf_second_1_1_0"));
            assertEquals("cafe", first.preview(10).data().get(0).get(2));
            assertEquals("cafe", second.preview(10).data().get(0).get(2));

            db.dropTablesForWorkflow("wf-first");

            assertEquals(0, countTables(db, "textnorm_wf_first_1_1_0"));
            assertEquals(1, countTables(db, "textnorm_wf_second_1_1_0"),
                    "a global textnorm_ prefix would drop the other workflow's mapping");
            assertEquals("cafe", second.preview(10).data().get(0).get(2),
                    "the surviving workflow's plan must still read its mapping table");
        }
    }

    @Test
    void droppingAWorkflowRemovesItsSurvivalAndTimeseriesMaterializations() throws Exception {
        List<ColumnSpec> columns = List.of(ColumnSpec.of("x", "Int64"));
        try (DuckDbSession db = new DuckDbSession();
             ArrowTable table = ArrowTable.of(columns, List.<Object[]>of(new Object[]{1L}))) {
            assertEquals("survival_wf_drop_1_1",
                    db.load(table, "survival", "wf-drop", "1:1"));
            assertEquals("timeseries_wf_drop_2_1",
                    db.load(table, "timeseries", "wf-drop", "2:1"));
            db.load(table, "survival", "wf-keep", "1:1");

            db.dropTablesForWorkflow("wf-drop");

            assertEquals(0, countTables(db, "survival_wf_drop_"));
            assertEquals(0, countTables(db, "timeseries_wf_drop_"));
            assertEquals(1, countTables(db, "survival_wf_keep_"));
        }
    }

    private static LazyPlan survivalPlan(DuckDbSession db, Connection job, String workflow, String node)
            throws Exception {
        LazyPlan input = NodeTestSupport.table(db,
                List.of(ColumnSpec.of("time", "Float64"), ColumnSpec.of("event", "Int64")),
                List.<Object[]>of(new Object[]{1.0, 1L}, new Object[]{2.0, 0L}, new Object[]{3.0, 1L}));
        return new SurvivalExecutor().execute(context(workflow, node, job, db,
                "{\"time\":\"time\",\"event\":\"event\"}"), List.of(input));
    }

    private static LazyPlan textPlan(DuckDbSession db, Connection job, String workflow, String node)
            throws Exception {
        LazyPlan input = NodeTestSupport.table(db,
                List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("name", "Utf8")),
                List.<Object[]>of(new Object[]{1L, "Café"}));
        return new TextNormalizeExecutor().execute(context(workflow, node, job, db,
                "{\"columns\":[\"name\"],\"strip_accents\":true,\"case\":\"lower\"}"),
                List.of(input));
    }

    private static NodeContext context(String workflow, String node, Connection job,
                                       DuckDbSession db, String config) throws Exception {
        ObjectNode json = (ObjectNode) Json.parse(config);
        return new NodeContext(workflow, node, "session", json, db, job);
    }

    private static int countTables(DuckDbSession db, String prefix) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE starts_with(table_name, '"
                             + prefix + "')")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
