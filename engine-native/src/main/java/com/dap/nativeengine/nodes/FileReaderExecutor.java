package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.io.PathGuard;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * dap.file_reader: one table out of many files — a list, a glob or a directory tree.
 *
 * <p>The files are expanded and validated in Java (sorted, capped, every path through
 * {@link PathGuard}), then handed to DuckDB as one {@code read_csv}/{@code read_parquet}/
 * {@code read_json_auto} relation over a file list. Nothing is loaded into the heap: the
 * scan stays inside DuckDB, so a multi-gigabyte dataset is read out of core.
 *
 * <p>ORC and Avro are rejected with a conversion hint: DuckDB 1.5.6 ships no ORC extension
 * for darwin/arm64 and Avro is not in the core.
 */
public final class FileReaderExecutor implements NodeExecutor {
    private static final String NODE = "file_reader";
    /** Hard cap on the expanded file list; a wider selection is a configuration mistake. */
    static final int MAX_FILES = 10_000;
    private static final Set<String> FORMATS = Set.of("csv", "parquet", "json");
    private static final Set<String> UNSUPPORTED_FORMATS = Set.of("orc", "avro");
    private static final Map<String, Set<String>> EXTENSIONS = Map.of(
            "csv", Set.of("csv"),
            "parquet", Set.of("parquet"),
            "json", Set.of("json", "jsonl", "ndjson", "ndjsonl"));

    @Override
    public String nodeId() {
        return "dap.file_reader";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        JsonNode config = context.config();
        String format = format(config);
        boolean recursive = NodeSql.bool(config, "recursive", false);
        List<Path> files = files(config, format, recursive);
        String sql = "SELECT * FROM " + relation(files, format, config);
        List<ColumnSpec> columns = IoNodeSupport.describe(context, sql, NODE);
        return new LazyPlan(context.db(), sql, columns);
    }

