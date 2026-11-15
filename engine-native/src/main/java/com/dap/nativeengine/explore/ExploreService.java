package com.dap.nativeengine.explore;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One-pass exploration of an ingested dataset: column profile, quality score, pairwise
 * relationships, group differences, time span, hypotheses and recommendations.
 *
 * <p>The source is a Parquet file (the cache written by {@link
 * com.dap.nativeengine.io.IngestService}) exposed as the view {@code src}; an optional
 * read-only SQL query then refines it. Aggregates run in DuckDB over the whole relation,
 * while relationships, group differences and hypotheses read at most {@code sampleRows}
 * rows into Java (deterministic first rows in scan order, no random sampling).</p>
 */
public final class ExploreService {
    public static final int DEFAULT_MAX_COLUMNS = 50;
    public static final int DEFAULT_SAMPLE_ROWS = 1000;
    /** Upper bounds keep one request bounded in memory and response size. */
    public static final int MAX_COLUMNS_LIMIT = 200;
    public static final int MAX_SAMPLE_ROWS_LIMIT = 100_000;

    private static final int HIGH_PENALTY = 12;
    private static final int MEDIUM_PENALTY = 5;
    private static final int LOW_PENALTY = 1;
    private static final int HIGH_CARDINALITY_MIN_ROWS = 20;
    private static final double HIGH_CARDINALITY_RATIO = 0.9;
    private static final double OUTLIER_FENCE = 1.5;

    /** Profile the source as-is (no SQL refinement). */
    public ExploreResult explore(Path source, String sql, int maxColumns, int sampleRows)
            throws SQLException {
        return explore(source == null ? null : source.toString(), source, sql, maxColumns, sampleRows);
    }

