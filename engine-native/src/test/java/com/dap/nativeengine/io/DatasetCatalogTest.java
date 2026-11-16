package com.dap.nativeengine.io;

import com.dap.nativeengine.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Persistence, versioning and profile cache of the dataset catalog. */
class DatasetCatalogTest {
    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path catalogFile() {
        return dir.resolve("datasets.json");
    }

    private DatasetCatalog catalog() {
        return catalog(catalogFile());
    }

    private DatasetCatalog catalog(Path file) {
        return new DatasetCatalog(file, new IngestService(dir.resolve("cache")));
    }

    private Path csv() throws Exception {
        return write("people.csv", "name,score\nalice,1.5\nbob,2.5\ncarol,\n");
    }

    // ------------------------------------------------------------------ add

    @Test
    void addIngestsTheSourceAndPersistsTheEntry() throws Exception {
        Path csv = csv();
        DatasetCatalog catalog = catalog();

        DatasetCatalog.Entry entry = catalog.add("people", csv, "csv", "demo data",
                List.of("demo", "p6"), "alice");

        assertEquals("people", entry.name());
        assertEquals("demo data", entry.description());
        assertEquals("alice", entry.owner());
        assertEquals(List.of("demo", "p6"), entry.tags());
        assertEquals(csv.toAbsolutePath().normalize().toString(), entry.source());
        assertEquals("csv", entry.format());
        assertEquals(3, entry.rows());
        assertEquals(2, entry.columns());
        assertEquals(1, entry.version());
        assertEquals(64, entry.contentHash().length());
        assertTrue(Files.isRegularFile(Path.of(entry.path())), "parquet cache is missing");
        assertTrue(entry.path().endsWith(".parquet"));
        assertNotNull(entry.createdAt());
        assertEquals(entry.createdAt(), entry.updatedAt());
        assertTrue(entry.history().isEmpty());
        assertNull(entry.profile());

        assertTrue(Files.isRegularFile(catalogFile()), "the catalog file must be written");
        assertEquals(List.of("people"), catalog.list().stream()
                .map(DatasetCatalog.Entry::name).toList());
        assertEquals(entry, catalog.get("people"));
        assertFalse(hasTempFiles(), "atomic write must not leave temp files");
    }

    @Test
    void addAcceptsAnExistingParquetDataset() throws Exception {
        Path csv = csv();
        DatasetCatalog catalog = catalog();
        DatasetCatalog.Entry first = catalog.add("people", csv, "csv", null, List.of(), null);

        DatasetCatalog.Entry second = catalog.add("people-parquet", Path.of(first.path()),
                "parquet", null, List.of(), null);

        assertEquals(3, second.rows());
        assertEquals(2, second.columns());
        assertEquals("parquet", second.format());
        assertEquals(2, catalog.list().size());
    }

    @Test
    void addInfersTheFormatFromTheExtension() throws Exception {
        Path csv = csv();

        DatasetCatalog.Entry entry = catalog().add("people", csv, null, null, List.of(), null);

        assertEquals("csv", entry.format());
        assertEquals(3, entry.rows());
    }

    @Test
    void duplicateNameIsAConflict() throws Exception {
        Path csv = csv();
        DatasetCatalog catalog = catalog();
        catalog.add("people", csv, "csv", null, List.of(), null);

        ApiException failure = assertThrows(ApiException.class,
                () -> catalog.add("people", csv, "csv", null, List.of(), null));

        assertEquals(409, failure.status());
        assertEquals("dataset_exists", failure.code());
        assertTrue(failure.getMessage().contains("people"), failure.getMessage());
        assertEquals(1, catalog.list().size());
    }

