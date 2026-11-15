package com.dap.nativeengine.explore;

import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.io.IngestService;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/** Fixtures shared by the explore tests: the iris Parquet and ad-hoc Parquet tables. */
final class ExploreTestSupport {
    private ExploreTestSupport() {
    }

    /** iris.csv ingested into a throw-away Parquet cache under dir. */
    static Path iris(Path dir) throws Exception {
        Path csv = Path.of("..", "tests", "goldens-native", "data", "iris.csv")
                .toAbsolutePath().normalize();
        return new IngestService(dir.resolve("cache")).ingest(csv, "csv").path();
    }

    /** Write the rows of a SELECT into a Parquet file. */
    static Path parquet(Path dir, String name, String select) throws Exception {
        Path file = dir.resolve(name);
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + select + ") TO '"
                    + file.toString().replace("'", "''") + "' (FORMAT PARQUET)");
        }
        return file;
    }

    static ExploreResult.Column column(ExploreResult result, String name) {
        return result.columns().stream().filter(column -> column.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing column " + name));
    }
}
