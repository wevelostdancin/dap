package com.dap.nativeengine.io;

import com.dap.nativeengine.api.ApiException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Allowlist/denylist for every local path the engine touches and for file literals inside
 * SQL (spec §8): the agent must not be able to read {@code ~/.ssh} or
 * {@code ~/.dsh/.credentials.yaml} through a reader node or through
 * {@code read_parquet('/etc/passwd')}.
 *
 * <p>Allowed roots default to the user home, the JVM temp directory, {@code DAP_HOME} and the
 * ingest cache; {@code DAP_ALLOWED_ROOTS} replaces them with an explicit list. The denylist
 * always wins: {@code DAP_DENY_ROOTS} is appended to the built-in secrets list. Remote URLs
 * ({@code http(s)://}, {@code s3://}, …) are not local paths and are passed through, so
 * DuckDB's httpfs can read them.
 */
public final class PathGuard {
    private static final String CODE = "path_not_allowed";
    /** Schemes DuckDB reads through httpfs; a SQL literal with one of them is not a local path. */
    private static final Set<String> REMOTE_SCHEMES = Set.of("http", "https", "s3", "gs", "gcs", "azure", "abfs", "abfss");
    /** DuckDB functions whose arguments are file paths. */
    private static final Set<String> READ_FUNCTIONS = Set.of(
            "read_parquet", "read_csv", "read_csv_auto", "read_json", "read_json_auto",
            "read_ndjson", "read_orc", "parquet_scan", "csv_scan", "json_scan", "glob");
    private static volatile PathGuard shared;

    private final List<Path> allowed;
    private final List<Path> denied;
    /** Both the configured and the real (symlink-resolved) form of every root. */
    private final List<Path> allowedMatch;
    private final List<Path> deniedMatch;

    public PathGuard(List<Path> allowed, List<Path> denied) {
        this.allowed = normalizeRoots(allowed);
        this.denied = normalizeRoots(denied);
        this.allowedMatch = expand(this.allowed);
        this.deniedMatch = expand(this.denied);
    }

    /** The process-wide guard, built from the environment once. */
    public static PathGuard shared() {
        PathGuard current = shared;
        if (current == null) {
            synchronized (PathGuard.class) {
                current = shared;
                if (current == null) {
                    current = fromEnv();
                    shared = current;
                }
            }
        }
        return current;
    }

    /** Reads {@code DAP_ALLOWED_ROOTS}/{@code DAP_DENY_ROOTS}; defaults per spec §8. */
    public static PathGuard fromEnv() {
        return fromEnv(System.getenv());
    }

    static PathGuard fromEnv(Map<String, String> env) {
        String configuredAllowed = blankToNull(env.get("DAP_ALLOWED_ROOTS"));
        List<Path> allowed = configuredAllowed != null
                ? split(configuredAllowed)
                : defaultAllowedRoots(env);
        List<Path> denied = new ArrayList<>(defaultDeniedRoots());
        String configuredDenied = blankToNull(env.get("DAP_DENY_ROOTS"));
        if (configuredDenied != null) {
            denied.addAll(split(configuredDenied));
        }
        return new PathGuard(allowed, denied);
    }

    private static List<Path> defaultAllowedRoots(Map<String, String> env) {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(Path.of(System.getProperty("user.home")));
        roots.add(Path.of(System.getProperty("java.io.tmpdir")));
        String dapHome = blankToNull(env.get("DAP_HOME"));
        Path home = dapHome != null ? Path.of(dapHome) : Path.of(System.getProperty("user.home"), ".dap");
        roots.add(home);
        String cache = blankToNull(env.get("DAP_CACHE_DIR"));
        roots.add(cache != null ? Path.of(cache) : home.resolve("build").resolve("cache"));
        return new ArrayList<>(roots);
    }

    private static List<Path> defaultDeniedRoots() {
        Path home = Path.of(System.getProperty("user.home"));
        return List.of(
                home.resolve(".ssh"),
                home.resolve(".gnupg"),
                home.resolve(".dsh"),
                home.resolve(".aws"),
                home.resolve(".kube"),
                home.resolve(".config").resolve("gcloud"),
                home.resolve("Library").resolve("Keychains"),
                home.resolve(".netrc"),
                home.resolve(".docker").resolve("config.json"));
    }

    private static List<Path> split(String value) {
        List<Path> paths = new ArrayList<>();
        for (String entry : value.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                paths.add(Path.of(entry.strip()));
            }
        }
        return paths;
    }

    private static List<Path> normalizeRoots(List<Path> roots) {
        if (roots == null) {
            return List.of();
        }
        Set<Path> normalized = new LinkedHashSet<>();
        for (Path root : roots) {
            if (root != null) {
                normalized.add(root.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(normalized);
    }

    private static List<Path> expand(List<Path> roots) {
        Set<Path> expanded = new LinkedHashSet<>(roots);
        for (Path root : roots) {
            Path real = realPath(root);
            if (real != null) {
                expanded.add(real);
            }
        }
        return List.copyOf(expanded);
    }

    /** The configured allowlist, for error hints. */
    public List<Path> allowedRoots() {
        return allowed;
    }

    public List<Path> deniedRoots() {
        return denied;
    }

    public Path checkRead(String raw) {
        return check(raw, "read");
    }

    public Path checkWrite(String raw) {
        return check(raw, "write");
    }

    private Path check(String raw, String action) {
        if (raw == null || raw.isBlank()) {
            throw notAllowed("(empty path)", action);
        }
        if (isRemote(raw)) {
            throw notAllowed(raw, action);
        }
        Path candidate;
        try {
            candidate = Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException invalid) {
            throw notAllowed(raw, action);
        }
        Path real = realPath(candidate);
        // The denylist wins over every allowlist entry, including a symlink into ~/.ssh.
        if (under(deniedMatch, candidate) || (real != null && under(deniedMatch, real))) {
            throw notAllowed(raw, action);
        }
        // For an existing path the resolved (symlink-free) location must be allowed too, so a
        // symlink inside an allowed directory cannot smuggle in /etc/passwd.
        Path effective = real != null ? real : candidate;
        boolean allowed = under(allowedMatch, effective);
        if (!allowed && real == null) {
            // A not-yet-created target under a symlinked directory: check the parent's real path.
            Path parent = candidate.getParent();
            Path parentReal = parent == null ? null : realPath(parent);
            if (parentReal != null && under(allowedMatch, parentReal.resolve(candidate.getFileName()))) {
                allowed = true;
            }
        }
        if (!allowed) {
            throw notAllowed(raw, action);
        }
        return real != null ? real : candidate;
    }

    /** True for a URL DuckDB reads through httpfs; those are not local paths. */
    public static boolean isRemote(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.strip();
        int scheme = value.indexOf("://");
        if (scheme <= 0) {
            return false;
        }
        return REMOTE_SCHEMES.contains(value.substring(0, scheme).toLowerCase(Locale.ROOT));
    }

    private static boolean under(List<Path> roots, Path candidate) {
        for (Path root : roots) {
            if (candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    private static Path realPath(Path path) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                return path.toRealPath();
            }
        } catch (IOException | SecurityException ignored) {
            // An unreadable path keeps its normalized form; the allowlist still applies.
        }
        return null;
    }

    /**
     * Reject file literals inside SQL: arguments of the {@code read_*}/scan/glob functions,
     * a literal directly after {@code FROM}/{@code JOIN}, and the target of
     * {@code COPY ... TO}. Quoted identifiers and comments are skipped.
     */
    public void checkSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        int index = 0;
        boolean copySeen = false;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'') {
                index = skipQuoted(sql, index, '\'');
                continue;
            }
            if (current == '"') {
                index = skipQuoted(sql, index, '"');
                continue;
            }
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                while (index < sql.length() && sql.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < sql.length()
                        && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) {
                    index++;
                }
                index = Math.min(sql.length(), index + 2);
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int start = index;
                while (index < sql.length()) {
                    char letter = sql.charAt(index);
                    if (!Character.isLetterOrDigit(letter) && letter != '_') {
                        break;
                    }
                    index++;
                }
                String word = sql.substring(start, index).toLowerCase(Locale.ROOT);
                if (READ_FUNCTIONS.contains(word)) {
                    index = checkFunctionArguments(sql, index);
                } else if ("from".equals(word) || "join".equals(word)) {
                    index = checkLiteralAfter(sql, index, false);
                } else if ("copy".equals(word)) {
                    copySeen = true;
                } else if ("to".equals(word) && copySeen) {
                    index = checkLiteralAfter(sql, index, true);
                }
                continue;
            }
            index++;
        }
    }

    /** Every string literal inside the argument list of a read function is a path. */
    private int checkFunctionArguments(String sql, int index) {
        int cursor = skipSpace(sql, index);
        if (cursor >= sql.length() || sql.charAt(cursor) != '(') {
            return index;
        }
        cursor++;
        int depth = 1;
        while (cursor < sql.length()) {
            char current = sql.charAt(cursor);
            if (current == '\'') {
                int end = skipQuoted(sql, cursor, '\'');
                String literal = unquote(sql, cursor, end);
                if (!isRemote(literal)) {
                    checkRead(literal);
                }
                cursor = end;
                continue;
            }
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return cursor + 1;
                }
            }
            cursor++;
        }
        return sql.length();
    }

    /** A single-quoted literal directly after FROM/JOIN (read) or TO of a COPY (write). */
    private int checkLiteralAfter(String sql, int index, boolean write) {
        int cursor = skipSpace(sql, index);
        if (cursor >= sql.length() || sql.charAt(cursor) != '\'') {
            return index;
        }
        int end = skipQuoted(sql, cursor, '\'');
        String value = unquote(sql, cursor, end);
        if (write) {
            checkWrite(value);
        } else {
            checkRead(value);
        }
        return end;
    }

    private static int skipSpace(String sql, int index) {
        int cursor = index;
        while (cursor < sql.length() && Character.isWhitespace(sql.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static String unquote(String sql, int start, int end) {
        String raw = sql.substring(start + 1, Math.max(start + 1, end - 1));
        return raw.replace("''", "'");
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            if (sql.charAt(index) == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return index;
    }

    private ApiException notAllowed(String raw, String action) {
        StringBuilder hint = new StringBuilder("allowed roots: ");
        if (allowed.isEmpty()) {
            hint.append("(none)");
        } else {
            for (int index = 0; index < allowed.size(); index++) {
                if (index > 0) {
                    hint.append(File.pathSeparator);
                }
                hint.append(allowed.get(index));
            }
        }
        hint.append("; set DAP_ALLOWED_ROOTS to extend the allowlist");
        String message = "path_not_allowed: cannot " + action + " '" + raw + "': it is outside the "
                + "allowed roots or inside a denied root (secrets such as ~/.ssh, ~/.dsh and "
                + "/etc/passwd are never readable)";
        return ApiException.badRequest(CODE, message, hint.toString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
