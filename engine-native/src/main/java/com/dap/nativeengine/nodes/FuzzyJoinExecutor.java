package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.fuzzy_join: joins two tables on similar text keys with a Jaro-Winkler, Levenshtein or
 * Damerau-Levenshtein similarity computed by DuckDB, keeping the best {@code max_matches}
 * candidates per left row.
 *
 * <p>Both inputs get a synthetic row id, candidates are produced by a cross join, filtered by
 * the threshold and ranked per left row; the matched pairs are then joined back so a left join
 * keeps every left row (with NULL similarity for an unmatched one).
 */
public final class FuzzyJoinExecutor implements NodeExecutor {
    private static final Set<String> METHODS = Set.of("jaro_winkler", "levenshtein", "damerau");
    private static final Set<String> HOWS = Set.of("inner", "left");

    @Override
    public String nodeId() {
        return "dap.fuzzy_join";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.get(0) == null) {
            throw new IllegalArgumentException("fuzzy_join: requires a connected left input");
        }
        if (inputs.size() < 2 || inputs.get(1) == null) {
            throw new IllegalArgumentException("fuzzy_join: requires a connected right input");
        }
        LazyPlan left = inputs.get(0);
        LazyPlan right = inputs.get(1);
        JsonNode config = context.config();
        String leftKey = key(config, "left_key", "left", left.columns());
        String rightKey = key(config, "right_key", "right", right.columns());
        String method = NodeSql.text(config, "method", "jaro_winkler").toLowerCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("fuzzy_join: 'method' must be one of "
                    + new java.util.TreeSet<>(METHODS) + ", got '" + method + "'");
        }
        double threshold = threshold(config);
        int maxMatches = maxMatches(config);
        String how = NodeSql.text(config, "how", "inner").toLowerCase(Locale.ROOT);
        if (!HOWS.contains(how)) {
            throw new IllegalArgumentException("fuzzy_join: 'how' must be inner or left, got '"
                    + how + "'");
        }
        String suffix = NodeSql.text(config, "suffix", " (Right)");
        if (suffix.isEmpty()) {
            throw new IllegalArgumentException("fuzzy_join: 'suffix' must not be empty");
        }

        // MATERIALIZED keeps one row id per input row: a plain CTE may be re-evaluated per
        // reference and row_number() OVER () could then assign different ids.
        String leftCte = "dap_left AS MATERIALIZED (SELECT *, row_number() OVER () AS dap_lid, "
                + NodeSql.quote(leftKey) + " AS dap_key FROM " + left.asTable() + ")";
        String rightCte = "dap_right AS MATERIALIZED (SELECT *, row_number() OVER () AS dap_rid, "
                + NodeSql.quote(rightKey) + " AS dap_key FROM " + right.asTable() + ")";
        String similarity = similarity(method, "l.\"dap_key\"", "r.\"dap_key\"");
        String candidates = "dap_matches AS (SELECT * FROM (SELECT l.dap_lid, r.dap_rid, "
                + similarity + " AS similarity FROM dap_left AS l CROSS JOIN dap_right AS r)"
                + " AS dap_candidates WHERE similarity >= " + Double.toString(threshold)
                + " QUALIFY row_number() OVER (PARTITION BY dap_lid"
                + " ORDER BY similarity DESC, dap_rid) <= " + maxMatches + ")";

        List<ColumnSpec> output = new ArrayList<>();
        Set<String> names = new HashSet<>();
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : left.columns()) {
            projection.add("l." + NodeSql.quote(column.name()));
            output.add(column);
            names.add(column.name());
        }
        for (ColumnSpec column : right.columns()) {
            String name = names.contains(column.name()) ? column.name() + suffix : column.name();
            projection.add("r." + NodeSql.quote(column.name()) + " AS " + NodeSql.quote(name));
            names.add(name);
            output.add(new ColumnSpec(name, column.arrowType(), column.knimeType()));
        }
        String similarityName = names.contains("similarity") ? "similarity" + suffix : "similarity";
        projection.add("dap_matches.similarity AS " + NodeSql.quote(similarityName));
        output.add(ColumnSpec.of(similarityName, "Float64"));

        String join = "left".equals(how) ? "LEFT JOIN" : "JOIN";
        String sql = "WITH " + leftCte + ", " + rightCte + ", " + candidates
                + " SELECT " + projection
                + " FROM dap_left AS l"
                + " " + join + " dap_matches ON dap_matches.dap_lid = l.dap_lid"
                + " LEFT JOIN dap_right AS r ON r.dap_rid = dap_matches.dap_rid";
        return new LazyPlan(context.db(), sql, output);
    }

    private static String similarity(String method, String left, String right) {
        return switch (method) {
            case "levenshtein" -> "CASE WHEN greatest(length(" + left + "), length(" + right
                    + ")) = 0 THEN 1.0 ELSE 1.0 - levenshtein(" + left + ", " + right
                    + ")::DOUBLE / greatest(length(" + left + "), length(" + right + ")) END";
            case "damerau" -> "CASE WHEN greatest(length(" + left + "), length(" + right
                    + ")) = 0 THEN 1.0 ELSE 1.0 - damerau_levenshtein(" + left + ", " + right
                    + ")::DOUBLE / greatest(length(" + left + "), length(" + right + ")) END";
            default -> "jaro_winkler_similarity(" + left + ", " + right + ")";
        };
    }

    private static String key(JsonNode config, String field, String side, List<ColumnSpec> columns) {
        String name = NodeSql.requiredText(config, field);
        for (ColumnSpec column : columns) {
            if (column.name().equals(name)) {
                if (!"Utf8".equals(column.arrowType())) {
                    throw new IllegalArgumentException("fuzzy_join: " + side + " key column '" + name
                            + "' is " + column.arrowType() + ", not a text (Utf8) column");
                }
                return name;
            }
        }
        throw new IllegalArgumentException("fuzzy_join: unknown " + side + " key column '" + name + "'");
    }

    private static double threshold(JsonNode config) {
        JsonNode node = config.get("threshold");
        double value = node == null || node.isNull() ? 0.8
                : node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText().strip());
        if (!(value >= 0.0 && value <= 1.0)) {
            throw new IllegalArgumentException("fuzzy_join: 'threshold' must be between 0 and 1, got "
                    + value);
        }
        return value;
    }

    private static int maxMatches(JsonNode config) {
        JsonNode node = config.get("max_matches");
        if (node == null || node.isNull()) {
            return 1;
        }
        if (!node.isIntegralNumber() || node.intValue() < 1) {
            throw new IllegalArgumentException("fuzzy_join: 'max_matches' must be a positive integer, got "
                    + node);
        }
        return node.intValue();
    }
}
