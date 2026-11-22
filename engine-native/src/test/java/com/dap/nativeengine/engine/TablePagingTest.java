package com.dap.nativeengine.engine;

import com.dap.nativeengine.api.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stable paging of a lazy plan: one run, one order, no duplicates or lost rows. */
class TablePagingTest {
    private static final List<ColumnSpec> COLUMNS =
            List.of(ColumnSpec.of("id", "Int64"), ColumnSpec.of("v", "Utf8"));

    private DuckDbSession db;

    @BeforeEach
    void open() throws Exception {
        db = new DuckDbSession();
    }

    @AfterEach
    void close() {
        if (db != null) {
            db.close();
        }
        System.clearProperty("dap.table.materialize.maxRows");
        System.clearProperty("dap.table.materialize.budgetRows");
    }

    @Test
    void samePageCalledTwiceKeepsTheSameRowOrder() throws Exception {
        source(60);
        // An order that really changes between executions: without a materialization the
        // two calls below return different rows, which is the paging bug being fixed.
        String sql = "SELECT id, v FROM src ORDER BY random()";

        TableData first = db.page(sql, COLUMNS, 20, 0, 0, "wf-1", "1:1");
        TableData second = db.page(sql, COLUMNS, 20, 0, 0, "wf-1", "1:1");

        assertEquals(60, first.rows());
        assertFalse(first.data().isEmpty());
        assertEquals(first.data(), second.data());
    }

    @Test
    void pagesStitchIntoTheFullListWithoutDuplicatesOrGaps() throws Exception {
        source(65);
        String sql = "SELECT id, v FROM src ORDER BY random()";

        List<String> stitched = new ArrayList<>();
        for (int offset = 0; ; offset += 20) {
            TableData page = db.page(sql, COLUMNS, 20, offset, 0, "wf-1", "1:1");
            assertEquals(65, page.rows());
            for (List<String> row : page.data()) {
                stitched.add(row.get(0));
            }
            if (page.data().size() < 20) {
                break;
            }
        }

        assertEquals(65, stitched.size());
        assertEquals(new TreeSet<>(ids(sql)), new TreeSet<>(stitched));
    }

    @Test
    void aboveTheMaterializationLimitPagesStayDeterministicAndUncached() throws Exception {
        System.setProperty("dap.table.materialize.maxRows", "3");
        db.close();
        db = new DuckDbSession();
        source(10);
        String sql = "SELECT id, v FROM src ORDER BY random()";

        TableData first = db.page(sql, COLUMNS, 3, 0, 0, "wf-1", "1:1");
        TableData second = db.page(sql, COLUMNS, 3, 0, 0, "wf-1", "1:1");
        assertEquals(10, first.rows());
        assertEquals(first.data(), second.data(), "ORDER BY ALL must make the fallback deterministic");

        db.execute("UPDATE src SET v = 'changed' WHERE id = 1");
        TableData third = db.page(sql, COLUMNS, 3, 0, 0, "wf-1", "1:1");
        assertTrue(third.data().stream().anyMatch(row -> "changed".equals(row.get(1))),
                "above the limit every page must re-read the plan, not a stale cache entry");
    }

    @Test
    void differentPageSqlGetsItsOwnMaterialization() throws Exception {
        source(10);
        String plan = "SELECT id, v FROM src";
        String all = "SELECT \"id\", \"v\" FROM (" + plan + ") AS dap_projection";
        String filtered = "SELECT \"id\" FROM (" + plan + ") AS dap_filtered WHERE \"id\" > 5";

        TableData everything = db.page(all, COLUMNS, 20, 0, 0, "wf-1", "1:1");
        TableData subset = db.page(filtered, List.of(COLUMNS.get(0)), 20, 0, 0, "wf-1", "1:1");

        assertEquals(10, everything.rows());
        assertEquals(10, everything.data().size());
        assertEquals(5, subset.rows());
        assertEquals(5, subset.data().size());
        assertTrue(subset.data().stream().allMatch(row -> Long.parseLong(row.get(0)) > 5));
    }

    @Test
    void invalidatingTheWorkflowMakesChangedDataVisible() throws Exception {
        source(3);
        String sql = "SELECT id, v FROM src";
        assertEquals("v1", db.page(sql, COLUMNS, 3, 0, 0, "wf-1", "1:1").data().get(0).get(1));

        db.execute("UPDATE src SET v = 'changed' WHERE id = 1");
        assertEquals("v1", db.page(sql, COLUMNS, 3, 0, 0, "wf-1", "1:1").data().get(0).get(1),
                "the cached page must not see the change mid-run");

        db.invalidateTables("wf-1");
        assertEquals("changed", db.page(sql, COLUMNS, 3, 0, 0, "wf-1", "1:1").data().get(0).get(1));
    }

