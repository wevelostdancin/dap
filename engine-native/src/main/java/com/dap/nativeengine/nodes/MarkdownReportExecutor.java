package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.dap.nativeengine.report.ReportDocument;
import com.dap.nativeengine.report.ReportWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * dap.markdown_report: writes a deterministic report and passes input port 0 through.
 * The node has eight input ports; every section selects the table it reads with
 * {@code sections[].input} (0..7, default 0). Sections are rendered in config order:
 * {@code table} (rows), {@code profile} (dap.describe statistics), {@code text} (a literal
 * paragraph), {@code metrics} (row/column counters and per-column aggregates), {@code image}
 * (a raster file) and {@code page_break}. The resolved sections become a
 * {@link ReportDocument}; {@link ReportWriter} renders it in the configured format.
 */
public final class MarkdownReportExecutor implements NodeExecutor {
    private static final String NODE = "markdown_report";
    private static final int MAX_INPUTS = 8;
    private static final int DEFAULT_TABLE_LIMIT = 100;
    private static final int MAX_TABLE_LIMIT = 10_000;
    private static final int DEFAULT_PROFILE_LIMIT = 1_000;
    private static final int MAX_IMAGE_WIDTH = 10_000;
    private static final Set<String> KINDS =
            Set.of("table", "profile", "text", "metrics", "image", "page_break");
    /** Per-column numeric metrics: SQL aggregate and its report label. */
    private static final List<String[]> NUMERIC_METRICS = List.of(
            new String[]{"AVG", "mean"},
            new String[]{"MIN", "min"},
            new String[]{"MAX", "max"});

    @Override
    public String nodeId() {
        return "dap.markdown_report";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) throws Exception {
        LazyPlan passThrough = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        ReportDocument document = document(context, inputs, config);

        byte[] content = ReportWriter.write(document);
        IoNodeSupport.writeAtomically(IoNodeSupport.targetFile(file, NODE), content, NODE);
        return passThrough;
    }

    // ---------------------------------------------------------------- document

    /** One parsed section before its data is resolved from the selected input. */
    private record Spec(String kind, String title, List<String> columns, int limit, String text,
                        int input, String path, String alt, int width) {
    }

    private static ReportDocument document(NodeContext context, List<LazyPlan> inputs, JsonNode config)
            throws Exception {
        ReportDocument.Format format = enumSetting(config, "format", ReportDocument.Format.class,
                ReportDocument.Format.MD);
        String title = NodeSql.text(config, "title", null);
        String subtitle = NodeSql.text(config, "subtitle", null);
        String author = NodeSql.text(config, "author", null);
        boolean toc = NodeSql.bool(config, "toc", false);
        ReportDocument.Theme theme = enumSetting(config, "theme", ReportDocument.Theme.class,
                ReportDocument.Theme.DEFAULT);
        ReportDocument.PageSize pageSize = enumSetting(config, "page_size",
                ReportDocument.PageSize.class, null);
        ReportDocument.Orientation orientation = enumSetting(config, "orientation",
                ReportDocument.Orientation.class, null);
        List<Spec> specs = specs(config, inputs);
        Map<Integer, TableData> previews = previews(context, inputs, specs);
        Map<Integer, Long> totals = new HashMap<>();
        List<ReportDocument.Section> sections = new ArrayList<>();
        for (Spec spec : specs) {
            LazyPlan input = inputs.get(spec.input());
            sections.add(switch (spec.kind()) {
                case "table" -> table(spec, input, previews.get(spec.input()));
                case "profile" -> profile(context, spec, input);
                case "metrics" -> metrics(context, spec, input,
                        totalRows(context, input, spec.input(), previews, totals));
                case "text" -> new ReportDocument.Section.Text(spec.title(), spec.text());
                case "image" -> image(spec);
                default -> new ReportDocument.Section.PageBreak(spec.title());
            });
        }
        return new ReportDocument(format, title, subtitle, author, toc, theme, pageSize,
                orientation, sections);
    }

