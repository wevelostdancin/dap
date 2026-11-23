package com.dap.nativeengine.io;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.ProfileData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent catalog of datasets: one JSON file with the ingested Parquet paths, their
 * source, row/column counts, a monotone version with the last ten snapshots, and a
 * profile cache keyed by the content hash.
 *
 * <p>The file lives at {@code DAP_DATASETS_FILE}, else {@code $DAP_HOME/build/datasets.json},
 * else {@code ~/.dap/build/datasets.json}. Reads and writes are serialised by a
 * read/write lock; every write goes through a temporary file plus an atomic move, so a
 * crash never leaves a half-written catalog. A corrupt file is reported with the path
 * and a hint instead of being silently replaced.
 */
public final class DatasetCatalog {
    /** Version marker of the on-disk document. */
    public static final String FORMAT = "dap-datasets/1";
    /** Dataset names are URL-safe so they can be path parameters. */
    public static final String NAME_PATTERN = "[A-Za-z0-9._-]{1,64}";

    private static final int MAX_HISTORY = 10;
    private static final String FORMAT_HINT = "pass one of csv, excel, json, parquet";
    private static final String NAME_HINT =
            "use 1..64 characters from A-Z a-z 0-9 . _ - (the name is the path parameter "
                    + "/v1/catalog/datasets/{name})";
    /** One column of the cached profile. Numeric fields are null for non-numeric columns. */
    public record ProfileColumn(String name, String type, long missing, long distinct,
                                Double min, Double max, Double mean) {
    }

    /** Cached profile of one dataset version; {@code contentHash} keys the cache. */
    public record Profile(String contentHash, long rows, List<ProfileColumn> columns,
                          String computedAt) {
        public Profile {
            columns = List.copyOf(columns);
        }
    }

    /** One superseded version kept in {@code history}. */
    public record Version(int version, String contentHash, long rows, int columns, String path,
                          String updatedAt) {
    }

    /**
     * One catalog record. {@code columns} is the column count; the per-column schema lives
     * in {@link Profile}. {@code history} keeps the last ten superseded versions.
     */
    public record Entry(String name, String description, String owner, List<String> tags,
                        String source, String format, String path, long rows, int columns,
                        int version, String contentHash, String createdAt, String updatedAt,
                        List<Version> history, Profile profile) {
        public Entry {
            tags = List.copyOf(tags);
            history = List.copyOf(history);
        }
    }

    private final Path file;
    private final IngestService ingest;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Object loadMonitor = new Object();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private volatile boolean loaded;

    /** Production catalog: {@code DAP_DATASETS_FILE} or {@code $DAP_HOME/build/datasets.json}. */
    public DatasetCatalog() {
        this(defaultFile(System.getenv()));
    }

    /** Production catalog with an ingest service that carries the engine's DuckDB resources. */
    public DatasetCatalog(IngestService ingest) {
        this(defaultFile(System.getenv()), ingest);
    }

    /** Test seam: an explicit catalog file and the production ingest cache. */
    public DatasetCatalog(Path file) {
        this(file, new IngestService());
    }

    /** Test seam: an explicit catalog file and an explicit ingest cache. */
    public DatasetCatalog(Path file, IngestService ingest) {
        this.file = Objects.requireNonNull(file, "file");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
    }

    /** Resolve the catalog file from an environment snapshot. */
    public static Path defaultFile(Map<String, String> env) {
        String configured = blankToNull(env.get("DAP_DATASETS_FILE"));
        if (configured != null) {
            return Path.of(configured);
        }
        String home = blankToNull(env.get("DAP_HOME"));
        if (home != null) {
            return Path.of(home, "build", "datasets.json");
        }
        return Path.of(System.getProperty("user.home"), ".dap", "build", "datasets.json");
    }