    @Test
    void rowBudgetEvictsOldestOfThreeTwoMillionRowTables() throws Exception {
        System.setProperty("dap.table.materialize.budgetRows", "4000000");
        db.close();
        db = new DuckDbSession();
        List<ColumnSpec> idColumn = List.of(ColumnSpec.of("id", "Int64"));

        String first = "SELECT i AS id FROM range(1, 2000001) t(i) /* page 0 */";
        assertEquals(2_000_000, db.page(first, idColumn, 1, 0, 0, "wf-1", "1:1").rows());
        String firstTable = pageTableNames().get(0);

        for (int index = 1; index <= 2; index++) {
            assertEquals(2_000_000, db.page(
                    "SELECT i AS id FROM range(1, 2000001) t(i) /* page " + index + " */",
                    idColumn, 1, 0, 0, "wf-1", "1:1").rows());
        }

        List<String> names = pageTableNames();
        assertEquals(2, names.size(), "a 4M row budget holds at most two 2M row tables");
        assertFalse(names.contains(firstTable), "the oldest table must be evicted with its rows");
    }

    @Test
    void tableLargerThanTheBudgetIsNotMaterialized() throws Exception {
        System.setProperty("dap.table.materialize.budgetRows", "3");
        db.close();
        db = new DuckDbSession();
        source(5);
        String sql = "SELECT id, v FROM src ORDER BY random()";

        TableData first = db.page(sql, COLUMNS, 2, 0, 0, "wf-1", "1:1");
        TableData second = db.page(sql, COLUMNS, 2, 0, 0, "wf-1", "1:1");

        assertEquals(5, first.rows());
        assertEquals(first.data(), second.data(), "the fallback must still be deterministic");
        assertEquals(0, tablesStartingWith("dap_page_"), "an oversized table must be dropped");
    }

    @Test
    void droppingTheWorkflowDropsItsOwnPageTables() throws Exception {
        source(3);
        String sql = "SELECT id, v FROM src";
        db.page(sql, COLUMNS, 2, 0, 0, "wf-1", "1:1");
        db.page(sql, COLUMNS, 2, 0, 0, "wf-2", "1:1");
        String wf1Table = pageTableNames().stream()
                .filter(name -> name.startsWith("dap_page_wf_1_")).findFirst().orElseThrow();
        assertTrue(tableExists(wf1Table), "the dap_page_* table must be visible before deletion");

        db.dropTablesForWorkflow("wf-1");

        assertFalse(tableExists(wf1Table), "information_schema must no longer list wf-1's page table");
        assertEquals(1, tablesStartingWith("dap_page_wf_2_"), "wf-2's table must survive");
        assertEquals(3, db.page(sql, COLUMNS, 2, 0, 0, "wf-1", "1:1").rows());
    }

    @Test
    void serviceColumnIsHiddenInBothPagingPaths() throws Exception {
        source(5);
        String sql = "SELECT id, v FROM src";
        TableData materialized = db.page(sql, COLUMNS, 5, 0, 0, "wf-1", "1:1");
        assertHidden(materialized);
        String internal = pageTableNames().get(0);
        assertTrue(columnNames("SELECT rowid AS \"__dap_rid\", * FROM "
                        + DuckDbSession.quote(internal) + " LIMIT 0").contains("__dap_rid"),
                "the hidden key exists in the page relation but not in the response");

        System.setProperty("dap.table.materialize.maxRows", "2");
        db.close();
        db = new DuckDbSession();
        source(5);
        TableData fallback = db.page(sql, COLUMNS, 5, 0, 0, "wf-1", "1:1");
        assertHidden(fallback);
        assertEquals(0, tablesStartingWith("dap_page_"), "ORDER BY ALL path must not materialize");
    }

    private static void assertHidden(TableData page) {
        assertEquals(List.of("id", "v"), page.columns().stream().map(ColumnSpec::name).toList());
        assertTrue(page.data().stream().allMatch(row -> row.size() == 2));
        assertFalse(Json.write(page).contains("__dap_rid"));
    }

    private List<String> pageTableNames() throws Exception {
        return tableNamesStartingWith("dap_page_");
    }

    private boolean tableExists(String name) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_name = '" + name + "'")) {
            result.next();
            return result.getInt(1) == 1;
        }
    }

    private int tablesStartingWith(String prefix) throws Exception {
        return tableNamesStartingWith(prefix).size();
    }

    private List<String> tableNamesStartingWith(String prefix) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                     + "WHERE starts_with(table_name, '" + prefix + "') ORDER BY table_name")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private List<String> columnNames(String relationSql) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(relationSql)) {
            ResultSetMetaData metadata = result.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                names.add(metadata.getColumnName(index));
            }
        }
        return names;
    }

    private void source(int rows) throws Exception {
        db.execute("DROP TABLE IF EXISTS src");
        db.execute("CREATE TABLE src AS SELECT i AS id, 'v' || i AS v FROM range(1, "
                + (rows + 1) + ") t(i)");
    }

    private List<String> ids(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT \"id\" FROM (" + LazyPlan.stripSemicolon(sql) + ") AS p")) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }
}