    /** A textual config field that names one constant of an enum, case-insensitively. */
    private static <E extends Enum<E>> E enumSetting(JsonNode config, String field, Class<E> type,
                                                     E fallback) {
        JsonNode node = config.get(field);
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(NODE + ": '" + field + "' must be a string");
        }
        String value = node.textValue();
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        StringJoiner expected = new StringJoiner(", ");
        for (E constant : type.getEnumConstants()) {
            expected.add(constant.name().toLowerCase(java.util.Locale.ROOT));
        }
        throw new IllegalArgumentException(NODE + ": unknown " + field + " '" + value
                + "', expected one of " + expected);
    }

    // ---------------------------------------------------------------- sections

    private static List<Spec> specs(JsonNode config, List<LazyPlan> inputs) {
        JsonNode node = config.get("sections");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(NODE + ": 'sections' must be a non-empty array");
        }
        List<Spec> specs = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(NODE + ": every section must be an object");
            }
            String kind = NodeSql.requiredText(item, "kind");
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException(NODE + ": unknown section kind '" + kind
                        + "', expected one of table, profile, text, metrics, image, page_break");
            }
            String title = NodeSql.text(item, "title", null);
            int input = input(item, inputs);
            List<String> selected = selectedColumns(item, inputs.get(input).columns());
            int limit = limit(item, kind.equals("table") ? DEFAULT_TABLE_LIMIT : DEFAULT_PROFILE_LIMIT);
            String text = null;
            String path = null;
            String alt = "";
            int width = 0;
            if (kind.equals("text")) {
                text = NodeSql.requiredText(item, "text");
            }
            if (kind.equals("image")) {
                path = NodeSql.requiredText(item, "path");
                alt = NodeSql.text(item, "alt", "");
                width = width(item);
            }
            specs.add(new Spec(kind, title, selected, limit, text, input, path, alt, width));
        }
        return specs;
    }

    /** The input port a section reads; it must be within 0..7 and connected. */
    private static int input(JsonNode section, List<LazyPlan> inputs) {
        JsonNode node = section.get("input");
        int index = 0;
        if (node != null && !node.isNull()) {
            if (!node.isIntegralNumber()) {
                throw new IllegalArgumentException(NODE + ": section 'input' must be an integer");
            }
            long value = node.longValue();
            if (value < 0 || value >= MAX_INPUTS) {
                throw new IllegalArgumentException(NODE + ": section 'input' must be between 0 and "
                        + (MAX_INPUTS - 1) + ", got " + value);
            }
            index = (int) value;
        }
        if (index >= inputs.size() || inputs.get(index) == null) {
            throw new IllegalArgumentException(NODE + ": section input " + index
                    + " is not connected (" + inputs.size() + " input port(s) connected)");
        }
        return index;
    }

    private static List<String> selectedColumns(JsonNode section, List<ColumnSpec> columns) {
        JsonNode node = section.get("columns");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(NODE + ": section 'columns' must be an array of column names");
        }
        List<String> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new IllegalArgumentException(NODE + ": section 'columns' must contain non-empty column names");
            }
            String name = item.textValue();
            if (!seen.add(name)) {
                throw new IllegalArgumentException(NODE + ": duplicate column '" + name + "' in a section");
            }
            NodeSql.column(columns, name);
            selected.add(name);
        }
        return selected;
    }

    private static int limit(JsonNode section, int fallback) {
        JsonNode node = section.get("limit");
        if (node == null || node.isNull()) {
            return fallback;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException(NODE + ": section 'limit' must be an integer");
        }
        long limit = node.longValue();
        if (limit < 1 || limit > MAX_TABLE_LIMIT) {
            throw new IllegalArgumentException(NODE + ": section 'limit' must be between 1 and "
                    + MAX_TABLE_LIMIT + ", got " + limit);
        }
        return (int) limit;
    }

    private static int width(JsonNode section) {
        JsonNode node = section.get("width");
        if (node == null || node.isNull()) {
            return 0;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException(NODE + ": section 'width' must be an integer");
        }
        long width = node.longValue();
        if (width < 1 || width > MAX_IMAGE_WIDTH) {
            throw new IllegalArgumentException(NODE + ": section 'width' must be between 1 and "
                    + MAX_IMAGE_WIDTH + ", got " + width);
        }
        return (int) width;
    }

    /** One preview per input that has table sections; the widest limit bounds it. */
    private static Map<Integer, TableData> previews(NodeContext context, List<LazyPlan> inputs,
                                                    List<Spec> specs) throws Exception {
        Map<Integer, Integer> limits = new TreeMap<>();
        for (Spec spec : specs) {
            if (spec.kind().equals("table")) {
                limits.merge(spec.input(), spec.limit(), Math::max);
            }
        }
        Map<Integer, TableData> previews = new TreeMap<>();
        for (Map.Entry<Integer, Integer> entry : limits.entrySet()) {
            previews.put(entry.getKey(), inputs.get(entry.getKey())
                    .preview(entry.getValue(), context.workflowId(), context.nodeId()));
        }
        return previews;
    }

    private static long totalRows(NodeContext context, LazyPlan input, int index,
                                  Map<Integer, TableData> previews, Map<Integer, Long> totals)
            throws Exception {
        Long cached = totals.get(index);
        if (cached != null) {
            return cached;
        }
        TableData preview = previews.get(index);
        long rows = preview != null
                ? preview.rows()
                : input.preview(1, context.workflowId(), context.nodeId()).rows();
        totals.put(index, rows);
        return rows;
    }

    // ---------------------------------------------------------------- renderers

    private static ReportDocument.Section.Table table(Spec spec, LazyPlan input, TableData rows) {
        List<String> selected = spec.columns().isEmpty()
                ? input.columns().stream().map(ColumnSpec::name).toList()
                : spec.columns();
        List<Integer> indexes = new ArrayList<>();
        for (String name : selected) {
            indexes.add(indexOf(input.columns(), name));
        }
        List<List<String>> data = new ArrayList<>();
        int printed = 0;
        for (List<String> row : rows.data()) {
            if (printed++ >= spec.limit()) {
                break;
            }
            List<String> cells = new ArrayList<>(indexes.size());
            for (int index : indexes) {
                cells.add(index < row.size() ? row.get(index) : null);
            }
            data.add(cells);
        }
        return new ReportDocument.Section.Table(spec.title(), selected, data);
    }

    private static ReportDocument.Section.Table profile(NodeContext context, Spec spec, LazyPlan input)
            throws Exception {
        ObjectNode describeConfig = Json.object();
        ArrayNode selected = describeConfig.putArray("columns");
        spec.columns().forEach(selected::add);
        NodeContext describeContext = new NodeContext(context.workflowId(), context.nodeId(),
                context.sessionId(), describeConfig, context.db(), context.jobConnection());
        LazyPlan profile = new DescribeExecutor().execute(describeContext, List.of(input));
        TableData data = profile.preview(spec.limit(), context.workflowId(), context.nodeId());
        List<String> headers = data.columns().stream().map(ColumnSpec::name).toList();
        return new ReportDocument.Section.Table(spec.title(), headers, data.data());
    }

    private static ReportDocument.Section.Table metrics(NodeContext context, Spec spec, LazyPlan input,
                                                long totalRows) throws Exception {
        List<ColumnSpec> metrics = new ArrayList<>();
        StringJoiner sql = new StringJoiner(", ");
        sql.add("CAST(COUNT(*) AS VARCHAR) AS \"m0\"");
        metrics.add(ColumnSpec.of("m0", "Utf8"));
        List<String> values = new ArrayList<>();
        int ordinal = 1;
        for (String name : spec.columns()) {
            ColumnSpec column = NodeSql.column(input.columns(), name);
            String quoted = NodeSql.quote(name);
            values.add(name + ".count");
            sql.add("CAST(COUNT(" + quoted + ") AS VARCHAR) AS \"m" + ordinal + "\"");
            metrics.add(ColumnSpec.of("m" + ordinal++, "Utf8"));
            values.add(name + ".missing");
            sql.add("CAST(COUNT(*) - COUNT(" + quoted + ") AS VARCHAR) AS \"m" + ordinal + "\"");
            metrics.add(ColumnSpec.of("m" + ordinal++, "Utf8"));
            if (column.numeric()) {
                for (String[] aggregate : NUMERIC_METRICS) {
                    values.add(name + "." + aggregate[1]);
                    sql.add("CAST(" + aggregate[0] + "(" + quoted + ") AS VARCHAR) AS \"m" + ordinal + "\"");
                    metrics.add(ColumnSpec.of("m" + ordinal++, "Utf8"));
                }
            }
        }
        LazyPlan plan = new LazyPlan(context.db(),
                "SELECT " + sql + " FROM " + input.asTable() + " AS dap_report", metrics);
        TableData data = plan.preview(1, context.workflowId(), context.nodeId());
        List<String> row = data.data().isEmpty() ? List.of() : data.data().get(0);
        List<List<String>> table = new ArrayList<>();
        table.add(List.of("rows", Long.toString(totalRows)));
        table.add(List.of("columns", Integer.toString(input.columns().size())));
        for (int index = 0; index < values.size(); index++) {
            table.add(List.of(values.get(index), cell(row, index + 1)));
        }
        return new ReportDocument.Section.Table(spec.title(), List.of("metric", "value"), table);
    }

    private static ReportDocument.Section.Image image(Spec spec) {
        Path path = Path.of(spec.path()).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(NODE + ": image not found: " + path);
        }
        try {
            return new ReportDocument.Section.Image(spec.title(), spec.path(), spec.alt(), spec.width(),
                    Files.readAllBytes(path));
        } catch (IOException failure) {
            throw new IllegalArgumentException(NODE + ": cannot read image " + path + ": "
                    + failure.getMessage(), failure);
        }
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() ? row.get(index) : null;
    }

    private static int indexOf(List<ColumnSpec> columns, String name) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException(NODE + ": unknown column '" + name + "'");
    }
}