    /** Every dataset, sorted by name so the listing is deterministic. */
    public List<Entry> list() {
        lock.readLock().lock();
        try {
            ensureLoaded();
            List<Entry> sorted = new ArrayList<>(entries.values());
            sorted.sort((left, right) -> left.name().compareTo(right.name()));
            return List.copyOf(sorted);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** One dataset; 404 {@code dataset_not_found} when the name is unknown. */
    public Entry get(String name) {
        lock.readLock().lock();
        try {
            ensureLoaded();
            return require(name);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Ingest a CSV/Excel/JSON/Parquet source (or an already ingested Parquet dataset) and
     * record it under {@code name}. A duplicate name is 409 {@code dataset_exists}, an
     * invalid name is 400 {@code invalid_dataset_name}.
     */
    public Entry add(String name, Path source, String format, String description,
                     List<String> tags, String owner) {
        String dataset = validateName(name);
        Path checked = checkSource(source);
        String normalized = normalizeFormat(format, checked);
        if (!Files.isRegularFile(checked)) {
            throw ApiException.badRequest("dataset_source_not_found",
                    "file not found: " + checked,
                    "pass an existing CSV/Excel/JSON/Parquet file or an ingested Parquet path");
        }
        lock.readLock().lock();
        try {
            ensureLoaded();
            if (entries.containsKey(dataset)) {
                throw duplicate(dataset);
            }
        } finally {
            lock.readLock().unlock();
        }

        IngestService.Result result = ingest(checked, normalized);
        String contentHash = contentHash(result.path());

        lock.writeLock().lock();
        try {
            ensureLoaded();
            if (entries.containsKey(dataset)) {
                throw duplicate(dataset);
            }
            String now = Instant.now().toString();
            Entry entry = new Entry(dataset, description, owner, tags == null ? List.of() : tags,
                    checked.toString(), normalized, result.path().toString(), result.rows(),
                    result.columns().size(), 1, contentHash, now, now, List.of(), null);
            entries.put(dataset, entry);
            save();
            return entry;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Merge the mutable metadata; null arguments keep the current value. Data fields,
     * version and the profile cache are untouched.
     */
    public Entry update(String name, String description, List<String> tags, String owner) {
        lock.writeLock().lock();
        try {
            ensureLoaded();
            Entry current = require(name);
            Entry updated = new Entry(current.name(),
                    description == null ? current.description() : description,
                    owner == null ? current.owner() : owner,
                    tags == null ? current.tags() : tags,
                    current.source(), current.format(), current.path(), current.rows(),
                    current.columns(), current.version(), current.contentHash(),
                    current.createdAt(), Instant.now().toString(), current.history(),
                    current.profile());
            entries.put(updated.name(), updated);
            save();
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Delete one dataset; 404 {@code dataset_not_found} when the name is unknown. */
    public void remove(String name) {
        lock.writeLock().lock();
        try {
            ensureLoaded();
            if (entries.remove(name) == null) {
                throw notFound(name);
            }
            save();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Re-ingest the recorded source, bump the version and keep the superseded snapshot in
     * {@code history} (the last ten). The profile cache is reused only when the content
     * hash did not change.
     */
    public Entry refresh(String name) {
        Entry current = get(name);
        IngestService.Result result = ingest(Path.of(current.source()), current.format());
        String contentHash = contentHash(result.path());

        lock.writeLock().lock();
        try {
            ensureLoaded();
            Entry latest = require(name);
            List<Version> history = new ArrayList<>(latest.history());
            history.add(new Version(latest.version(), latest.contentHash(), latest.rows(),
                    latest.columns(), latest.path(), latest.updatedAt()));
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
            Profile profile = latest.profile() != null
                    && latest.profile().contentHash().equals(contentHash)
                    ? latest.profile()
                    : null;
            Entry refreshed = new Entry(latest.name(), latest.description(), latest.owner(),
                    latest.tags(), latest.source(), latest.format(), result.path().toString(),
                    result.rows(), result.columns().size(), latest.version() + 1, contentHash,
                    latest.createdAt(), Instant.now().toString(), history, profile);
            entries.put(name, refreshed);
            save();
            return refreshed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Profile of the current version: the cache is returned when it was computed for the
     * same content hash, otherwise it is computed and persisted. {@code refresh} forces a
     * recomputation.
     */
    public Profile profile(String name, boolean refresh) {
        Entry current = get(name);
        Profile cached = current.profile();
        if (!refresh && cached != null && cached.contentHash().equals(current.contentHash())) {
            return cached;
        }
        Profile computed = computeProfile(current);

        lock.writeLock().lock();
        try {
            ensureLoaded();
            Entry latest = entries.get(name);
            if (latest != null && latest.contentHash().equals(computed.contentHash())) {
                entries.put(name, new Entry(latest.name(), latest.description(), latest.owner(),
                        latest.tags(), latest.source(), latest.format(), latest.path(),
                        latest.rows(), latest.columns(), latest.version(), latest.contentHash(),
                        latest.createdAt(), latest.updatedAt(), latest.history(), computed));
                save();
            }
        } finally {
            lock.writeLock().unlock();
        }
        return computed;
    }

    // ------------------------------------------------------------- internals

    private Entry require(String name) {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw notFound(name);
        }
        return entry;
    }

    private static ApiException notFound(String name) {
        return ApiException.notFound("dataset_not_found", "unknown dataset: " + name,
                "GET /v1/catalog/datasets lists the dataset names");
    }

    private static ApiException duplicate(String name) {
        return ApiException.conflict("dataset_exists",
                "dataset already exists: " + name,
                "PATCH /v1/catalog/datasets/" + name + " updates it, "
                        + "POST /v1/catalog/datasets/" + name + "/refresh re-ingests it, "
                        + "DELETE removes it");
    }

    private static String validateName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("invalid_dataset_name", "dataset name is required",
                    NAME_HINT);
        }
        String name = raw.strip();
        if (!name.matches(NAME_PATTERN)) {
            throw ApiException.badRequest("invalid_dataset_name",
                    "invalid dataset name: '" + raw.strip() + "'", NAME_HINT);
        }
        return name;
    }

    /** The catalog source goes through the same path allowlist as every other reader. */
    private static Path checkSource(Path source) {
        if (source == null) {
            throw ApiException.badRequest("dataset_source_required",
                    "'file' or 'dataset' is required",
                    "pass a CSV/Excel/JSON/Parquet file path or an ingested Parquet path");
        }
        return PathGuard.shared().checkRead(source.toAbsolutePath().normalize().toString());
    }

    private static String normalizeFormat(String raw, Path source) {
        String format = raw == null || raw.isBlank()
                ? formatOf(source)
                : raw.strip().toLowerCase(Locale.ROOT);
        if (format == null) {
            throw ApiException.badRequest("invalid_format",
                    "cannot infer the format from the file name: " + source, FORMAT_HINT);
        }
        return switch (format) {
            case "csv" -> "csv";
            case "excel", "xlsx", "xls", "xlsb" -> "excel";
            case "json" -> "json";
            case "parquet" -> "parquet";
            default -> throw ApiException.badRequest("invalid_format",
                    "unknown format: " + raw, FORMAT_HINT);
        };
    }

    private static String formatOf(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return null;
        }
        String value = name.toString().toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        String extension = dot < 0 ? "" : value.substring(dot + 1);
        return switch (extension) {
            case "csv" -> "csv";
            case "xlsx", "excel", "xls", "xlsb" -> "excel";
            case "json", "jsonl", "ndjson" -> "json";
            case "parquet" -> "parquet";
            default -> null;
        };
    }

    private IngestService.Result ingest(Path source, String format) {
        try {
            return ingest.ingest(source, format);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.badRequest(invalid.getMessage());
        } catch (IllegalStateException failed) {
            throw new ApiException(500, "dataset_ingest_failed",
                    "cannot ingest " + source + ": " + failed.getMessage(),
                    "check that the file is readable and its columns have scalar types");
        }
    }

    /** SHA-256 of the cached Parquet bytes; the profile cache is keyed by it. */
    private static String contentHash(Path parquet) {
        try (InputStream stream = Files.newInputStream(parquet)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("cannot hash " + parquet + ": " + failure.getMessage(),
                    failure);
        }
    }

    private static Profile computeProfile(Entry entry) {
        String sql = "SELECT * FROM read_parquet(" + literal(entry.path()) + ")";
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> specs;
            try (Connection connection = db.newConnection()) {
                specs = db.describe(connection, sql);
            }
            ProfileData data = db.profile(sql, specs);
            List<ProfileColumn> columns = new ArrayList<>(specs.size());
            for (int index = 0; index < specs.size(); index++) {
                com.dap.nativeengine.engine.ProfileColumn stats = data.columns().get(index);
                columns.add(new ProfileColumn(stats.name(), specs.get(index).arrowType(),
                        stats.missing(), stats.distinct(), stats.min(), stats.max(),
                        stats.mean()));
            }
            return new Profile(entry.contentHash(), data.rows(), columns,
                    Instant.now().toString());
        } catch (SQLException failure) {
            throw new ApiException(500, "dataset_profile_failed",
                    "cannot profile dataset '" + entry.name() + "': " + failure.getMessage(),
                    "check that the cached Parquet file still exists: " + entry.path());
        }
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Lazily read the file once; a missing file starts an empty catalog. */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (loadMonitor) {
            if (loaded) {
                return;
            }
            load();
            loaded = true;
        }
    }

    private void load() {
        Path absolute = file.toAbsolutePath().normalize();
        entries.clear();
        if (!Files.exists(absolute)) {
            return;
        }
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(Files.readString(absolute, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw corrupt(absolute, failure.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw corrupt(absolute, "the file is not a JSON object");
        }
        String format = root.path("format").asText("");
        if (!FORMAT.equals(format)) {
            throw corrupt(absolute, "unsupported format '" + format + "' (expected " + FORMAT + ")");
        }
        JsonNode datasets = root.path("datasets");
        if (!datasets.isArray()) {
            throw corrupt(absolute, "missing the 'datasets' array");
        }
        Map<String, Entry> parsed = new LinkedHashMap<>();
        for (JsonNode node : datasets) {
            Entry entry = fromJson(node, absolute);
            parsed.put(entry.name(), entry);
        }
        entries.putAll(parsed);
    }

    private static ApiException corrupt(Path file, String detail) {
        String message = "cannot read the dataset catalog " + file + ": " + detail;
        return new ApiException(500, "datasets_corrupt", message,
                "fix or delete " + file + "; a missing file starts an empty catalog");
    }

    private void save() {
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ObjectNode root = Json.object();
            root.put("format", FORMAT);
            ArrayNode datasets = root.putArray("datasets");
            for (Entry entry : entries.values()) {
                datasets.add(toJson(entry));
            }
            String document = Json.MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root) + "\n";
            Path temporary = parent.resolve("." + absolute.getFileName() + "."
                    + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(temporary, document, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException fallback) {
                    Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new ApiException(500, "datasets_write_failed",
                    "cannot write the dataset catalog " + absolute + ": " + failure.getMessage(),
                    "check the permissions of " + parent);
        }
    }

    private static ObjectNode toJson(Entry entry) {
        ObjectNode node = Json.object();
        node.put("name", entry.name());
        if (entry.description() != null) {
            node.put("description", entry.description());
        }
        if (entry.owner() != null) {
            node.put("owner", entry.owner());
        }
        ArrayNode tags = node.putArray("tags");
        for (String tag : entry.tags()) {
            tags.add(tag);
        }
        node.put("source", entry.source());
        node.put("format", entry.format());
        node.put("path", entry.path());
        node.put("rows", entry.rows());
        node.put("columns", entry.columns());
        node.put("version", entry.version());
        node.put("contentHash", entry.contentHash());
        node.put("createdAt", entry.createdAt());
        node.put("updatedAt", entry.updatedAt());
        ArrayNode history = node.putArray("history");
        for (Version version : entry.history()) {
            ObjectNode item = history.addObject();
            item.put("version", version.version());
            item.put("contentHash", version.contentHash());
            item.put("rows", version.rows());
            item.put("columns", version.columns());
            item.put("path", version.path());
            item.put("updatedAt", version.updatedAt());
        }
        if (entry.profile() != null) {
            node.set("profile", profileToJson(entry.profile()));
        }
        return node;
    }

    private static ObjectNode profileToJson(Profile profile) {
        ObjectNode node = Json.object();
        node.put("contentHash", profile.contentHash());
        node.put("rows", profile.rows());
        node.put("computedAt", profile.computedAt());
        ArrayNode columns = node.putArray("columns");
        for (ProfileColumn column : profile.columns()) {
            ObjectNode item = columns.addObject();
            item.put("name", column.name());
            item.put("type", column.type());
            item.put("missing", column.missing());
            item.put("distinct", column.distinct());
            if (column.min() != null) {
                item.put("min", column.min());
            }
            if (column.max() != null) {
                item.put("max", column.max());
            }
            if (column.mean() != null) {
                item.put("mean", column.mean());
            }
        }
        return node;
    }

    private static Entry fromJson(JsonNode node, Path file) {
        if (node == null || !node.isObject()) {
            throw corrupt(file, "a dataset entry is not an object");
        }
        String name = node.path("name").asText("");
        if (name.isEmpty()) {
            throw corrupt(file, "a dataset entry has no name");
        }
        String path = node.path("path").asText("");
        if (path.isEmpty()) {
            throw corrupt(file, "dataset '" + name + "' has no cached path");
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : node.path("tags")) {
            tags.add(tag.asText());
        }
        List<Version> history = new ArrayList<>();
        for (JsonNode item : node.path("history")) {
            history.add(new Version(item.path("version").asInt(),
                    item.path("contentHash").asText(""), item.path("rows").asLong(),
                    item.path("columns").asInt(), item.path("path").asText(""),
                    item.path("updatedAt").asText("")));
        }
        JsonNode profileNode = node.get("profile");
        Profile profile = profileNode == null || !profileNode.isObject()
                ? null
                : profileFromJson(profileNode);
        return new Entry(name, textOrNull(node, "description"), textOrNull(node, "owner"), tags,
                node.path("source").asText(""), node.path("format").asText(""), path,
                node.path("rows").asLong(), node.path("columns").asInt(),
                node.path("version").asInt(1), node.path("contentHash").asText(""),
                node.path("createdAt").asText(""), node.path("updatedAt").asText(""),
                history, profile);
    }

    private static Profile profileFromJson(JsonNode node) {
        List<ProfileColumn> columns = new ArrayList<>();
        for (JsonNode item : node.path("columns")) {
            columns.add(new ProfileColumn(item.path("name").asText(""),
                    item.path("type").asText(""), item.path("missing").asLong(),
                    item.path("distinct").asLong(),
                    doubleOrNull(item, "min"), doubleOrNull(item, "max"),
                    doubleOrNull(item, "mean")));
        }
        return new Profile(node.path("contentHash").asText(""), node.path("rows").asLong(),
                columns, node.path("computedAt").asText(""));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }
}
