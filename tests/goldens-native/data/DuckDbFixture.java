import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Creates the DuckDB twin of fixture.db; run through make_db_fixtures.py. */
public final class DuckDbFixture {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]).toAbsolutePath();
        Files.deleteIfExists(path);
        // Create through ATTACH with a small block size so the committed fixture stays tiny.
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("ATTACH '" + path + "' AS fixture (BLOCK_SIZE 16384)");
            statement.execute("CREATE TABLE fixture.items "
                    + "(id BIGINT, name VARCHAR, score DOUBLE, created VARCHAR)");
            statement.execute("INSERT INTO fixture.items VALUES "
                    + "(1, 'alpha', 1.5, '2026-01-01'), "
                    + "(2, 'beta', 2.5, '2026-01-02'), "
                    + "(3, 'gamma', NULL, '2026-01-03')");
            statement.execute("CHECKPOINT fixture");
            statement.execute("DETACH fixture");
        }
    }
}
