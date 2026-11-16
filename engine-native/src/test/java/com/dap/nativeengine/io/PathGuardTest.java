package com.dap.nativeengine.io;

import com.dap.nativeengine.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Path allowlist/denylist for file nodes, ingest and SQL literals (spec §8). */
class PathGuardTest {
    @TempDir
    Path dir;

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }

    private static ApiException rejection(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    private static void assertPathNotAllowed(ApiException failure, String expectedFragment) {
        assertEquals(400, failure.status(), failure.getMessage());
        assertEquals("path_not_allowed", failure.code(), failure.getMessage());
        assertTrue(failure.getMessage().contains(expectedFragment), failure.getMessage());
        assertTrue(failure.hint() != null && failure.hint().contains("allowed roots"),
                "hint must name the allowed roots: " + failure.hint());
    }

    @Test
    void allowsAFileUnderTheTempRoot() throws Exception {
        Path file = Files.writeString(dir.resolve("data.csv"), "a\n1\n");

        Path checked = PathGuard.fromEnv().checkRead(file.toString());

        assertEquals(file.toAbsolutePath().normalize(), checked);
    }

    @Test
    void rejectsTheSshDirectoryOfTheUser() {
        Path key = home().resolve(".ssh").resolve("id_ed25519");

        assertPathNotAllowed(rejection(() -> PathGuard.fromEnv().checkRead(key.toString())),
                "id_ed25519");
    }

    @Test
    void rejectsEtcPasswd() {
        assertPathNotAllowed(rejection(() -> PathGuard.fromEnv().checkRead("/etc/passwd")),
                "/etc/passwd");
    }

    @Test
    void rejectsDeniedFileEvenWhenTheHomeRootIsAllowed() {
        Path netrc = home().resolve(".netrc");
        assertPathNotAllowed(rejection(() -> PathGuard.fromEnv().checkRead(netrc.toString())),
                ".netrc");
    }

    @Test
    void rejectsSqlReadingADeniedPath() {
        PathGuard guard = PathGuard.fromEnv();

        ApiException failure = rejection(() -> guard.checkSql(
                "SELECT * FROM read_parquet('/etc/passwd')"));

        assertPathNotAllowed(failure, "/etc/passwd");
    }

    @Test
    void rejectsSqlWithAFileLiteralAfterFrom() {
        PathGuard guard = PathGuard.fromEnv();

        assertPathNotAllowed(rejection(() -> guard.checkSql("SELECT * FROM '/etc/passwd'")),
                "/etc/passwd");
        assertPathNotAllowed(rejection(() -> guard.checkSql(
                "SELECT * FROM read_csv(['" + dir + "/a.csv', '/etc/passwd'])")), "/etc/passwd");
        assertPathNotAllowed(rejection(() -> guard.checkSql(
                "SELECT * FROM glob('/etc/*')")), "/etc/*");
    }

    @Test
    void allowsSqlOverHttpAndS3() {
        PathGuard guard = PathGuard.fromEnv();

        guard.checkSql("SELECT * FROM read_parquet('https://example.com/a.parquet')");
        guard.checkSql("SELECT * FROM read_csv('s3://bucket/a.csv')");
        guard.checkSql("SELECT * FROM read_json_auto('http://localhost:1/a.json')");
    }

    @Test
    void allowsSqlOverTempAndHomeFiles() throws Exception {
        Path file = Files.writeString(dir.resolve("ok.csv"), "a\n1\n");
        PathGuard guard = PathGuard.fromEnv();

        guard.checkSql("SELECT * FROM read_csv('" + file + "')");
        guard.checkSql("SELECT * FROM read_csv('" + file.toString().replace("'", "''") + "')");
    }

    @Test
    void rejectsCopyToOutsideTheAllowlist() {
        PathGuard guard = PathGuard.fromEnv();

        assertPathNotAllowed(rejection(() -> guard.checkSql(
                "COPY (SELECT 1) TO '/etc/leak.parquet' (FORMAT PARQUET)")), "/etc/leak.parquet");
    }

    @Test
    void checkWriteAllowsTempAndRejectsEtc() throws Exception {
        Path target = dir.resolve("out.csv");
        PathGuard guard = PathGuard.fromEnv();

        assertEquals(target.toAbsolutePath().normalize(), guard.checkWrite(target.toString()));
        assertPathNotAllowed(rejection(() -> guard.checkWrite("/etc/out.csv")), "/etc/out.csv");
    }

    @Test
    void explicitAllowlistIsStrict() throws Exception {
        Path other = Files.createTempDirectory("dap-other");
        try {
            Path file = Files.writeString(other.resolve("x.csv"), "a\n1\n");
            PathGuard guard = new PathGuard(List.of(dir), List.of());

            assertPathNotAllowed(rejection(() -> guard.checkRead(file.toString())), "x.csv");
            assertEquals(dir.toAbsolutePath().normalize(), guard.checkRead(dir.resolve("..").resolve(dir.getFileName()).toString()));
        } finally {
            Files.deleteIfExists(other.resolve("x.csv"));
            Files.deleteIfExists(other);
        }
    }

    @Test
    void denylistWinsOverTheAllowlist() throws Exception {
        Path secretDir = Files.createDirectories(dir.resolve("secret"));
        Path secret = Files.writeString(secretDir.resolve("token"), "s");
        Path fine = Files.writeString(dir.resolve("fine.csv"), "a\n1\n");
        PathGuard guard = new PathGuard(List.of(dir), List.of(secretDir));

        assertPathNotAllowed(rejection(() -> guard.checkRead(secret.toString())), "token");
        assertEquals(fine.toAbsolutePath().normalize(), guard.checkRead(fine.toString()));
    }

    @Test
    void symlinkIntoADeniedDirectoryIsRejected() throws Exception {
        Path link = dir.resolve("passwd-link");
        try {
            Files.createSymbolicLink(link, Path.of("/etc/passwd"));
        } catch (UnsupportedOperationException | java.io.IOException unsupported) {
            return; // the platform forbids symlinks; the realpath branch is covered above
        }

        assertPathNotAllowed(rejection(() -> PathGuard.fromEnv().checkRead(link.toString())),
                "passwd-link");
    }

    @Test
    void fromEnvReadsAllowedAndDeniedRoots() throws Exception {
        Path allowed = Files.createDirectories(dir.resolve("allowed"));
        Path denied = Files.createDirectories(dir.resolve("denied"));
        PathGuard guard = PathGuard.fromEnv(Map.of(
                "DAP_ALLOWED_ROOTS", allowed.toString(),
                "DAP_DENY_ROOTS", denied.toString()));

        Path fine = Files.writeString(allowed.resolve("a.csv"), "a\n1\n");
        Path bad = Files.writeString(denied.resolve("b.csv"), "b\n1\n");
        assertEquals(fine.toAbsolutePath().normalize(), guard.checkRead(fine.toString()));
        assertPathNotAllowed(rejection(() -> guard.checkRead(bad.toString())), "b.csv");
        assertPathNotAllowed(rejection(() -> guard.checkRead("/etc/passwd")), "/etc/passwd");
    }

    @Test
    void allowedRootsAreReportedForHints() {
        PathGuard guard = new PathGuard(List.of(dir), List.of());

        assertEquals(1, guard.allowedRoots().size());
        assertTrue(guard.allowedRoots().get(0).toString().contains(dir.getFileName().toString()));
    }
}
