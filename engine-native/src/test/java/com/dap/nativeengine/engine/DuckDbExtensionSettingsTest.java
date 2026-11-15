package com.dap.nativeengine.engine;

import com.dap.nativeengine.NativeService;
import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.catalog.NodeRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DuckDB extension directory: the session database applies it and never auto-installs;
 * /v1/health reports what is installed; the installer CLI mode fills the directory.
 */
class DuckDbExtensionSettingsTest {
    @TempDir
    Path tempDir;

    @Test
    void extensionDirectoryIsAppliedOnOpen() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("duckdb-ext"));
        try (DuckDbSession db = new DuckDbSession(null, null, null, directory.toString())) {
            assertEquals(directory.toString(), setting(db, "extension_directory"));
            assertEquals("false", setting(db, "autoinstall_known_extensions"),
                    "the engine must not download extensions on its own");
        }
    }

    @Test
    void healthListsInstalledExtensions() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("health-ext"));
        Path version = Files.createDirectories(directory.resolve("v1.5.6").resolve("linux_amd64"));
        Files.write(version.resolve("sqlite_scanner.duckdb_extension"), new byte[0]);
        Files.write(version.resolve("sqlite_scanner.duckdb_extension.info"), new byte[0]);
        Files.write(version.resolve("httpfs.duckdb_extension"), new byte[0]);

        NativeService service = new NativeService(
                new NativeService.Settings(100, 600, "127.0.0.1", 0, null, null, null,
                        900, 60, 64, 1800, 200, directory.toString()),
                new NodeRegistry(), NativeService.defaultExecutors());
        service.start(0);
        try {
            JsonNode health = health(service);
            JsonNode installed = health.path("extensions").path("installed");
            assertTrue(installed.isArray(), health.toString());
            assertEquals(List.of("httpfs", "sqlite_scanner"), names(installed), health.toString());
        } finally {
            service.close();
        }
    }

    @Test
    @Tag("network")
    void installerWritesTheExtensionIntoTheDirectory() throws Exception {
        assumeTrue(online(), "network is unavailable: skipping the extension install");
        Path directory = Files.createDirectories(tempDir.resolve("install-ext"));

        int exitCode = NativeService.installExtensions(directory.toString(), List.of("sqlite"));

        assertEquals(0, exitCode, "the installer must succeed for sqlite");
        try (Stream<Path> files = Files.walk(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                            .equals("sqlite_scanner.duckdb_extension")),
                    "the installer must create sqlite_scanner.duckdb_extension under " + directory);
        }
    }

    // --------------------------------------------------------------- helpers

    private static String setting(DuckDbSession db, String name) throws Exception {
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT current_setting('" + name + "')")) {
            result.next();
            return result.getString(1);
        }
    }

    private static JsonNode health(NativeService service) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + service.port() + "/v1/health"))
                .header("X-DAP-Session", "ext-" + service.port())
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return Json.MAPPER.readTree(response.body());
    }

    private static List<String> names(JsonNode installed) {
        List<String> names = new java.util.ArrayList<>();
        installed.forEach(node -> names.add(node.asText()));
        return names;
    }

    private static boolean online() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("extensions.duckdb.org", 443), 2000);
            return true;
        } catch (IOException offline) {
            return false;
        }
    }
}
