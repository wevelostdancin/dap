package com.dap.nativeengine.explore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response of {@code POST /v1/explore}: column profile, quality score, pairwise
 * relationships, group differences, time span, hypotheses and recommendations.
 *
 * <p>{@link #toMap()} renders the wire shape of the design spec (snake_case keys,
 * null optional numbers omitted) so the handler stays a thin adapter.</p>
 */
public record ExploreResult(
        String source,
        long rows,
        boolean qualitySampled,
        long qualityScannedRows,
        List<Column> columns,
        Quality quality,
        List<Relationship> relationships,
        List<GroupDifference> groupDifferences,
        TimeSpan time,
        List<Hypothesis> hypotheses,
        List<String> recommendations) {

    public ExploreResult {
        columns = List.copyOf(columns);
        relationships = List.copyOf(relationships);
        groupDifferences = List.copyOf(groupDifferences);
        hypotheses = List.copyOf(hypotheses);
        recommendations = List.copyOf(recommendations);
    }

    /** One profiled column. Numeric statistics are null for non-numeric columns. */
    public record Column(String name, String type, long missing, long distinct,
                         boolean distinctExact, Double min, Double max, Double mean, Double stddev,
                         Long zeros, Long negatives, Long outliers) {
    }

    /** Deterministic 0-100 quality score and the issues that lowered it. */
    public record Quality(int score, List<Issue> issues) {
        public Quality {
            issues = List.copyOf(issues);
        }
    }

    /** One finding; {@code column} and {@code count} are null for dataset-wide issues. */
    public record Issue(String severity, String kind, String column, Long count, String detail) {
    }

    /** Pairwise association: Pearson for numeric pairs, Cramer's V for categorical pairs. */
    public record Relationship(String left, String right, String method, double value,
                               Double pValue, long n, String strength) {
    }

    /** Numeric value that differs across the groups of a categorical column. */
    public record GroupDifference(String value, String group, String test, double statistic,
                                  double pValue, double pAdjusted, Double effectSize) {
    }

    /** Inferred time span of the first temporal column. */
    public record TimeSpan(String column, String granularity, String from, String to) {
    }

    /** Templated next question derived from the strongest findings. */
    public record Hypothesis(String id, String question, String test, List<String> columns,
                             String rationale, String priority) {
        public Hypothesis {
            columns = List.copyOf(columns);
        }
    }

    /** Wire shape of the design spec section 5.4. */
    public Map<String, Object> toMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("rows", rows);
        body.put("quality_sampled", qualitySampled);
        body.put("quality_scanned_rows", qualityScannedRows);
        body.put("columns", columns.stream().map(ExploreResult::columnMap).toList());
        Map<String, Object> qualityBody = new LinkedHashMap<>();
        qualityBody.put("score", quality.score());
        qualityBody.put("issues", quality.issues().stream().map(ExploreResult::issueMap).toList());
        body.put("quality", qualityBody);
        body.put("relationships", relationships.stream().map(ExploreResult::relationshipMap).toList());
        body.put("group_differences", groupDifferences.stream().map(ExploreResult::groupDifferenceMap).toList());
        if (time != null) {
            Map<String, Object> timeBody = new LinkedHashMap<>();
            timeBody.put("column", time.column());
            timeBody.put("granularity", time.granularity());
            timeBody.put("from", time.from());
            timeBody.put("to", time.to());
            body.put("time", timeBody);
        }
        body.put("hypotheses", hypotheses.stream().map(ExploreResult::hypothesisMap).toList());
        body.put("recommendations", recommendations);
        return body;
    }

    private static Map<String, Object> columnMap(Column column) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", column.name());
        view.put("type", column.type());
        view.put("missing", column.missing());
        view.put("distinct", column.distinct());
        view.put("distinct_exact", column.distinctExact());
        putIfNotNull(view, "min", column.min());
        putIfNotNull(view, "max", column.max());
        putIfNotNull(view, "mean", column.mean());
        putIfNotNull(view, "stddev", column.stddev());
        putIfNotNull(view, "zeros", column.zeros());
        putIfNotNull(view, "negatives", column.negatives());
        putIfNotNull(view, "outliers", column.outliers());
        return view;
    }

    private static Map<String, Object> issueMap(Issue issue) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("severity", issue.severity());
        view.put("kind", issue.kind());
        putIfNotNull(view, "column", issue.column());
        putIfNotNull(view, "count", issue.count());
        view.put("detail", issue.detail());
        return view;
    }

    private static Map<String, Object> relationshipMap(Relationship relationship) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("left", relationship.left());
        view.put("right", relationship.right());
        view.put("method", relationship.method());
        view.put("value", relationship.value());
        putIfNotNull(view, "p_value", relationship.pValue());
        view.put("n", relationship.n());
        view.put("strength", relationship.strength());
        return view;
    }

    private static Map<String, Object> groupDifferenceMap(GroupDifference difference) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("value", difference.value());
        view.put("group", difference.group());
        view.put("test", difference.test());
        view.put("statistic", difference.statistic());
        view.put("p_value", difference.pValue());
        view.put("p_adjusted", difference.pAdjusted());
        putIfNotNull(view, "effect_size", difference.effectSize());
        return view;
    }

    private static Map<String, Object> hypothesisMap(Hypothesis hypothesis) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", hypothesis.id());
        view.put("question", hypothesis.question());
        view.put("test", hypothesis.test());
        view.put("columns", hypothesis.columns());
        view.put("rationale", hypothesis.rationale());
        view.put("priority", hypothesis.priority());
        return view;
    }

    private static void putIfNotNull(Map<String, Object> view, String key, Object value) {
        if (value != null) {
            view.put(key, value);
        }
    }
}
