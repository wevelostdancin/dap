package com.dap.nativeengine;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.nodes.CsvReaderExecutor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.duckdb.DuckDBConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup warm-up: pays the first-run costs (JVM JIT, JDBC driver bootstrap, DuckDB
 * native init, CSV parsing through read_csv and preview) on a throwaway session after
 * the HTTP server is already listening, so the first user call does not.
 *
 * <p>The switch is the system property {@code dap.warmup} or, when it is absent, the
 * environment variable {@code DAP_WARMUP}; {@code 0} disables warm-up and anything
 * else keeps it on. Warm-up runs once per process, is best effort and never fails
 * startup: {@link NativeService} logs the failure and keeps serving.</p>
 */
final class WarmUp {
    static final String ENV = "DAP_WARMUP";
    static final String PROPERTY = "dap.warmup";
    /** One warm-up per process: the JIT and the driver state are process-wide. */
    private static final AtomicBoolean CLAIMED = new AtomicBoolean();

    /** What one warm-up run did: wall time and the previewed row count. */
    record Outcome(long elapsedMs, int rows) {
    }

    private WarmUp() {
    }

    /** Enabled unless the property or the environment says {@code 0}; the property wins. */
    static boolean enabled() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            return !"0".equals(property.strip());
        }
        return enabled(System.getenv());
    }

    /** Environment-only check, injectable for tests. */
    static boolean enabled(Map<String, String> env) {
        String raw = env.get(ENV);
        return raw == null || raw.isBlank() || !"0".equals(raw.strip());
    }

    /** Claim the single warm-up of this process; false when another service already did. */
    static boolean claim() {
        return CLAIMED.compareAndSet(false, true);
    }

    /** Diagnostics and tests: whether the process already claimed its warm-up. */
    static boolean claimed() {
        return CLAIMED.get();
    }

    /** Test seam: let the next {@link #claim()} win again. */
    static void reset() {
        CLAIMED.set(false);
    }

    /**
     * Run one warm-up on a throwaway session: tiny SQL, a few-row CSV parsed through
     * the real csv_reader path, describe and preview, then close and
     * delete the temp file.
     */
    static Outcome run(NativeService.Settings settings) throws Exception {
        long started = System.nanoTime();
        Path csv = null;
        DuckDbSession db = null;
        try {
            csv = tempCsv(settings.duckdbTempDirectory());
            db = new DuckDbSession(settings.duckdbMemoryLimit(), settings.duckdbThreads(),
                    settings.duckdbTempDirectory(), settings.duckdbExtensionDirectory());
            db.execute("SELECT 1");
            try (DuckDBConnection connection = db.newConnection()) {
                ObjectNode config = Json.object();
                config.put("file", csv.toString());
                config.put("header", true);
                NodeContext context = new NodeContext("warmup", "1:1", "warmup", config, db, connection);
                LazyPlan plan = new CsvReaderExecutor().execute(context, List.of());
                db.describe(connection, plan.sql());
                TableData table = plan.preview(10);
                return new Outcome((System.nanoTime() - started) / 1_000_000L, table.preview());
            }
        } finally {
            if (db != null) {
                db.close();
            }
            deleteQuietly(csv);
        }
    }

    /** A few rows in the configured DuckDB temp directory, else the JVM temp directory. */
    private static Path tempCsv(String tempDirectory) throws IOException {
        Path directory = tempDirectory == null || tempDirectory.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(tempDirectory);
        Files.createDirectories(directory);
        Path csv = Files.createTempFile(directory, "dap-warmup-", ".csv");
        Files.writeString(csv, "id,name,value\n1,alpha,1.5\n2,beta,2.5\n3,gamma,\n",
                StandardCharsets.UTF_8);
        return csv;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            System.err.println("dap-native: cannot delete warm-up file " + file + ": "
                    + failure.getMessage());
        }
    }
}