    /**
     * Profile the source under an explicit label ({@code sourceLabel} is what the response
     * reports; {@code source} may be null for a standalone SQL query).
     */
    public ExploreResult explore(String sourceLabel, Path source, String sql,
                                 int maxColumns, int sampleRows) throws SQLException {
        validateMaxColumns(maxColumns);
        validateSampleRows(sampleRows);
        if (source == null && (sql == null || sql.isBlank())) {
            throw ApiException.badRequest(
                    "explore: pass a Parquet source or a read-only sql query");
        }
        if (source != null && !Files.isRegularFile(source)) {
            throw ApiException.notFound("dataset_not_found",
                    "explore: no such Parquet dataset: " + source,
                    "POST /v1/datasets converts a source file into the Parquet cache; "
                            + "pass the returned path as 'dataset'");
        }
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection()) {
            String base = baseSql(connection, source, sql);
            List<ColumnSpec> described;
            try {
                described = db.describe(connection, base);
            } catch (SQLException failure) {
                throw ApiException.badRequest("invalid_sql",
                        "explore: cannot analyse the query: " + failure.getMessage(),
                        "pass one read-only SELECT or WITH statement");
            }
            List<ColumnSpec> columns = List.copyOf(
                    described.subList(0, Math.min(maxColumns, described.size())));
            Profile profile = profile(connection, base, columns);
            List<Object[]> sample = sample(connection, base, columns.size(), sampleRows);
            List<ExploreResult.Issue> issues = new ArrayList<>();
            int score = quality(profile, columns, sample, issues);
            List<ExploreResult.Column> profileColumns = profile.columns().stream()
                    .map(ExploreService::view)
                    .toList();
            return new ExploreResult(sourceLabel, profile.rows(), profileColumns,
                    new ExploreResult.Quality(score, issues),
                    List.of(), List.of(), null, List.of(), List.of());
        }
    }

    private static void validateMaxColumns(int maxColumns) {
        if (maxColumns < 1 || maxColumns > MAX_COLUMNS_LIMIT) {
            throw ApiException.badRequest("explore: 'maxColumns' must be between 1 and "
                    + MAX_COLUMNS_LIMIT + ", got " + maxColumns);
        }
    }

    private static void validateSampleRows(int sampleRows) {
        if (sampleRows < 1 || sampleRows > MAX_SAMPLE_ROWS_LIMIT) {
            throw ApiException.badRequest("explore: 'sampleRows' must be between 1 and "
                    + MAX_SAMPLE_ROWS_LIMIT + ", got " + sampleRows);
        }
    }

    /** {@code src} over the Parquet file plus the optional read-only refinement query. */
    private static String baseSql(Connection connection, Path source, String sql) throws SQLException {
        if (source != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE VIEW src AS SELECT * FROM read_parquet("
                        + literal(source.toString()) + ")");
            }
        }
        return sql == null || sql.isBlank() ? "SELECT * FROM src" : sql;
    }

    /** One profiled column with the fences needed to describe its outliers. */
    private record ColumnStat(ColumnSpec spec, long missing, long distinct,
                              Double min, Double max, Double mean, Double stddev,
                              Long zeros, Long negatives, Long outliers,
                              Double lowerFence, Double upperFence) {
    }

    private record Profile(long rows, List<ColumnStat> columns, long duplicateRows) {
    }

    private static Profile profile(Connection connection, String base, List<ColumnSpec> columns)
            throws SQLException {
        StringBuilder aggregates = new StringBuilder("COUNT(*) AS \"dap_rows\"");
        for (int index = 0; index < columns.size(); index++) {
            String ref = "#" + (index + 1);
            aggregates.append(", COUNT(").append(ref).append(") AS \"c").append(index)
                    .append("_present\"");
            aggregates.append(", COUNT(DISTINCT ").append(ref).append(") AS \"c").append(index)
                    .append("_distinct\"");
            if (columns.get(index).numeric()) {
                aggregates.append(", MIN(").append(ref).append(") AS \"c").append(index)
                        .append("_min\"");
                aggregates.append(", MAX(").append(ref).append(") AS \"c").append(index)
                        .append("_max\"");
                aggregates.append(", AVG(").append(ref).append(") AS \"c").append(index)
                        .append("_mean\"");
                aggregates.append(", STDDEV_SAMP(").append(ref).append(") AS \"c").append(index)
                        .append("_stddev\"");
                aggregates.append(", COUNT(*) FILTER (WHERE ").append(ref)
                        .append(" = 0) AS \"c").append(index).append("_zeros\"");
                aggregates.append(", COUNT(*) FILTER (WHERE ").append(ref)
                        .append(" < 0) AS \"c").append(index).append("_negatives\"");
            }
        }
        long rows;
        long[] missing = new long[columns.size()];
        long[] distinct = new long[columns.size()];
        Double[] min = new Double[columns.size()];
        Double[] max = new Double[columns.size()];
        Double[] mean = new Double[columns.size()];
        Double[] stddev = new Double[columns.size()];
        Long[] zeros = new Long[columns.size()];
        Long[] negatives = new Long[columns.size()];
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT " + aggregates + " FROM (" + base + ") AS dap_explore")) {
            result.next();
            rows = result.getLong("dap_rows");
            for (int index = 0; index < columns.size(); index++) {
                long present = result.getLong("c" + index + "_present");
                missing[index] = rows - present;
                distinct[index] = result.getLong("c" + index + "_distinct");
                if (columns.get(index).numeric()) {
                    min[index] = asDouble(result.getObject("c" + index + "_min"));
                    max[index] = asDouble(result.getObject("c" + index + "_max"));
                    mean[index] = asDouble(result.getObject("c" + index + "_mean"));
                    stddev[index] = asDouble(result.getObject("c" + index + "_stddev"));
                    zeros[index] = result.getLong("c" + index + "_zeros");
                    negatives[index] = result.getLong("c" + index + "_negatives");
                }
            }
        }
        Double[] lowerFence = new Double[columns.size()];
        Double[] upperFence = new Double[columns.size()];
        quantiles(connection, base, columns, lowerFence, upperFence);
        Long[] outliers = outlierCounts(connection, base, columns, lowerFence, upperFence);
        long duplicateRows = rows == 0 ? 0 : rows - distinctRows(connection, base);
        List<ColumnStat> stats = new ArrayList<>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            stats.add(new ColumnStat(columns.get(index), missing[index], distinct[index],
                    min[index], max[index], mean[index], stddev[index],
                    zeros[index], negatives[index], outliers[index],
                    lowerFence[index], upperFence[index]));
        }
        return new Profile(rows, stats, duplicateRows);
    }

    /** First and third quartiles of every numeric column in one scan; null when undefined. */
    private static void quantiles(Connection connection, String base, List<ColumnSpec> columns,
                                  Double[] lowerFence, Double[] upperFence) throws SQLException {
        List<String> selects = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            if (!columns.get(index).numeric()) {
                continue;
            }
            indexes.add(index);
            selects.add("QUANTILE_CONT(#" + (index + 1) + ", 0.25) AS \"c" + index + "_q1\"");
            selects.add("QUANTILE_CONT(#" + (index + 1) + ", 0.75) AS \"c" + index + "_q3\"");
        }
        if (selects.isEmpty()) {
            return;
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + String.join(", ", selects)
                     + " FROM (" + base + ") AS dap_explore")) {
            result.next();
            for (int index : indexes) {
                Double q1 = asDouble(result.getObject("c" + index + "_q1"));
                Double q3 = asDouble(result.getObject("c" + index + "_q3"));
                if (q1 == null || q3 == null || !Double.isFinite(q1) || !Double.isFinite(q3)) {
                    continue;
                }
                double iqr = q3 - q1;
                lowerFence[index] = q1 - OUTLIER_FENCE * iqr;
                upperFence[index] = q3 + OUTLIER_FENCE * iqr;
            }
        }
    }

    /** Rows outside the 1.5x IQR fences of every numeric column in one scan. */
    private static Long[] outlierCounts(Connection connection, String base, List<ColumnSpec> columns,
                                        Double[] lowerFence, Double[] upperFence) throws SQLException {
        Long[] outliers = new Long[columns.size()];
        List<String> selects = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            if (!columns.get(index).numeric()) {
                continue;
            }
            outliers[index] = 0L;
            Double lower = lowerFence[index];
            Double upper = upperFence[index];
            if (lower == null || upper == null) {
                continue;
            }
            indexes.add(index);
            selects.add("COUNT(*) FILTER (WHERE #" + (index + 1) + " IS NOT NULL AND (#"
                    + (index + 1) + " < " + literal(lower) + " OR #" + (index + 1) + " > "
                    + literal(upper) + ")) AS \"c" + index + "_outliers\"");
        }
        if (selects.isEmpty()) {
            return outliers;
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + String.join(", ", selects)
                     + " FROM (" + base + ") AS dap_explore")) {
            result.next();
            for (int index : indexes) {
                outliers[index] = result.getLong("c" + index + "_outliers");
            }
        }
        return outliers;
    }

    private static long distinctRows(Connection connection, String base) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM (SELECT DISTINCT * FROM (" + base
                             + ") AS dap_explore) AS dap_distinct")) {
            result.next();
            return result.getLong(1);
        }
    }

    /** At most {@code limit} rows of the first {@code columnCount} columns, in scan order. */
    private static List<Object[]> sample(Connection connection, String base, int columnCount,
                                         int limit) throws SQLException {
        StringBuilder projection = new StringBuilder();
        for (int index = 1; index <= columnCount; index++) {
            if (index > 1) {
                projection.append(", ");
            }
            projection.append('#').append(index);
        }
        List<Object[]> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT " + projection + " FROM (" + base
                     + ") AS dap_explore LIMIT " + limit)) {
            while (result.next()) {
                Object[] row = new Object[columnCount];
                for (int index = 0; index < columnCount; index++) {
                    row[index] = result.getObject(index + 1);
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Deterministic quality score (100 minus issue penalties) plus the issues themselves. */
    private static int quality(Profile profile, List<ColumnSpec> columns, List<Object[]> sample,
                               List<ExploreResult.Issue> issues) {
        long rows = profile.rows();
        if (rows == 0) {
            return 100;
        }
        int penalty = 0;
        if (profile.duplicateRows() > 0) {
            boolean many = profile.duplicateRows() * 20 > rows;
            issues.add(new ExploreResult.Issue(many ? "medium" : "low", "duplicate_rows", null,
                    profile.duplicateRows(),
                    profile.duplicateRows() + " of " + rows + " rows are exact duplicates"));
            penalty += many ? MEDIUM_PENALTY : LOW_PENALTY;
        }
        for (int index = 0; index < profile.columns().size(); index++) {
            ColumnStat stat = profile.columns().get(index);
            String name = stat.spec().name();
            if (stat.missing() == rows) {
                issues.add(new ExploreResult.Issue("high", "empty", name, stat.missing(),
                        "column is entirely NULL"));
                penalty += HIGH_PENALTY;
            } else if (stat.missing() > 0) {
                String severity = stat.missing() * 2 > rows ? "high"
                        : stat.missing() * 5 > rows ? "medium" : "low";
                issues.add(new ExploreResult.Issue(severity, "missing", name, stat.missing(),
                        stat.missing() + " of " + rows + " values are NULL ("
                                + percent(stat.missing(), rows) + ")"));
                penalty += penalty(severity);
            }
            if (rows > 1 && stat.distinct() == 1 && stat.missing() < rows) {
                issues.add(new ExploreResult.Issue("low", "constant", name, rows,
                        "column has a single distinct value"));
                penalty += LOW_PENALTY;
            }
            if (rows >= HIGH_CARDINALITY_MIN_ROWS && stat.distinct() >= HIGH_CARDINALITY_MIN_ROWS
                    && stat.distinct() >= HIGH_CARDINALITY_RATIO * rows) {
                issues.add(new ExploreResult.Issue("medium", "high_cardinality", name,
                        stat.distinct(),
                        stat.distinct() + " distinct values in " + rows
                                + " rows; treat it as an identifier, not a category"));
                penalty += MEDIUM_PENALTY;
            }
            if (stat.outliers() != null && stat.outliers() > 0) {
                double ratio = (double) stat.outliers() / rows;
                if (ratio > 0.01) {
                    String severity = ratio > 0.1 ? "medium" : "low";
                    issues.add(new ExploreResult.Issue(severity, "outliers", name, stat.outliers(),
                            stat.outliers() + " rows outside 1.5x IQR ["
                                    + number(stat.lowerFence()) + ", "
                                    + number(stat.upperFence()) + "]"));
                    penalty += penalty(severity);
                }
            }
            if ("Utf8".equals(stat.spec().arrowType())) {
                int nonNull = 0;
                int numeric = 0;
                for (Object[] row : sample) {
                    Object value = row[index];
                    if (value == null) {
                        continue;
                    }
                    nonNull++;
                    if (looksNumeric(value.toString())) {
                        numeric++;
                    }
                }
                if (numeric > 0 && numeric < nonNull) {
                    issues.add(new ExploreResult.Issue("medium", "mixed_type", name, (long) numeric,
                            numeric + " of " + nonNull
                                    + " sampled text values look numeric; the column may mix "
                                    + "numbers and labels"));
                    penalty += MEDIUM_PENALTY;
                }
            }
        }
        return Math.max(0, Math.min(100, 100 - penalty));
    }

    private static int penalty(String severity) {
        return switch (severity) {
            case "high" -> HIGH_PENALTY;
            case "medium" -> MEDIUM_PENALTY;
            default -> LOW_PENALTY;
        };
    }

    private static boolean looksNumeric(String value) {
        try {
            Double.parseDouble(value.trim());
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static String percent(long part, long total) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
    }

    private static String number(Double value) {
        return value == null ? "?" : String.format(Locale.ROOT, "%.4g", value);
    }

    private static ExploreResult.Column view(ColumnStat stat) {
        return new ExploreResult.Column(stat.spec().name(), stat.spec().arrowType(),
                stat.missing(), stat.distinct(), stat.min(), stat.max(), stat.mean(),
                stat.stddev(), stat.zeros(), stat.negatives(), stat.outliers());
    }

    private static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.math.BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** Double literal for a fence: finite values only, formatted deterministically. */
    private static String literal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite literal: " + value);
        }
        return Double.toString(value);
    }
}
