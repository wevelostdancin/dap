package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * dap.text_normalize: Unicode normalization plus the usual text clean-up (case, accents,
 * whitespace, punctuation), keeping the original columns and adding normalized ones.
 *
 * <p>DuckDB 1.5.6 only ships {@code nfc_normalize}, so NFC, case, punctuation, whitespace
 * and trim run as pure SQL. NFD/NFKD/NFKC (and accent stripping) need {@code java.text.
 * Normalizer}: those modes collect the distinct values of the selected columns (bounded by
 * {@link #MAX_DISTINCT}, an error with a hint above it), normalize them in Java and join the
 * mapping back in SQL, so the row count never enters the heap.
 */
public final class TextNormalizeExecutor implements NodeExecutor {
    /** Distinct values collected for the Java normalization path. */
    static final int MAX_DISTINCT = 100_000;
    private static final String NODE = "text_normalize";
    private static final Set<String> MODES = Set.of("none", "nfc", "nfd", "nfkc", "nfkd");
    private static final Set<String> CASES = Set.of("none", "lower", "upper");
    private static final Pattern MARKS = Pattern.compile("\\p{M}");
    private static final Pattern PUNCTUATION = Pattern.compile("\\p{P}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private record Options(List<String> columns, String mode, String textCase,
                           boolean stripAccents, boolean collapseWhitespace, boolean trim,
                           boolean removePunctuation, String suffix, boolean replace) {
        /** True when the transform needs java.text.Normalizer (a bounded distinct mapping). */
        boolean javaSide() {
            return stripAccents || "nfd".equals(mode) || "nfkd".equals(mode) || "nfkc".equals(mode);
        }
    }

    @Override
    public String nodeId() {
        return "dap.text_normalize";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan input = NodeSql.input(inputs);
        Options options = options(context.config(), input.columns());
        return options.javaSide()
                ? javaSide(context, input, options)
                : sqlSide(context, input, options);
    }

    private static Options options(JsonNode config, List<ColumnSpec> columns) {
        JsonNode columnsNode = config.get("columns");
        if (columnsNode == null || !columnsNode.isArray() || columnsNode.isEmpty()) {
            throw new IllegalArgumentException("text_normalize: 'columns' must be a non-empty array "
                    + "of text column names");
        }
        List<String> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : columnsNode) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException("text_normalize: 'columns' must contain non-empty strings");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                continue;
            }
            ColumnSpec column = null;
            for (ColumnSpec candidate : columns) {
                if (candidate.name().equals(name)) {
                    column = candidate;
                    break;
                }
            }
            if (column == null) {
                throw new IllegalArgumentException("text_normalize: unknown column '" + name + "'");
            }
            if (!"Utf8".equals(column.arrowType())) {
                throw new IllegalArgumentException("text_normalize: column '" + name + "' is "
                        + column.arrowType() + ", not a text (Utf8) column");
            }
            selected.add(name);
        }
        String mode = NodeSql.text(config, "mode", "none").toLowerCase(Locale.ROOT);
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("text_normalize: 'mode' must be one of "
                    + new java.util.TreeSet<>(MODES) + ", got '" + mode + "'");
        }
        String textCase = NodeSql.text(config, "case", "none").toLowerCase(Locale.ROOT);
        if (!CASES.contains(textCase)) {
            throw new IllegalArgumentException("text_normalize: 'case' must be none, lower or upper, got '"
                    + textCase + "'");
        }
        String suffix = NodeSql.text(config, "suffix", "_normalized");
        if (suffix.isEmpty() && !NodeSql.bool(config, "replace", false)) {
            throw new IllegalArgumentException("text_normalize: 'suffix' must not be empty "
                    + "unless 'replace' is true");
        }
        return new Options(List.copyOf(selected), mode, textCase,
                NodeSql.bool(config, "strip_accents", false),
                NodeSql.bool(config, "collapse_whitespace", false),
                NodeSql.bool(config, "trim", false),
                NodeSql.bool(config, "remove_punctuation", false),
                suffix,
                NodeSql.bool(config, "replace", false));
    }

    /** Everything DuckDB can do in SQL: NFC, case, punctuation, whitespace and trim. */
    private static LazyPlan sqlSide(NodeContext context, LazyPlan input, Options options) {
        List<ColumnSpec> output = new ArrayList<>();
        StringJoiner projection = new StringJoiner(", ");
        for (ColumnSpec column : input.columns()) {
            if (!options.columns().contains(column.name())) {
                projection.add("i." + NodeSql.quote(column.name()));
                output.add(column);
                continue;
            }
            if (!options.replace()) {
                projection.add("i." + NodeSql.quote(column.name()));
                output.add(column);
            }
            String expression = "i." + NodeSql.quote(column.name());
            if ("nfc".equals(options.mode())) {
                expression = "nfc_normalize(" + expression + ")";
            }
            if ("lower".equals(options.textCase())) {
                expression = "lower(" + expression + ")";
            } else if ("upper".equals(options.textCase())) {
                expression = "upper(" + expression + ")";
            }
            if (options.removePunctuation()) {
                expression = "regexp_replace(" + expression + ", '[[:punct:]]', '', 'g')";
            }
            if (options.collapseWhitespace()) {
                expression = "regexp_replace(" + expression + ", '\\s+', ' ', 'g')";
            }
            if (options.trim()) {
                expression = "trim(" + expression + ")";
            }
            projection.add(expression + " AS " + NodeSql.quote(outputName(column, options)));
            output.add(ColumnSpec.of(outputName(column, options), "Utf8"));
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS i";
        return new LazyPlan(context.db(), sql, output);
    }

    /** Java normalization over the distinct values of the selected columns. */
    private static LazyPlan javaSide(NodeContext context, LazyPlan input, Options options)
            throws SQLException {
        List<ColumnSpec> output = new ArrayList<>();
        StringJoiner projection = new StringJoiner(", ");
        StringJoiner joins = new StringJoiner(" ");
        int alias = 0;
        for (ColumnSpec column : input.columns()) {
            if (!options.columns().contains(column.name())) {
                projection.add("i." + NodeSql.quote(column.name()));
                output.add(column);
                continue;
            }
            if (!options.replace()) {
                projection.add("i." + NodeSql.quote(column.name()));
                output.add(column);
            }
            String mappingTable = mappingTable(context, input, column.name(), options, alias);
            String joinAlias = "m" + alias++;
            joins.add(" LEFT JOIN " + DuckDbSession.quote(mappingTable) + " AS " + joinAlias
                    + " ON i." + NodeSql.quote(column.name()) + " = " + joinAlias + ".src");
            String expression = "COALESCE(" + joinAlias + ".dst, i."
                    + NodeSql.quote(column.name()) + ")";
            projection.add(expression + " AS " + NodeSql.quote(outputName(column, options)));
            output.add(ColumnSpec.of(outputName(column, options), "Utf8"));
        }
        String sql = "SELECT " + projection + " FROM " + input.asTable() + " AS i" + joins;
        return new LazyPlan(context.db(), sql, output);
    }

    /**
     * Collect the distinct values of one text column, normalize them in Java and load the
     * (src, dst) pairs into a session table. Only the distinct values, never the rows.
     */
    private static String mappingTable(NodeContext context, LazyPlan input, String column,
                                       Options options, int columnIndex) throws SQLException {
        DuckDbSession db = context.db();
        List<Object[]> pairs = new ArrayList<>();
        String quoted = NodeSql.quote(column);
        String query = "SELECT DISTINCT " + quoted + " FROM " + input.asTable()
                + " WHERE " + quoted + " IS NOT NULL";
        try (Connection connection = db.newConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                String value = result.getString(1);
                pairs.add(new Object[]{value, normalize(value, options)});
                if (pairs.size() > MAX_DISTINCT) {
                    throw new IllegalArgumentException("text_normalize: column '" + column
                            + "' has more than " + MAX_DISTINCT + " distinct values; the Unicode "
                            + "normalization runs in Java over distinct values — filter the table "
                            + "or normalize fewer columns first");
                }
            }
        }
        try (ArrowTable table = ArrowTable.of(
                List.of(ColumnSpec.of("src", "Utf8"), ColumnSpec.of("dst", "Utf8")), pairs)) {
            // Index, not column name: two columns can sanitize to the same identifier.
            return db.load(table, "textnorm", context.workflowId(), context.nodeId(),
                    String.valueOf(columnIndex));
        } catch (SQLException failure) {
            throw new SQLException("text_normalize: cannot load the normalization mapping: "
                    + failure.getMessage(), failure);
        }
    }

    private static String outputName(ColumnSpec column, Options options) {
        return options.replace() ? column.name() : column.name() + options.suffix();
    }

    /** The Java transform: normalization form, accents, case, punctuation, whitespace, trim. */
    static String normalize(String value, Options options) {
        String normalized = value;
        Normalizer.Form form = switch (options.mode()) {
            case "nfc" -> Normalizer.Form.NFC;
            case "nfd" -> Normalizer.Form.NFD;
            case "nfkc" -> Normalizer.Form.NFKC;
            case "nfkd" -> Normalizer.Form.NFKD;
            default -> null;
        };
        if (form != null) {
            normalized = Normalizer.normalize(normalized, form);
        }
        if (options.stripAccents()) {
            // Marks only exist after canonical decomposition; recompose when the mode asked for it.
            normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD);
            normalized = MARKS.matcher(normalized).replaceAll("");
            if (form == Normalizer.Form.NFC || form == Normalizer.Form.NFKC) {
                normalized = Normalizer.normalize(normalized, form);
            }
        }
        if ("lower".equals(options.textCase())) {
            normalized = normalized.toLowerCase(Locale.ROOT);
        } else if ("upper".equals(options.textCase())) {
            normalized = normalized.toUpperCase(Locale.ROOT);
        }
        if (options.removePunctuation()) {
            normalized = PUNCTUATION.matcher(normalized).replaceAll("");
        }
        if (options.collapseWhitespace()) {
            normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        }
        if (options.trim()) {
            normalized = normalized.strip();
        }
        return normalized;
    }
}
