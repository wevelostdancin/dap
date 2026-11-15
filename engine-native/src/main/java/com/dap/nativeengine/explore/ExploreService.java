package com.dap.nativeengine.explore;

import com.dap.nativeengine.api.ApiException;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.Distributions;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.io.PathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private static final int MAX_RELATIONSHIPS = 20;
    private static final int MAX_GROUP_DIFFERENCES = 10;
    private static final int MAX_HYPOTHESES = 5;
    private static final int MIN_HYPOTHESES = 3;
    private static final int MAX_RECOMMENDATIONS = 8;
    private static final int MAX_CATEGORIES = 20;
    private static final int SMALL_GROUP = 5;
    private static final int HIGH_PENALTY = 12;
    private static final int MEDIUM_PENALTY = 5;
    private static final int LOW_PENALTY = 1;
    private static final int HIGH_CARDINALITY_MIN_ROWS = 20;
    private static final double HIGH_CARDINALITY_RATIO = 0.9;
    private static final double OUTLIER_FENCE = 1.5;

    private final String memoryLimit;
    private final Integer threads;
    private final String tempDirectory;
    private final String extensionDirectory;

    /** Production service: DuckDB resources stay at their defaults. */
    public ExploreService() {
        this(null, null, null, null);
    }

    /** Service with the engine's DuckDB resource settings, so explore respects memory/temp limits. */
    public ExploreService(String memoryLimit, Integer threads, String tempDirectory,
                          String extensionDirectory) {
        this.memoryLimit = memoryLimit;
        this.threads = threads;
        this.tempDirectory = tempDirectory;
        this.extensionDirectory = extensionDirectory;
    }

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
        if (source != null) {
            PathGuard.shared().checkRead(source.toAbsolutePath().toString());
        }
        if (sql != null && !sql.isBlank()) {
            PathGuard.shared().checkSql(sql);
        }
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
        try (DuckDbSession db = new DuckDbSession(memoryLimit, threads, tempDirectory, extensionDirectory);
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
            List<ExploreResult.Relationship> relationships = relationships(columns, sample);
            List<ExploreResult.GroupDifference> groupDifferences = groupDifferences(columns, sample);
            ExploreResult.TimeSpan time = timeSpan(connection, base, columns);
            List<ExploreResult.Hypothesis> hypotheses = hypotheses(columns, profile, relationships,
                    groupDifferences, time, score, issues);
            List<String> recommendations = recommendations(profile, columns, relationships,
                    groupDifferences, time);
            return new ExploreResult(sourceLabel, profile.rows(), profileColumns,
                    new ExploreResult.Quality(score, issues), relationships, groupDifferences,
                    time, hypotheses, recommendations);
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

    /** Pearson for numeric pairs and Cramer's V for categorical pairs, capped and ordered. */
    private static List<ExploreResult.Relationship> relationships(List<ColumnSpec> columns,
                                                                   List<Object[]> sample) {
        List<ExploreResult.Relationship> found = new ArrayList<>();
        for (int left = 0; left < columns.size(); left++) {
            for (int right = left + 1; right < columns.size(); right++) {
                ColumnSpec first = columns.get(left);
                ColumnSpec second = columns.get(right);
                ExploreResult.Relationship pair;
                if (first.numeric() && second.numeric()) {
                    pair = pearson(first, second, sample, left, right);
                } else if (categorical(first) && categorical(second)) {
                    pair = cramersV(first, second, sample, left, right);
                } else {
                    continue;
                }
                if (pair != null) {
                    found.add(pair);
                }
            }
        }
        found.sort(Comparator.comparingDouble((ExploreResult.Relationship pair) -> -Math.abs(pair.value()))
                .thenComparing(ExploreResult.Relationship::left)
                .thenComparing(ExploreResult.Relationship::right)
                .thenComparing(ExploreResult.Relationship::method));
        return List.copyOf(found.subList(0, Math.min(MAX_RELATIONSHIPS, found.size())));
    }

    private static ExploreResult.Relationship pearson(ColumnSpec left, ColumnSpec right,
                                                      List<Object[]> sample, int leftIndex,
                                                      int rightIndex) {
        int n = 0;
        double sumLeft = 0;
        double sumRight = 0;
        for (Object[] row : sample) {
            Double leftValue = asDouble(row[leftIndex]);
            Double rightValue = asDouble(row[rightIndex]);
            if (leftValue == null || rightValue == null) {
                continue;
            }
            n++;
            sumLeft += leftValue;
            sumRight += rightValue;
        }
        if (n < 3) {
            return null;
        }
        double meanLeft = sumLeft / n;
        double meanRight = sumRight / n;
        double covariance = 0;
        double varianceLeft = 0;
        double varianceRight = 0;
        for (Object[] row : sample) {
            Double leftValue = asDouble(row[leftIndex]);
            Double rightValue = asDouble(row[rightIndex]);
            if (leftValue == null || rightValue == null) {
                continue;
            }
            double deltaLeft = leftValue - meanLeft;
            double deltaRight = rightValue - meanRight;
            covariance += deltaLeft * deltaRight;
            varianceLeft += deltaLeft * deltaLeft;
            varianceRight += deltaRight * deltaRight;
        }
        if (varianceLeft <= 0 || varianceRight <= 0) {
            return null;
        }
        double r = Math.max(-1.0, Math.min(1.0, covariance / Math.sqrt(varianceLeft * varianceRight)));
        return new ExploreResult.Relationship(left.name(), right.name(), "pearson", r,
                pearsonPValue(r, n), n, strength(r));
    }

    /** Two-sided p-value of a Pearson correlation: t with n-2 degrees of freedom. */
    private static double pearsonPValue(double r, long n) {
        if (n <= 2) {
            return Double.NaN;
        }
        if (Math.abs(r) >= 1.0) {
            return 0.0;
        }
        double degrees = n - 2;
        double t = r * Math.sqrt(degrees / (1.0 - r * r));
        return clamp01(2.0 * (1.0 - Distributions.tCdf(Math.abs(t), degrees)));
    }

    private static ExploreResult.Relationship cramersV(ColumnSpec left, ColumnSpec right,
                                                       List<Object[]> sample, int leftIndex,
                                                       int rightIndex) {
        Map<String, Integer> leftCounts = new LinkedHashMap<>();
        Map<String, Integer> rightCounts = new LinkedHashMap<>();
        Map<String, Integer> cells = new LinkedHashMap<>();
        int n = 0;
        for (Object[] row : sample) {
            Object leftValue = row[leftIndex];
            Object rightValue = row[rightIndex];
            if (leftValue == null || rightValue == null) {
                continue;
            }
            String leftKey = leftValue.toString();
            String rightKey = rightValue.toString();
            leftCounts.merge(leftKey, 1, Integer::sum);
            rightCounts.merge(rightKey, 1, Integer::sum);
            cells.merge(leftKey + "\u0000" + rightKey, 1, Integer::sum);
            n++;
        }
        if (n < 5 || leftCounts.size() < 2 || rightCounts.size() < 2
                || leftCounts.size() > MAX_CATEGORIES || rightCounts.size() > MAX_CATEGORIES) {
            return null;
        }
        double chiSquare = 0;
        for (Map.Entry<String, Integer> leftCell : leftCounts.entrySet()) {
            for (Map.Entry<String, Integer> rightCell : rightCounts.entrySet()) {
                double expected = (double) leftCell.getValue() * rightCell.getValue() / n;
                if (expected <= 0) {
                    continue;
                }
                int observed = cells.getOrDefault(leftCell.getKey() + "\u0000" + rightCell.getKey(), 0);
                double delta = observed - expected;
                chiSquare += delta * delta / expected;
            }
        }
        int degrees = (leftCounts.size() - 1) * (rightCounts.size() - 1);
        if (degrees <= 0) {
            return null;
        }
        double p = clamp01(1.0 - Distributions.chiSquareCdf(chiSquare, degrees));
        double v = Math.sqrt(chiSquare / (n * (double) Math.min(leftCounts.size() - 1,
                rightCounts.size() - 1)));
        v = Math.min(1.0, v);
        return new ExploreResult.Relationship(left.name(), right.name(), "cramers_v", v, p, n,
                strength(v));
    }

    /** ANOVA when every group has at least five values, Kruskal-Wallis otherwise. */
    private static List<ExploreResult.GroupDifference> groupDifferences(List<ColumnSpec> columns,
                                                                        List<Object[]> sample) {
        List<ExploreResult.GroupDifference> found = new ArrayList<>();
        for (int valueIndex = 0; valueIndex < columns.size(); valueIndex++) {
            if (!columns.get(valueIndex).numeric()) {
                continue;
            }
            for (int groupIndex = 0; groupIndex < columns.size(); groupIndex++) {
                if (!categorical(columns.get(groupIndex))) {
                    continue;
                }
                Map<String, List<Double>> groups = new LinkedHashMap<>();
                for (Object[] row : sample) {
                    Double value = asDouble(row[valueIndex]);
                    Object group = row[groupIndex];
                    if (value == null || group == null) {
                        continue;
                    }
                    groups.computeIfAbsent(group.toString(), key -> new ArrayList<>()).add(value);
                }
                if (groups.size() < 2 || groups.size() > MAX_CATEGORIES) {
                    continue;
                }
                int smallest = Integer.MAX_VALUE;
                for (List<Double> values : groups.values()) {
                    smallest = Math.min(smallest, values.size());
                }
                if (smallest < 2) {
                    continue;
                }
                if (smallest < SMALL_GROUP) {
                    kruskal(columns.get(valueIndex), columns.get(groupIndex), groups, found);
                } else {
                    anova(columns.get(valueIndex), columns.get(groupIndex), groups, found);
                }
            }
        }
        found.sort(Comparator.comparingDouble(ExploreResult.GroupDifference::pValue)
                .thenComparing(ExploreResult.GroupDifference::value)
                .thenComparing(ExploreResult.GroupDifference::group));
        return List.copyOf(found.subList(0, Math.min(MAX_GROUP_DIFFERENCES, found.size())));
    }

    private static void anova(ColumnSpec value, ColumnSpec group, Map<String, List<Double>> groups,
                              List<ExploreResult.GroupDifference> found) {
        int n = 0;
        double sum = 0;
        for (List<Double> values : groups.values()) {
            n += values.size();
            for (double sample : values) {
                sum += sample;
            }
        }
        int k = groups.size();
        if (n <= k) {
            return;
        }
        double grandMean = sum / n;
        double between = 0;
        double within = 0;
        for (List<Double> values : groups.values()) {
            double groupSum = 0;
            for (double sample : values) {
                groupSum += sample;
            }
            double groupMean = groupSum / values.size();
            between += values.size() * (groupMean - grandMean) * (groupMean - grandMean);
            for (double sample : values) {
                within += (sample - groupMean) * (sample - groupMean);
            }
        }
        if (within <= 0) {
            return;
        }
        int dfBetween = k - 1;
        int dfWithin = n - k;
        double f = (between / dfBetween) / (within / dfWithin);
        double p = clamp01(1.0 - Distributions.fCdf(f, dfBetween, dfWithin));
        found.add(new ExploreResult.GroupDifference(value.name(), group.name(), "anova", f, p,
                between / (between + within)));
    }

    private static void kruskal(ColumnSpec value, ColumnSpec group, Map<String, List<Double>> groups,
                                List<ExploreResult.GroupDifference> found) {
        List<String> names = new ArrayList<>(groups.keySet());
        int n = 0;
        for (List<Double> values : groups.values()) {
            n += values.size();
        }
        int k = names.size();
        if (n <= k) {
            return;
        }
        double[] values = new double[n];
        int[] groupIndex = new int[n];
        int position = 0;
        for (int index = 0; index < k; index++) {
            for (double sample : groups.get(names.get(index))) {
                values[position] = sample;
                groupIndex[position] = index;
                position++;
            }
        }
        Integer[] order = new Integer[n];
        for (int index = 0; index < n; index++) {
            order[index] = index;
        }
        java.util.Arrays.sort(order, (first, second) -> Double.compare(values[first], values[second]));
        double[] ranks = new double[n];
        double ties = 0;
        int index = 0;
        while (index < n) {
            int last = index;
            while (last + 1 < n
                    && Double.compare(values[order[last + 1]], values[order[index]]) == 0) {
                last++;
            }
            double rank = (index + last) / 2.0 + 1.0;
            for (int rankIndex = index; rankIndex <= last; rankIndex++) {
                ranks[order[rankIndex]] = rank;
            }
            int tieSize = last - index + 1;
            if (tieSize > 1) {
                ties += (double) tieSize * tieSize * tieSize - tieSize;
            }
            index = last + 1;
        }
        double[] rankSums = new double[k];
        int[] sizes = new int[k];
        for (int row = 0; row < n; row++) {
            rankSums[groupIndex[row]] += ranks[row];
            sizes[groupIndex[row]]++;
        }
        double h = 0;
        for (int groupIndexValue = 0; groupIndexValue < k; groupIndexValue++) {
            h += rankSums[groupIndexValue] * rankSums[groupIndexValue] / sizes[groupIndexValue];
        }
        h = 12.0 / (n * (double) (n + 1)) * h - 3.0 * (n + 1);
        double correction = 1.0 - ties / ((double) n * n * n - n);
        if (correction > 0) {
            h /= correction;
        }
        double p = clamp01(1.0 - Distributions.chiSquareCdf(Math.max(0.0, h), k - 1));
        double epsilonSquared = Math.max(0.0, (h - k + 1) / (double) (n - k));
        found.add(new ExploreResult.GroupDifference(value.name(), group.name(), "kruskal", h, p,
                Math.min(1.0, epsilonSquared)));
    }

    /** Span of the first date/timestamp column: from, to and an inferred granularity. */
    private static ExploreResult.TimeSpan timeSpan(Connection connection, String base,
                                                   List<ColumnSpec> columns) throws SQLException {
        for (int index = 0; index < columns.size(); index++) {
            if (!temporal(columns.get(index))) {
                continue;
            }
            String ref = "#" + (index + 1);
            String sql = "SELECT CAST(MIN(" + ref + ") AS VARCHAR) AS \"from_value\", "
                    + "CAST(MAX(" + ref + ") AS VARCHAR) AS \"to_value\", "
                    + "DATE_DIFF('day', MIN(" + ref + "), MAX(" + ref + ")) AS \"span_days\", "
                    + "COUNT(DISTINCT " + ref + ") AS \"distinct_values\" "
                    + "FROM (" + base + ") AS dap_explore";
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                result.next();
                String from = result.getString("from_value");
                if (from == null) {
                    continue;
                }
                long spanDays = result.getLong("span_days");
                long distinct = result.getLong("distinct_values");
                return new ExploreResult.TimeSpan(columns.get(index).name(),
                        granularity(spanDays, distinct), from, result.getString("to_value"));
            }
        }
        return null;
    }

    private static String granularity(long spanDays, long distinct) {
        if (distinct <= 1 || spanDays <= 0) {
            return "day";
        }
        double gap = (double) spanDays / (distinct - 1);
        if (gap >= 300) {
            return "year";
        }
        if (gap >= 25) {
            return "month";
        }
        if (gap >= 6) {
            return "week";
        }
        return "day";
    }

    /** Three to five templated next questions, ordered by priority then id. */
    private static List<ExploreResult.Hypothesis> hypotheses(List<ColumnSpec> columns, Profile profile,
                                                             List<ExploreResult.Relationship> relationships,
                                                             List<ExploreResult.GroupDifference> differences,
                                                             ExploreResult.TimeSpan time, int score,
                                                             List<ExploreResult.Issue> issues) {
        if (profile.rows() == 0) {
            return List.of();
        }
        List<ExploreResult.Hypothesis> candidates = new ArrayList<>();
        int relationshipCandidates = 0;
        for (ExploreResult.Relationship pair : relationships) {
            if (relationshipCandidates >= 2 || Math.abs(pair.value()) < 0.5) {
                break;
            }
            String priority = Math.abs(pair.value()) >= 0.7 ? "high" : "medium";
            String association = "pearson".equals(pair.method()) ? "correlation" : "association";
            candidates.add(new ExploreResult.Hypothesis("",
                    "Do " + pair.left() + " and " + pair.right() + " move together?",
                    pair.method(), List.of(pair.left(), pair.right()),
                    capitalised(pair.strength()) + " " + association + " ("
                            + pair.method() + "=" + decimal(pair.value()) + ", "
                            + pText(pair.pValue()) + ", n=" + pair.n() + ").",
                    priority));
            relationshipCandidates++;
        }
        int differenceCandidates = 0;
        for (ExploreResult.GroupDifference difference : differences) {
            if (differenceCandidates >= 2 || difference.pValue() >= 0.05) {
                break;
            }
            String priority = difference.pValue() < 0.001 ? "high" : "medium";
            String effect = "anova".equals(difference.test()) ? "eta squared" : "epsilon squared";
            candidates.add(new ExploreResult.Hypothesis("",
                    "Does " + difference.value() + " differ across " + difference.group()
                            + " groups?",
                    difference.test(), List.of(difference.value(), difference.group()),
                    testName(difference.test()) + " (statistic="
                            + decimal(difference.statistic()) + ", " + pText(difference.pValue())
                            + ", " + effect + "=" + decimal(difference.effectSize()) + ").",
                    priority));
            differenceCandidates++;
        }
        for (ColumnStat stat : profile.columns()) {
            if (stat.missing() == 0 || stat.missing() == profile.rows()
                    || (double) stat.missing() / profile.rows() < 0.1) {
                continue;
            }
            String priority = stat.missing() * 2 > profile.rows() ? "high" : "medium";
            candidates.add(new ExploreResult.Hypothesis("",
                    "Is " + stat.spec().name() + " missing at random?",
                    "missingness", List.of(stat.spec().name()),
                    stat.missing() + " of " + profile.rows() + " values are NULL ("
                            + percent(stat.missing(), profile.rows()) + ").",
                    priority));
            break;
        }
        for (ColumnStat stat : profile.columns()) {
            if (stat.outliers() == null || stat.outliers() < 3) {
                continue;
            }
            candidates.add(new ExploreResult.Hypothesis("",
                    "Are the " + stat.outliers() + " outliers in " + stat.spec().name()
                            + " data errors or a real subgroup?",
                    "outliers", List.of(stat.spec().name()),
                    stat.outliers() + " rows outside 1.5x IQR [" + number(stat.lowerFence())
                            + ", " + number(stat.upperFence()) + "].",
                    "low"));
            break;
        }
        ColumnSpec numeric = firstNumeric(columns);
        if (time != null && numeric != null) {
            candidates.add(new ExploreResult.Hypothesis("",
                    "Is there a trend in " + numeric.name() + " over " + time.column() + "?",
                    "trend", List.of(time.column(), numeric.name()),
                    "Time column spans " + time.from() + " to " + time.to() + " with "
                            + time.granularity() + " granularity.",
                    "medium"));
        }
        if (profile.duplicateRows() > 0) {
            List<String> names = columns.stream().map(ColumnSpec::name).toList();
            candidates.add(new ExploreResult.Hypothesis("",
                    "Are the " + profile.duplicateRows() + " duplicate rows expected?",
                    "duplicates", names,
                    profile.duplicateRows() + " rows repeat exactly across all " + names.size()
                            + " columns.",
                    "low"));
        }
        if (candidates.size() < MIN_HYPOTHESES) {
            List<String> names = columns.stream().map(ColumnSpec::name).toList();
            if (numeric != null && candidates.size() < MIN_HYPOTHESES) {
                ColumnStat stat = statOf(profile, numeric.name());
                candidates.add(new ExploreResult.Hypothesis("",
                        "What is the distribution of " + numeric.name() + "?",
                        "distribution", List.of(numeric.name()),
                        "mean=" + decimal(stat == null ? null : stat.mean())
                                + ", stddev=" + decimal(stat == null ? null : stat.stddev()) + ".",
                        "low"));
            }
            if (candidates.size() < MIN_HYPOTHESES) {
                candidates.add(new ExploreResult.Hypothesis("",
                        "Is the data quality good enough for modelling?",
                        "quality", names,
                        "Quality score " + score + " with " + issues.size() + " issues.",
                        "low"));
            }
            if (candidates.size() < MIN_HYPOTHESES && names.size() >= 2) {
                candidates.add(new ExploreResult.Hypothesis("",
                        "Which groups should be compared next?",
                        "group_comparison", List.of(names.get(0), names.get(1)),
                        "Compare the main measure with the strongest categorical segment.",
                        "low"));
            }
        }
        candidates.sort(Comparator.comparingInt(
                (ExploreResult.Hypothesis hypothesis) -> priorityRank(hypothesis.priority())));
        List<ExploreResult.Hypothesis> result = new ArrayList<>();
        int count = Math.min(MAX_HYPOTHESES, candidates.size());
        for (int index = 0; index < count; index++) {
            ExploreResult.Hypothesis candidate = candidates.get(index);
            result.add(new ExploreResult.Hypothesis("h" + (index + 1), candidate.question(),
                    candidate.test(), candidate.columns(), candidate.rationale(),
                    candidate.priority()));
        }
        return List.copyOf(result);
    }

    /** Concrete next steps derived from the findings, strongest first. */
    private static List<String> recommendations(Profile profile, List<ColumnSpec> columns,
                                                List<ExploreResult.Relationship> relationships,
                                                List<ExploreResult.GroupDifference> differences,
                                                ExploreResult.TimeSpan time) {
        long rows = profile.rows();
        if (rows == 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (profile.duplicateRows() > 0) {
            result.add("Deduplicate " + profile.duplicateRows()
                    + " repeated rows (or confirm they are expected).");
        }
        int missingAdded = 0;
        for (ColumnStat stat : profile.columns()) {
            if (missingAdded >= 3) {
                break;
            }
            if (stat.missing() == 0 || stat.missing() == rows) {
                continue;
            }
            result.add("Review " + stat.missing() + " missing values in " + stat.spec().name()
                    + " (" + percent(stat.missing(), rows) + ").");
            missingAdded++;
        }
        for (ColumnStat stat : profile.columns()) {
            if (stat.missing() == rows) {
                result.add("Drop empty column " + stat.spec().name() + ".");
            } else if (rows > 1 && stat.distinct() == 1) {
                result.add("Drop constant column " + stat.spec().name() + ".");
            }
        }
        int cardinalityAdded = 0;
        for (ColumnStat stat : profile.columns()) {
            if (cardinalityAdded >= 2) {
                break;
            }
            if (rows >= HIGH_CARDINALITY_MIN_ROWS && stat.distinct() >= HIGH_CARDINALITY_MIN_ROWS
                    && stat.distinct() >= HIGH_CARDINALITY_RATIO * rows) {
                result.add("Treat " + stat.spec().name()
                        + " as an identifier, not a category.");
                cardinalityAdded++;
            }
        }
        int outliersAdded = 0;
        for (ColumnStat stat : profile.columns()) {
            if (outliersAdded >= 3) {
                break;
            }
            if (stat.outliers() != null && stat.outliers() > 0) {
                result.add("Inspect " + stat.outliers() + " outliers in " + stat.spec().name()
                        + " (outside 1.5x IQR).");
                outliersAdded++;
            }
        }
        int relationshipAdded = 0;
        for (ExploreResult.Relationship pair : relationships) {
            if (relationshipAdded >= 3 || Math.abs(pair.value()) < 0.5) {
                break;
            }
            result.add("Model " + pair.right() + " with " + pair.left() + " (" + pair.method()
                    + "=" + decimal(pair.value()) + ", " + pText(pair.pValue()) + ").");
            relationshipAdded++;
        }
        int differenceAdded = 0;
        for (ExploreResult.GroupDifference difference : differences) {
            if (differenceAdded >= 3 || difference.pValue() >= 0.05) {
                break;
            }
            result.add("Compare " + difference.value() + " across " + difference.group()
                    + " groups (" + testName(difference.test()) + " " + pText(difference.pValue())
                    + ", effect size " + decimal(difference.effectSize()) + ").");
            differenceAdded++;
        }
        ColumnSpec numeric = firstNumeric(columns);
        if (time != null && numeric != null) {
            result.add("Analyze " + numeric.name() + " over " + time.column()
                    + " as a time series (" + time.granularity() + " granularity).");
        }
        return List.copyOf(result.subList(0, Math.min(MAX_RECOMMENDATIONS, result.size())));
    }

    private static ColumnStat statOf(Profile profile, String name) {
        for (ColumnStat stat : profile.columns()) {
            if (stat.spec().name().equals(name)) {
                return stat;
            }
        }
        return null;
    }

    private static ColumnSpec firstNumeric(List<ColumnSpec> columns) {
        for (ColumnSpec column : columns) {
            if (column.numeric()) {
                return column;
            }
        }
        return null;
    }

    private static boolean categorical(ColumnSpec column) {
        return "Utf8".equals(column.arrowType()) || "Boolean".equals(column.arrowType());
    }

    private static boolean temporal(ColumnSpec column) {
        return "Date32".equals(column.arrowType()) || "Timestamp".equals(column.arrowType());
    }

    private static int priorityRank(String priority) {
        return switch (priority) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    private static String strength(double value) {
        double magnitude = Math.abs(value);
        if (magnitude >= 0.9) {
            return "very strong";
        }
        if (magnitude >= 0.7) {
            return "strong";
        }
        if (magnitude >= 0.5) {
            return "moderate";
        }
        if (magnitude >= 0.3) {
            return "weak";
        }
        return "very weak";
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Display name of a group-difference test for human-readable rationales. */
    private static String testName(String test) {
        return switch (test) {
            case "anova" -> "ANOVA";
            case "kruskal" -> "Kruskal-Wallis";
            default -> test;
        };
    }

    private static String decimal(Double value) {
        return value == null ? "?" : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String pText(Double p) {
        if (p == null) {
            return "p=?";
        }
        return p < 0.001 ? "p<0.001" : String.format(Locale.ROOT, "p=%.3f", p);
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