package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.io.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * dap.file_list: lists a directory as a table (path, name, extension, size, modified,
 * is_directory). Uses {@code Files.walk} without following symlinks and
 * {@link BasicFileAttributes}, so size and modification time are available; the
 * listing is sorted and capped for a deterministic result.
 */
public final class FileListExecutor implements NodeExecutor {
    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 1_000_000;
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("path", "Utf8"),
            ColumnSpec.of("name", "Utf8"),
            ColumnSpec.of("extension", "Utf8"),
            ColumnSpec.of("size", "Int64"),
            ColumnSpec.of("modified", "Timestamp"),
            ColumnSpec.of("is_directory", "Boolean"));

    private record Entry(String path, String name, String extension, long size,
                         LocalDateTime modified, boolean directory) {
    }

    @Override
    public String nodeId() {
        return "dap.file_list";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        JsonNode config = context.config();
        Path directory = directory(config);
        String pattern = NodeSql.text(config, "pattern", "*");
        boolean recursive = NodeSql.bool(config, "recursive", false);
        boolean includeHidden = NodeSql.bool(config, "include_hidden", false);
        String sort = sort(config);
        int limit = limit(config);

        PathMatcher matcher = matcher(pattern);
        List<Entry> entries = walk(directory, matcher, recursive, includeHidden);
        entries.sort(comparator(sort));
        List<Object[]> rows = new ArrayList<>(Math.min(entries.size(), limit));
        for (Entry entry : entries) {
            if (rows.size() >= limit) {
                break;
            }
            rows.add(new Object[]{entry.path(), entry.name(), entry.extension(), entry.size(),
                    entry.modified(), entry.directory()});
        }
        try (ArrowTable table = ArrowTable.of(COLUMNS, rows)) {
            String name = context.db().loadInto(context.jobConnection(), table, tableName(context));
            return new LazyPlan(context.db(), "SELECT * FROM " + DuckDbSession.quote(name), COLUMNS);
        } catch (SQLException failure) {
            throw new IllegalStateException("file_list: cannot load the listing into DuckDB: "
                    + failure.getMessage(), failure);
        }
    }

    private static Path directory(JsonNode config) {
        String value = NodeSql.requiredText(config, "directory");
        Path directory;
        try {
            directory = Path.of(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("file_list: invalid directory '" + value + "': "
                    + invalid.getMessage(), invalid);
        }
        directory = PathGuard.shared().checkRead(directory.toAbsolutePath().toString());
        if (!Files.exists(directory)) {
            throw new IllegalArgumentException("file_list: directory does not exist: " + value);
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("file_list: directory is not a directory: " + value);
        }
        return directory;
    }

    private static PathMatcher matcher(String pattern) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("file_list: invalid pattern '" + pattern + "': "
                    + invalid.getMessage(), invalid);
        }
    }

    private static List<Entry> walk(Path directory, PathMatcher matcher, boolean recursive,
                                    boolean includeHidden) {
        List<Entry> entries = new ArrayList<>();
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> paths = Files.walk(directory, depth)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (path.equals(directory)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (!includeHidden && name.startsWith(".")) {
                    continue;
                }
                if (!matcher.matches(path.getFileName())) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    // NOFOLLOW keeps symlinks as entries instead of walking into them.
                    attributes = Files.readAttributes(path, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                } catch (IOException unreadable) {
                    throw new IllegalStateException("file_list: cannot read attributes of " + path
                            + ": " + unreadable.getMessage(), unreadable);
                }
                entries.add(new Entry(path.toAbsolutePath().normalize().toString(), name,
                        extension(name), attributes.size(),
                        LocalDateTime.ofInstant(attributes.lastModifiedTime().toInstant(), ZoneOffset.UTC),
                        attributes.isDirectory()));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("file_list: cannot walk " + directory + ": "
                    + failure.getMessage(), failure);
        } catch (UncheckedIOException failure) {
            throw new IllegalStateException("file_list: cannot walk " + directory + ": "
                    + failure.getCause().getMessage(), failure);
        }
        return entries;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }

    private static String sort(JsonNode config) {
        String sort = NodeSql.text(config, "sort", "name");
        String normalised = sort.toLowerCase(Locale.ROOT);
        if (!normalised.equals("name") && !normalised.equals("size") && !normalised.equals("modified")) {
            throw new IllegalArgumentException("file_list: 'sort' must be name, size or modified, got '"
                    + sort + "'");
        }
        return normalised;
    }

    private static Comparator<Entry> comparator(String sort) {
        Comparator<Entry> byPath = Comparator.comparing(Entry::path);
        return switch (sort) {
            case "size" -> Comparator.comparingLong(Entry::size).thenComparing(byPath);
            case "modified" -> Comparator.comparing(Entry::modified).thenComparing(byPath);
            default -> Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(byPath);
        };
    }

    private static int limit(JsonNode config) {
        JsonNode node = config.get("limit");
        if (node == null || node.isNull()) {
            return DEFAULT_LIMIT;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException("file_list: 'limit' must be an integer, got " + node);
        }
        long value = node.longValue();
        if (value < 1 || value > MAX_LIMIT) {
            throw new IllegalArgumentException("file_list: 'limit' must be between 1 and " + MAX_LIMIT
                    + ", got " + value);
        }
        return (int) value;
    }

    private static String tableName(NodeContext context) {
        return "files_" + sanitize(context.workflowId()) + "_" + sanitize(context.nodeId());
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "default";
        }
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
