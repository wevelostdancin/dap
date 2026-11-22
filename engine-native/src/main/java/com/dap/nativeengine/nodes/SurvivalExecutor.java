package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.Distributions;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.Matrix;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * dap.survival: Kaplan-Meier curves and the log-rank test.
 *
 * <p>Rows are read into Java (capped at {@value #ROW_CAP} rows) because the curve is a
 * sequential product; the result is a DuckDB relation over a small loaded table (the
 * curve can have one row per event time) or a literal row for the log-rank test.</p>
 *
 * <p>Kaplan-Meier: one row per distinct event time with the number at risk, the events
 * and the censorings at that time, the survival S = product of (1 - d/n) and the
 * Greenwood interval on the log scale, {@code exp(log S -/+ z * sqrt(sum d/(n(n-d))))}
 * clamped to [0, 1] (the R {@code survfit} default {@code conf.type="log"}). When the
 * last observation is an event the survival is 0 and the interval is the maximal
 * [0, 1] because the Greenwood variance is infinite. Log-rank: the Mantel-Haenszel
 * statistic (O-E)'V^-1(O-E) over the first k-1 groups with the hypergeometric variance
 * and a chi-square p-value with k-1 degrees of freedom.</p>
 */
public final class SurvivalExecutor implements NodeExecutor {
    private static final String NODE = "survival";
    private static final List<String> MODES = List.of("km", "logrank");
    /** The curve is built in memory; same cap as the other in-memory statistics. */
    private static final int ROW_CAP = 100_000;
    private static final double Z = Distributions.normalQuantile(0.975);

    private static final List<ColumnSpec> LOGRANK = List.of(
            ColumnSpec.of("test", "Utf8"),
            ColumnSpec.of("statistic", "Float64"),
            ColumnSpec.of("df", "Int64"),
            ColumnSpec.of("p_value", "Float64"),
            ColumnSpec.of("groups", "Int64"));

    @Override
    public String nodeId() {
        return "dap.survival";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String mode = NodeSql.text(config, "mode", "km").trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException(NODE + ": unknown mode '" + mode
                    + "' (expected km or logrank)");
        }
        TimeAxis time = timeAxis(config, input);
        ColumnSpec event = eventColumn(config, input);
        ColumnSpec group = groupColumn(config, input);
        List<Observation> observations = readRows(context, input, time, event, group);
        if ("logrank".equals(mode)) {
            return logrank(context, observations, group);
        }
        return kaplanMeier(context, observations, time, group);
    }

    // ------------------------------------------------------------------ data

    /** The time column plus how it is read and written back. */
    private record TimeAxis(ColumnSpec column, boolean date, ColumnSpec output) {
    }

    private record Observation(double time, boolean event, String group) {
    }

    private static TimeAxis timeAxis(JsonNode config, LazyPlan input) {
        String name = required(config, "time");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown 'time' column '" + name + "'");
        }
        if ("Date32".equals(column.arrowType())) {
            return new TimeAxis(column, true, ColumnSpec.of("time", "Date32"));
        }
        if (!column.numeric()) {
            throw new IllegalArgumentException(NODE + ": 'time' column '" + name
                    + "' must be numeric or a date, got " + column.arrowType());
        }
        return new TimeAxis(column, false, ColumnSpec.of("time", "Float64"));
    }

    private static ColumnSpec eventColumn(JsonNode config, LazyPlan input) {
        String name = required(config, "event");
        ColumnSpec column;
        try {
            column = NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown 'event' column '" + name + "'");
        }
        if (!column.numeric() && !"Boolean".equals(column.arrowType())) {
            throw new IllegalArgumentException(NODE + ": 'event' column '" + name
                    + "' must be boolean or numeric 0/1, got " + column.arrowType());
        }
        return column;
    }

    private static ColumnSpec groupColumn(JsonNode config, LazyPlan input) {
        String name = NodeSql.text(config, "group", null);
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return NodeSql.column(input.columns(), name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(NODE + ": unknown 'group' column '" + name + "'");
        }
    }

    private static List<Observation> readRows(NodeContext context, LazyPlan input, TimeAxis time,
                                              ColumnSpec event, ColumnSpec group) throws SQLException {
        StringJoiner projection = new StringJoiner(", ");
        projection.add(time.date()
                ? "CAST(" + NodeSql.quote(time.column().name()) + " AS VARCHAR)"
                : "CAST(" + NodeSql.quote(time.column().name()) + " AS DOUBLE)");
        projection.add("Boolean".equals(event.arrowType())
                ? "CAST(" + NodeSql.quote(event.name()) + " AS INTEGER)"
                : "CAST(" + NodeSql.quote(event.name()) + " AS DOUBLE)");
        if (group != null) {
            projection.add("CAST(" + NodeSql.quote(group.name()) + " AS VARCHAR)");
        }
        StringJoiner where = new StringJoiner(" AND ");
        where.add(NodeSql.quote(time.column().name()) + " IS NOT NULL");
        where.add(NodeSql.quote(event.name()) + " IS NOT NULL");
        if (group != null) {
            where.add(NodeSql.quote(group.name()) + " IS NOT NULL");
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS dap_input"
                + " WHERE " + where + " LIMIT " + (ROW_CAP + 1);
        List<Observation> observations = new ArrayList<>();
        try (Connection connection = context.db().newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                double value = time.date()
                        ? LocalDate.parse(result.getString(1)).toEpochDay()
                        : result.getDouble(1);
                if (!(value >= 0.0)) {
                    throw new IllegalArgumentException(NODE + ": 'time' column '"
                            + time.column().name() + "' must be non-negative, got " + value);
                }
                double eventValue = result.getDouble(2);
                if (eventValue != 0.0 && eventValue != 1.0) {
                    throw new IllegalArgumentException(NODE + ": 'event' column '" + event.name()
                            + "' must be 0/1 or boolean, got " + eventValue);
                }
                String groupValue = group == null ? null : result.getString(3);
                observations.add(new Observation(value, eventValue == 1.0, groupValue));
            }
        }
        if (observations.size() > ROW_CAP) {
            throw new IllegalArgumentException(NODE + ": the survival analysis runs in memory and "
                    + "needs at most " + ROW_CAP + " rows, got more; filter or pre-aggregate the input");
        }
        return observations;
    }

    // -------------------------------------------------------------------- KM

    private static LazyPlan kaplanMeier(NodeContext context, List<Observation> observations,
                                        TimeAxis time, ColumnSpec group) throws SQLException {
        List<ColumnSpec> columns = new ArrayList<>();
        if (group != null) {
            columns.add(group);
        }
        columns.add(time.output());
        columns.add(ColumnSpec.of("n_at_risk", "Int64"));
        columns.add(ColumnSpec.of("events", "Int64"));
        columns.add(ColumnSpec.of("censored", "Int64"));
        columns.add(ColumnSpec.of("survival", "Float64"));
        columns.add(ColumnSpec.of("ci_low", "Float64"));
        columns.add(ColumnSpec.of("ci_high", "Float64"));

        Map<String, List<Observation>> buckets = buckets(observations, group);
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, List<Observation>> entry : buckets.entrySet()) {
            List<Observation> bucket = new ArrayList<>(entry.getValue());
            bucket.sort(Comparator.comparingDouble(Observation::time));
            double logSurvival = 0.0;
            double variance = 0.0;
            int index = 0;
            while (index < bucket.size()) {
                double atTime = bucket.get(index).time();
                int atRisk = bucket.size() - index;
                int events = 0;
                int censored = 0;
                while (index < bucket.size() && bucket.get(index).time() == atTime) {
                    if (bucket.get(index).event()) {
                        events++;
                    } else {
                        censored++;
                    }
                    index++;
                }
                if (events == 0) {
                    continue;
                }
                double survival;
                double ciLow;
                double ciHigh;
                if (events == atRisk) {
                    // The last observation is an event: S = 0 and the Greenwood variance
                    // is infinite, so the log interval is the maximal [0, 1].
                    survival = 0.0;
                    ciLow = 0.0;
                    ciHigh = 1.0;
                } else {
                    logSurvival += Math.log1p(-(double) events / atRisk);
                    variance += (double) events / ((double) atRisk * (atRisk - events));
                    survival = Math.exp(logSurvival);
                    double error = Z * Math.sqrt(variance);
                    ciLow = clamp(Math.exp(logSurvival - error));
                    ciHigh = clamp(Math.exp(logSurvival + error));
                }
                Object[] row = new Object[columns.size()];
                int column = 0;
                if (group != null) {
                    row[column++] = groupValue(group, entry.getKey());
                }
                row[column++] = time.date()
                        ? LocalDate.ofEpochDay(Math.round(atTime))
                        : (Object) atTime;
                row[column++] = (long) atRisk;
                row[column++] = (long) events;
                row[column++] = (long) censored;
                row[column++] = survival;
                row[column++] = ciLow;
                row[column++] = ciHigh;
                rows.add(row);
            }
        }
        return loadPlan(context, columns, rows);
    }

    // --------------------------------------------------------------- logrank

    private static LazyPlan logrank(NodeContext context, List<Observation> observations,
                                    ColumnSpec group) {
        if (group == null) {
            throw new IllegalArgumentException(NODE + ": mode logrank needs a 'group' column "
                    + "with at least two groups");
        }
        if (observations.isEmpty()) {
            return emptyPlan(context, LOGRANK);
        }
        Map<String, List<Observation>> buckets = buckets(observations, group);
        List<String> levels = new ArrayList<>(buckets.keySet());
        int groups = levels.size();
        if (groups < 2) {
            throw new IllegalArgumentException(NODE + ": mode logrank needs a 'group' column "
                    + "with at least two groups, got " + groups);
        }
        int[][] eventCounts = new int[groups][];
        double[][] times = new double[groups][];
        TreeSet<Double> eventTimes = new TreeSet<>();
        for (int index = 0; index < groups; index++) {
            List<Observation> bucket = new ArrayList<>(buckets.get(levels.get(index)));
            bucket.sort(Comparator.comparingDouble(Observation::time));
            times[index] = new double[bucket.size()];
            TreeMap<Double, Integer> eventsAt = new TreeMap<>();
            for (int i = 0; i < bucket.size(); i++) {
                times[index][i] = bucket.get(i).time();
                if (bucket.get(i).event()) {
                    eventsAt.merge(bucket.get(i).time(), 1, Integer::sum);
                    eventTimes.add(bucket.get(i).time());
                }
            }
            eventCounts[index] = new int[bucket.size()];
            int position = 0;
            for (Map.Entry<Double, Integer> entry : eventsAt.entrySet()) {
                while (position < bucket.size() && bucket.get(position).time() < entry.getKey()) {
                    position++;
                }
                eventCounts[index][position] = entry.getValue();
            }
        }
        if (eventTimes.isEmpty()) {
            throw new IllegalArgumentException(NODE + ": mode logrank needs at least one event, "
                    + "every observation is censored");
        }
        double[] observed = new double[groups];
        double[] expected = new double[groups];
        double[][] variance = new double[groups][groups];
        for (double atTime : eventTimes) {
            int atRisk = 0;
            int events = 0;
            int[] groupAtRisk = new int[groups];
            int[] groupEvents = new int[groups];
            for (int index = 0; index < groups; index++) {
                int position = lowerBound(times[index], atTime);
                groupAtRisk[index] = times[index].length - position;
                atRisk += groupAtRisk[index];
                if (position < times[index].length && times[index][position] == atTime) {
                    groupEvents[index] = eventCounts[index][position];
                }
                events += groupEvents[index];
            }
            if (events == 0 || atRisk == 0) {
                continue;
            }
            double factor = atRisk > 1 ? (double) events * (atRisk - events) / (atRisk - 1) : 0.0;
            for (int index = 0; index < groups; index++) {
                double proportion = (double) groupAtRisk[index] / atRisk;
                observed[index] += groupEvents[index];
                expected[index] += events * proportion;
                variance[index][index] += factor * proportion * (1.0 - proportion);
                for (int other = index + 1; other < groups; other++) {
                    double covariance = -factor * proportion
                            * ((double) groupAtRisk[other] / atRisk);
                    variance[index][other] += covariance;
                    variance[other][index] += covariance;
                }
            }
        }
        int degrees = groups - 1;
        double[][] reduced = new double[degrees][degrees];
        double[] difference = new double[degrees];
        for (int index = 0; index < degrees; index++) {
            difference[index] = observed[index] - expected[index];
            System.arraycopy(variance[index], 0, reduced[index], 0, degrees);
        }
        double[] solved;
        try {
            solved = Matrix.solve(reduced, difference);
        } catch (IllegalArgumentException singular) {
            throw new IllegalArgumentException(NODE + ": the log-rank variance matrix is singular; "
                    + "a group has no events or every event time is tied across groups", singular);
        }
        double statistic = 0.0;
        for (int index = 0; index < degrees; index++) {
            statistic += difference[index] * solved[index];
        }
        double pValue = clamp(1.0 - Distributions.chiSquareCdf(Math.max(0.0, statistic), degrees));
        String row = "SELECT " + NodeSql.literal("logrank") + " AS " + NodeSql.quote("test")
                + ", CAST(" + NodeSql.literal(Double.toString(statistic)) + " AS DOUBLE) AS "
                + NodeSql.quote("statistic")
                + ", CAST(" + degrees + " AS BIGINT) AS " + NodeSql.quote("df")
                + ", CAST(" + NodeSql.literal(Double.toString(pValue)) + " AS DOUBLE) AS "
                + NodeSql.quote("p_value")
                + ", CAST(" + groups + " AS BIGINT) AS " + NodeSql.quote("groups");
        return new LazyPlan(context.db(), row, LOGRANK);
    }

    // -------------------------------------------------------------- helpers

    /** Group buckets sorted by the text of the group; one bucket for the ungrouped curve. */
    private static Map<String, List<Observation>> buckets(List<Observation> observations,
                                                          ColumnSpec group) {
        Map<String, List<Observation>> buckets = new TreeMap<>();
        if (group == null) {
            buckets.put("", observations);
            return buckets;
        }
        for (Observation observation : observations) {
            buckets.computeIfAbsent(observation.group(), key -> new ArrayList<>()).add(observation);
        }
        return buckets;
    }

    private static int lowerBound(double[] sorted, double value) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /** Arrow-table value of a group key, preserving the input column type. */
    private static Object groupValue(ColumnSpec column, String text) {
        String type = column.arrowType();
        if (type.startsWith("Decimal128")) {
            return new BigDecimal(text);
        }
        return switch (type) {
            case "Int64" -> Long.parseLong(text);
            case "Float64" -> Double.parseDouble(text);
            case "Boolean" -> Boolean.parseBoolean(text);
            case "Date32" -> LocalDate.parse(text);
            case "Timestamp" -> LocalDateTime.parse(text.replace(' ', 'T'));
            default -> text;
        };
    }

    private static LazyPlan loadPlan(NodeContext context, List<ColumnSpec> columns,
                                     List<Object[]> rows) throws SQLException {
        try (ArrowTable table = ArrowTable.of(columns, rows)) {
            String name = context.db().load(table, "survival", context.workflowId(), context.nodeId());
            return new LazyPlan(context.db(), "SELECT * FROM " + NodeSql.quote(name), columns);
        }
    }

    private static LazyPlan emptyPlan(NodeContext context, List<ColumnSpec> columns) {
        StringJoiner select = new StringJoiner(", ");
        for (ColumnSpec column : columns) {
            select.add("CAST(NULL AS " + column.duckDbType() + ") AS " + NodeSql.quote(column.name()));
        }
        return new LazyPlan(context.db(), "SELECT " + select + " WHERE FALSE", columns);
    }

    private static double clamp(double probability) {
        return Math.max(0.0, Math.min(1.0, probability));
    }

    private static String required(JsonNode config, String field) {
        try {
            return NodeSql.requiredText(config, field);
        } catch (IllegalArgumentException missing) {
            throw new IllegalArgumentException(NODE + ": config field '" + field + "' is required");
        }
    }
}