    @Test
    void invalidNamesAreRejected() throws Exception {
        Path csv = csv();
        DatasetCatalog catalog = catalog();

        for (String name : List.of("", "bad name", "a/b", "../escape", "x".repeat(65), "  ")) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> catalog.add(name, csv, "csv", null, List.of(), null), name);
            assertEquals(400, failure.status(), name);
            assertEquals("invalid_dataset_name", failure.code(), name);
            assertNotNull(failure.hint(), name);
        }
        assertThrows(ApiException.class, () -> catalog.add(null, csv, "csv", null, List.of(), null));
        assertEquals(0, catalog.list().size());
    }

    @Test
    void unknownFormatAndMissingSourceAreRejected() throws Exception {
        Path csv = csv();
        DatasetCatalog catalog = catalog();

        ApiException format = assertThrows(ApiException.class,
                () -> catalog.add("people", csv, "xml", null, List.of(), null));
        assertEquals(400, format.status());
        assertEquals("invalid_format", format.code());
        assertTrue(format.hint().contains("csv"), format.hint());

        ApiException missing = assertThrows(ApiException.class,
                () -> catalog.add("people", dir.resolve("absent.csv"), "csv", null, List.of(), null));
        assertEquals(400, missing.status());
        assertEquals("dataset_source_not_found", missing.code());
        assertTrue(missing.getMessage().contains("absent.csv"), missing.getMessage());
        assertEquals(0, catalog.list().size());
    }

    @Test
    void secretPathsAreRejected() {
        DatasetCatalog catalog = catalog();
        Path denied = Path.of(System.getProperty("user.home"), ".ssh", "id_ed25519");

        ApiException failure = assertThrows(ApiException.class,
                () -> catalog.add("keys", denied, "csv", null, List.of(), null));

        assertEquals(400, failure.status());
        assertEquals("path_not_allowed", failure.code());
        assertTrue(failure.hint().contains("DAP_ALLOWED_ROOTS"), failure.hint());
    }

    // --------------------------------------------------------------- update

    @Test
    void updateMergesMetadataAndKeepsDataFields() throws Exception {
        DatasetCatalog catalog = catalog();
        DatasetCatalog.Entry added = catalog.add("people", csv(), "csv", "first",
                List.of("a"), "alice");

        DatasetCatalog.Entry updated = catalog.update("people", "second", List.of("b", "c"), "bob");

        assertEquals("second", updated.description());
        assertEquals(List.of("b", "c"), updated.tags());
        assertEquals("bob", updated.owner());
        assertEquals(added.version(), updated.version());
        assertEquals(added.createdAt(), updated.createdAt());
        assertEquals(added.contentHash(), updated.contentHash());
        assertTrue(updated.updatedAt().compareTo(added.updatedAt()) >= 0);

        DatasetCatalog.Entry kept = catalog.update("people", null, null, null);
        assertEquals("second", kept.description());
        assertEquals(List.of("b", "c"), kept.tags());
        assertEquals("bob", kept.owner());
    }

    @Test
    void updateAndRemoveOfAnUnknownDatasetAre404() {
        DatasetCatalog catalog = catalog();

        ApiException update = assertThrows(ApiException.class,
                () -> catalog.update("nope", "x", null, null));
        assertEquals(404, update.status());
        assertEquals("dataset_not_found", update.code());

        ApiException remove = assertThrows(ApiException.class, () -> catalog.remove("nope"));
        assertEquals(404, remove.status());
        assertEquals("dataset_not_found", remove.code());

        ApiException get = assertThrows(ApiException.class, () -> catalog.get("nope"));
        assertEquals(404, get.status());
        assertEquals("dataset_not_found", get.code());
    }

    // -------------------------------------------------------------- refresh

    @Test
    void refreshReingestsAndBumpsTheVersionKeepingHistory() throws Exception {
        Path csv = write("people.csv", "name,score\nalice,1.5\nbob,2.5\n");
        DatasetCatalog catalog = catalog();
        DatasetCatalog.Entry first = catalog.add("people", csv, "csv", "demo", List.of("a"), "alice");

        Files.writeString(csv, "name,score\nalice,1.5\nbob,2.5\ncarol,3.5\n", StandardCharsets.UTF_8);
        DatasetCatalog.Entry second = catalog.refresh("people");

        assertEquals(2, second.version());
        assertEquals(3, second.rows());
        assertNotEquals(first.contentHash(), second.contentHash());
        assertEquals("demo", second.description(), "refresh keeps the metadata");
        assertEquals("alice", second.owner());
        assertEquals(1, second.history().size());
        DatasetCatalog.Version previous = second.history().get(0);
        assertEquals(1, previous.version());
        assertEquals(first.contentHash(), previous.contentHash());
        assertEquals(first.rows(), previous.rows());
        assertEquals(first.path(), previous.path());
        assertEquals(first.updatedAt(), previous.updatedAt());
        assertEquals(2, catalog.get("people").version());
    }

    @Test
    void historyKeepsTheLastTenVersions() throws Exception {
        DatasetCatalog catalog = catalog();
        catalog.add("people", csv(), "csv", null, List.of(), null);
        for (int index = 0; index < 11; index++) {
            catalog.refresh("people");
        }

        DatasetCatalog.Entry entry = catalog.get("people");

        assertEquals(12, entry.version());
        assertEquals(10, entry.history().size());
        assertEquals(2, entry.history().get(0).version());
        assertEquals(11, entry.history().get(9).version());
    }

    @Test
    void refreshOfAnUnknownDatasetIs404() {
        ApiException failure = assertThrows(ApiException.class, () -> catalog().refresh("nope"));

        assertEquals(404, failure.status());
        assertEquals("dataset_not_found", failure.code());
    }

    // -------------------------------------------------------------- profile

    @Test
    void profileIsComputedAndCachedByContentHash() throws Exception {
        DatasetCatalog catalog = catalog();
        DatasetCatalog.Entry added = catalog.add("people", csv(), "csv", null, List.of(), null);

        DatasetCatalog.Profile profile = catalog.profile("people", false);

        assertEquals(3, profile.rows());
        assertEquals(added.contentHash(), profile.contentHash());
        assertNotNull(profile.computedAt());
        assertEquals(List.of("name", "score"), profile.columns().stream()
                .map(DatasetCatalog.ProfileColumn::name).toList());
        DatasetCatalog.ProfileColumn score = profile.columns().get(1);
        assertEquals("Float64", score.type());
        assertEquals(1, score.missing());
        assertEquals(2, score.distinct());
        assertEquals(1.5, score.min());
        assertEquals(2.5, score.max());
        assertEquals(2.0, score.mean());
        DatasetCatalog.ProfileColumn name = profile.columns().get(0);
        assertEquals("Utf8", name.type());
        assertNull(name.min(), "non-numeric columns carry no min/max/mean");
        assertEquals(0, name.missing());

        assertEquals(profile, catalog.get("people").profile(), "the profile must be cached in the entry");
        assertEquals(profile, catalog.profile("people", false));
        assertEquals(profile, catalog().profile("people", false), "cache must survive a new instance");
    }

    @Test
    void refreshInvalidatesTheProfileCacheWhenTheContentChanges() throws Exception {
        Path csv = write("people.csv", "name,score\nalice,1.5\nbob,2.5\n");
        DatasetCatalog catalog = catalog();
        catalog.add("people", csv, "csv", null, List.of(), null);
        assertEquals(2, catalog.profile("people", false).rows());

        Files.writeString(csv, "name,score\nalice,1.5\nbob,2.5\ncarol,3.5\ndave,4.5\n",
                StandardCharsets.UTF_8);
        DatasetCatalog.Entry refreshed = catalog.refresh("people");
        DatasetCatalog.Profile profile = catalog.profile("people", false);

        assertEquals(4, profile.rows());
        assertEquals(refreshed.contentHash(), profile.contentHash());
        assertEquals(profile, catalog.get("people").profile());
    }

    @Test
    void profileRefreshRecomputesTheCache() throws Exception {
        DatasetCatalog catalog = catalog();
        catalog.add("people", csv(), "csv", null, List.of(), null);
        DatasetCatalog.Profile cached = catalog.profile("people", false);

        DatasetCatalog.Profile forced = catalog.profile("people", true);

        assertEquals(cached.rows(), forced.rows());
        assertEquals(cached.columns(), forced.columns());
        assertEquals(cached.contentHash(), forced.contentHash());
        assertEquals(forced, catalog.get("people").profile());
    }

    @Test
    void profileOfAnUnknownDatasetIs404() {
        ApiException failure = assertThrows(ApiException.class,
                () -> catalog().profile("nope", false));

        assertEquals(404, failure.status());
        assertEquals("dataset_not_found", failure.code());
    }

    // ---------------------------------------------------------- persistence

    @Test
    void entriesSurviveANewCatalogInstance() throws Exception {
        Path csv = csv();
        DatasetCatalog first = catalog();
        first.add("people", csv, "csv", "demo", List.of("a", "b"), "alice");
        DatasetCatalog.Entry updated = first.update("people", "demo", List.of("a", "b"), "alice");

        DatasetCatalog second = catalog();

        assertEquals(List.of("people"), second.list().stream()
                .map(DatasetCatalog.Entry::name).toList());
        assertEquals(updated, second.get("people"));
        assertEquals(1, second.list().get(0).version());
    }

    @Test
    void listIsSortedByName() throws Exception {
        Path csv = csv();
        DatasetCatalog catalog = catalog();
        catalog.add("zulu", csv, "csv", null, List.of(), null);
        catalog.add("alpha", csv, "csv", null, List.of(), null);

        assertEquals(List.of("alpha", "zulu"), catalog.list().stream()
                .map(DatasetCatalog.Entry::name).toList());
    }

    @Test
    void removeDeletesTheEntryAndPersistsTheDeletion() throws Exception {
        DatasetCatalog catalog = catalog();
        catalog.add("people", csv(), "csv", null, List.of(), null);

        catalog.remove("people");

        assertThrows(ApiException.class, () -> catalog.get("people"));
        assertTrue(catalog.list().isEmpty());
        assertTrue(catalog().list().isEmpty(), "the deletion must be persisted");
    }

    @Test
    void corruptFileFailsWithAHint() throws Exception {
        Files.writeString(catalogFile(), "{not json", StandardCharsets.UTF_8);
        DatasetCatalog catalog = catalog();

        ApiException failure = assertThrows(ApiException.class, catalog::list);

        assertEquals(500, failure.status());
        assertEquals("datasets_corrupt", failure.code());
        assertTrue(failure.getMessage().contains("datasets.json"), failure.getMessage());
        assertNotNull(failure.hint());
        assertTrue(failure.hint().contains("datasets.json"), failure.hint());
    }

    @Test
    void foreignFormatMarkerIsRejectedAsCorrupt() throws Exception {
        Files.writeString(catalogFile(), "{\"format\":\"knime/1\",\"datasets\":[]}",
                StandardCharsets.UTF_8);
        DatasetCatalog catalog = catalog();

        ApiException failure = assertThrows(ApiException.class, catalog::list);

        assertEquals("datasets_corrupt", failure.code());
        assertTrue(failure.getMessage().contains("knime/1"), failure.getMessage());
    }

    // ------------------------------------------------------------- defaults

    @Test
    void defaultFileHonoursTheEnvironment() {
        assertEquals(Path.of("/tmp/x/datasets.json"),
                DatasetCatalog.defaultFile(Map.of("DAP_DATASETS_FILE", "/tmp/x/datasets.json")));
        assertEquals(Path.of("/home/u/build/datasets.json"),
                DatasetCatalog.defaultFile(Map.of("DAP_HOME", "/home/u")));
        assertEquals(Path.of(System.getProperty("user.home"), ".dap", "build", "datasets.json"),
                DatasetCatalog.defaultFile(Map.of()));
        assertEquals(Path.of("/home/u/build/datasets.json"),
                DatasetCatalog.defaultFile(Map.of("DAP_HOME", "/home/u", "DAP_DATASETS_FILE", " ")));
    }

    private boolean hasTempFiles() throws Exception {
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(file -> file.getFileName().toString().startsWith(".")
                    && file.getFileName().toString().endsWith(".tmp"));
        }
    }
}
