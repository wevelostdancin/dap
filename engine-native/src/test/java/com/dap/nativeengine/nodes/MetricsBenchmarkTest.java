package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeatable measurement of a report that renders table + profile + metrics over the same
 * one-million-row input. Timings are printed (not asserted) because they are machine-dependent;
 * the assertions pin the result so the benchmark cannot silently measure the wrong report.
 */
class MetricsBenchmarkTest {
    @TempDir
    Path dir;

    @Test
    void tableProfileMetricsOnMillionRows() throws Exception {
        List<ColumnSpec> columns = List.of(
                ColumnSpec.of("id", "Int64"),
                ColumnSpec.of("grp", "Int64"),
                ColumnSpec.of("amount", "Float64"));
        try (DuckDbSession db = new DuckDbSession(); Connection job = db.newConnection()) {
            try (Statement statement = job.createStatement()) {
                statement.execute("CREATE TABLE dap_bench AS SELECT i AS id, i % 7 AS grp, "
                        + "CAST(i AS DOUBLE) * 0.5 AS amount FROM range(1000000) AS t(i)");
            }
            LazyPlan input = new LazyPlan(db, "SELECT id, grp, amount FROM dap_bench", columns);
            Path file = dir.resolve("bench.md");
            String config = "{\"file\":\"" + NodeTestSupport.path(file) + "\",\"sections\":["
                    + "{\"kind\":\"table\",\"columns\":[\"id\"],\"limit\":3},"
                    + "{\"kind\":\"profile\",\"columns\":[\"grp\",\"amount\"]},"
                    + "{\"kind\":\"metrics\",\"columns\":[\"grp\",\"amount\"]}]}";
            NodeContext context = NodeTestSupport.context(db, job, config);
            long first = 0;
            long second = 0;
            long third = 0;
            for (int run = 0; run < 3; run++) {
                db.invalidateProfiles("wf");
                long start = System.nanoTime();
                new MarkdownReportExecutor().execute(context, List.of(input));
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                if (run == 0) {
                    first = elapsedMs;
                } else if (run == 1) {
                    second = elapsedMs;
                } else {
                    third = elapsedMs;
                }
            }
            System.out.printf("METRICS_BENCHMARK table+profile+metrics 1M rows: %d ms, %d ms, %d ms%n",
                    first, second, third);
            String markdown = Files.readString(file);
            assertTrue(markdown.contains("| rows | 1000000 |"), markdown.substring(0, 200));
            assertTrue(markdown.contains("| grp.missing | 0 |"), markdown);
        }
    }
}