    private static String format(JsonNode config) {
        String raw = NodeSql.text(config, "format", null);
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("file_reader: 'format' is required (csv, parquet or json)");
        }
        String format = raw.trim().toLowerCase(Locale.ROOT);
        if (UNSUPPORTED_FORMATS.contains(format)) {
            throw new IllegalArgumentException("file_reader: format '" + format + "' is not supported: "
                    + "DuckDB 1.5.6 has no " + format.toUpperCase(Locale.ROOT)
                    + " reader on darwin/arm64; convert the file to parquet or csv first");
        }
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("file_reader: unsupported format '" + raw
                    + "'; expected one of csv, parquet, json");
        }
        return format;
    }

    /** Every file selected by 'path'/'paths', validated, de-duplicated, sorted and capped. */
    private static List<Path> files(JsonNode config, String format, boolean recursive) {
        JsonNode pathNode = config.get("path");
        JsonNode pathsNode = config.get("paths");
        boolean hasPath = pathNode != null && !pathNode.isNull();
        boolean hasPaths = pathsNode != null && !pathsNode.isNull();
        if (hasPath == hasPaths) {
            throw new IllegalArgumentException(
                    "file_reader: exactly one of 'path' or 'paths' is required");
        }
        List<String> raw = new ArrayList<>();
        if (hasPath) {
            raw.add(NodeSql.requiredText(config, "path"));
        } else {
            if (!pathsNode.isArray() || pathsNode.isEmpty()) {
                throw new IllegalArgumentException("file_reader: 'paths' must be a non-empty array");
            }
            for (JsonNode entry : pathsNode) {
                if (!entry.isTextual() || entry.textValue().isBlank()) {
                    throw new IllegalArgumentException("file_reader: 'paths' must contain non-empty strings");
                }
                raw.add(entry.textValue());
            }
        }

        Set<Path> found = new LinkedHashSet<>();
        for (String entry : raw) {
            collect(entry, format, recursive, found);
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("file_reader: no files matched " + String.join(", ", raw)
                    + "; check the path, the glob and the format extension");
        }
        List<Path> files = new ArrayList<>(found);
        files.sort(Comparator.comparing(Path::toString));
        if (files.size() > MAX_FILES) {
            throw new IllegalArgumentException("file_reader: " + files.size()
                    + " files matched, more than the limit of " + MAX_FILES
                    + "; narrow the glob or the directory");
        }
        return files;
    }

    private static void collect(String entry, String format, boolean recursive, Set<Path> found) {
        Path path;
        try {
            path = Path.of(entry).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("file_reader: invalid path '" + entry + "': "
                    + invalid.getMessage(), invalid);
        }
        if (hasGlob(entry)) {
            collectGlob(entry, path, recursive, found);
            return;
        }
        Path checked = PathGuard.shared().checkRead(path.toString());
        if (Files.isDirectory(checked)) {
            collectDirectory(checked, format, recursive, found);
        } else if (Files.isRegularFile(checked)) {
            found.add(checked);
        } else {
            throw new IllegalArgumentException("file_reader: file not found: " + path);
        }
    }

    private static void collectDirectory(Path directory, String format, boolean recursive,
                                         Set<Path> found) {
        Set<String> extensions = EXTENSIONS.get(format);
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> paths = Files.walk(directory, depth)) {
            List<Path> candidates = paths.filter(Files::isRegularFile)
                    .filter(file -> extensions.contains(extension(file)))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path candidate : candidates) {
                found.add(PathGuard.shared().checkRead(candidate.toString()));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("file_reader: cannot walk " + directory + ": "
                    + failure.getMessage(), failure);
        }
    }

    private static void collectGlob(String entry, Path absolutePattern, boolean recursive,
                                    Set<Path> found) {
        Path root = globRoot(absolutePattern);
        PathGuard.shared().checkRead(root.toString());
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + absolutePattern);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("file_reader: invalid glob '" + entry + "': "
                    + invalid.getMessage(), invalid);
        }
        // A glob already encodes its own depth; 'recursive' only widens the walk.
        int depth = recursive ? Integer.MAX_VALUE : Integer.MAX_VALUE;
        try (Stream<Path> paths = Files.walk(root, depth)) {
            List<Path> matches = paths.filter(Files::isRegularFile)
                    .filter(matcher::matches)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path match : matches) {
                found.add(PathGuard.shared().checkRead(match.toString()));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("file_reader: cannot walk " + root + ": "
                    + failure.getMessage(), failure);
        }
    }

    /** The deepest directory before the first glob character. */
    private static Path globRoot(Path pattern) {
        String value = pattern.toString();
        int wildcard = value.length();
        for (char character : new char[]{'*', '?', '[', '{'}) {
            int index = value.indexOf(character);
            if (index >= 0) {
                wildcard = Math.min(wildcard, index);
            }
        }
        String prefix = value.substring(0, wildcard);
        int separator = prefix.lastIndexOf(java.io.File.separatorChar);
        Path root = separator <= 0 ? Path.of(prefix.isEmpty() ? "/" : prefix)
                : Path.of(prefix.substring(0, separator));
        return root.toAbsolutePath().normalize();
    }

    private static boolean hasGlob(String entry) {
        for (char character : new char[]{'*', '?', '[', '{'}) {
            if (entry.indexOf(character) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The DuckDB table expression over the expanded file list. */
    private static String relation(List<Path> files, String format, JsonNode config) {
        StringJoiner paths = new StringJoiner(", ", "[", "]");
        for (Path file : files) {
            paths.add(NodeSql.literal(file.toString()));
        }
        StringJoiner options = new StringJoiner(", ", "", "");
        if ("csv".equals(format)) {
            csvOptions(config, options);
        }
        if (NodeSql.bool(config, "union_by_name", false)) {
            options.add("union_by_name=true");
        }
        if (NodeSql.bool(config, "filename", false)) {
            options.add("filename=true");
        }
        String parameters = options.length() == 0 ? "" : ", " + options;
        return switch (format) {
            case "csv" -> "read_csv(" + paths + parameters + ")";
            case "parquet" -> "read_parquet(" + paths + parameters + ")";
            default -> "read_json_auto(" + paths + parameters + ")";
        };
    }

    private static void csvOptions(JsonNode config, StringJoiner options) {
        String delimiter = NodeSql.text(config, "delimiter", ",");
        if ("\\t".equals(delimiter)) {
            delimiter = "\t";
        }
        options.add("delim=" + NodeSql.literal(singleCharacter(delimiter, "delimiter")));
        options.add("header=" + NodeSql.bool(config, "header", true));
        String quote = NodeSql.text(config, "quote", null);
        if (quote != null && !quote.isEmpty()) {
            options.add("quote=" + NodeSql.literal(singleCharacter(quote, "quote")));
        }
        String escape = NodeSql.text(config, "escape", null);
        if (escape != null && !escape.isEmpty()) {
            options.add("escape=" + NodeSql.literal(singleCharacter(escape, "escape")));
        }
        String nullstr = NodeSql.text(config, "nullstr", null);
        if (nullstr != null && !nullstr.isEmpty()) {
            options.add("nullstr=" + NodeSql.literal(nullstr));
        }
        if (NodeSql.bool(config, "ignore_errors", false)) {
            options.add("ignore_errors=true");
        }
    }

    private static String singleCharacter(String value, String field) {
        if (value == null || value.length() != 1) {
            throw new IllegalArgumentException("file_reader: '" + field
                    + "' must be exactly one character, got '" + value + "'");
        }
        return value;
    }
}
