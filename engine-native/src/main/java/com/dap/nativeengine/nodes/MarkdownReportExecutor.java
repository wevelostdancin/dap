package com.dap.nativeengine.nodes;

import com.dap.nativeengine.api.Json;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * dap.markdown_report: writes a deterministic Markdown report and passes the input plan
 * through. Sections are rendered in config order: {@code table} (input rows), {@code profile}
 * (dap.describe statistics), {@code text} (a literal paragraph) and {@code metrics}
 * (row/column counters and per-column aggregates). Table cells escape pipes, newlines and
 * HTML metacharacters so a value never breaks the document; NULL renders as an empty cell.
 */
public final class MarkdownReportExecutor implements NodeExecutor {
    private static final String NODE = "markdown_report";
    private static final int DEFAULT_TABLE_LIMIT = 100;
    private static final int MAX_TABLE_LIMIT = 10_000;
    private static final int DEFAULT_PROFILE_LIMIT = 1_000;
    private static final Set<String> KINDS = Set.of("table", "profile", "text", "metrics");
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
        LazyPlan input = NodeSql.input(inputs);
        JsonNode config = context.config();
        String file = IoNodeSupport.requiredFile(config);
        String title = NodeSql.text(config, "title", null);
        List<Section> sections = sections(config, input.columns());

        TableData rows = tableRows(context, input, sections);
        long totalRows = rows != null ? rows.rows() : -1L;
        if (totalRows < 0 && hasKind(sections, "metrics")) {
            totalRows = input.preview(1, context.workflowId(), context.nodeId()).rows();
        }

        StringBuilder document = new StringBuilder();
        if (title != null && !title.isBlank()) {
            document.append("# ").append(singleLine(title));
        }
        for (Section section : sections) {
            if (document.length() > 0) {
                document.append("\n\n");
            }
            if (section.title() != null && !section.title().isBlank()) {
                document.append("## ").append(singleLine(section.title())).append('\n').append('\n');
            }
            switch (section.kind()) {
                case "table" -> appendTable(document, input.columns(), section, rows);
                case "profile" -> appendProfile(context, input, document, section);
                case "text" -> appendText(document, section);
                default -> appendMetrics(context, input, document, section, totalRows);
            }
        }
        document.append('\n');

        byte[] content = document.toString().getBytes(StandardCharsets.UTF_8);
        IoNodeSupport.writeAtomically(IoNodeSupport.targetFile(file, NODE), content, NODE);
        return input;
    }

    // ---------------------------------------------------------------- sections

    private record Section(String kind, String title, List<String> columns, int limit, String text) {
    }

    private static List<Section> sections(JsonNode config, List<ColumnSpec> columns) {
        JsonNode node = config.get("sections");
        if (node == null || node.isNull() || !node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(NODE + ": 'sections' must be a non-empty array");
        }
        List<Section> sections = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(NODE + ": every section must be an object");
            }
            String kind = NodeSql.requiredText(item, "kind");
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException(NODE + ": unknown section kind '" + kind
                        + "', expected one of table, profile, text, metrics");
            }
            String title = NodeSql.text(item, "title", null);
            List<String> selected = selectedColumns(item, columns);
            int limit = limit(item, kind.equals("table") ? DEFAULT_TABLE_LIMIT : DEFAULT_PROFILE_LIMIT);
            String text = null;
            if (kind.equals("text")) {
                text = NodeSql.requiredText(item, "text");
            }
            sections.add(new Section(kind, title, selected, limit, text));
        }
        return sections;
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

    private static boolean hasKind(List<Section> sections, String kind) {
        return sections.stream().anyMatch(section -> section.kind().equals(kind));
    }

    /** One preview for every table section; the widest section limit bounds it. */
    private static TableData tableRows(NodeContext context, LazyPlan input, List<Section> sections)
            throws Exception {
        int limit = 0;
        for (Section section : sections) {
            if (section.kind().equals("table")) {
                limit = Math.max(limit, section.limit());
            }
        }
        if (limit == 0) {
            return null;
        }
        return input.preview(limit, context.workflowId(), context.nodeId());
    }

    // ---------------------------------------------------------------- renderers

    private static void appendTable(StringBuilder document, List<ColumnSpec> columns, Section section,
                                    TableData rows) {
        List<String> selected = section.columns().isEmpty()
                ? columns.stream().map(ColumnSpec::name).toList()
                : section.columns();
        List<Integer> indexes = new ArrayList<>();
        for (String name : selected) {
            indexes.add(indexOf(columns, name));
        }
        StringJoiner header = new StringJoiner(" | ", "| ", " |");
        for (String name : selected) {
            header.add(escapeCell(name));
        }
        document.append(header);
        document.append('\n').append("|");
        for (int index = 0; index < selected.size(); index++) {
            document.append(" --- |");
        }
        int printed = 0;
        for (List<String> row : rows.data()) {
            if (printed++ >= section.limit()) {
                break;
            }
            StringJoiner cells = new StringJoiner(" | ", "| ", " |");
            for (int index : indexes) {
                cells.add(escapeCell(index < row.size() ? row.get(index) : null));
            }
            document.append('\n').append(cells);
        }
    }

    private static void appendProfile(NodeContext context, LazyPlan input, StringBuilder document,
                                      Section section) throws Exception {
        ObjectNode describeConfig = Json.object();
        ArrayNode selected = describeConfig.putArray("columns");
        section.columns().forEach(selected::add);
        NodeContext describeContext = new NodeContext(context.workflowId(), context.nodeId(),
                context.sessionId(), describeConfig, context.db(), context.jobConnection());
        LazyPlan profile = new DescribeExecutor().execute(describeContext, List.of(input));
        TableData data = profile.preview(section.limit(), context.workflowId(), context.nodeId());
        List<String> headers = data.columns().stream().map(ColumnSpec::name).toList();
        appendTable(document, headers, data.data());
    }

    private static void appendText(StringBuilder document, Section section) {
        document.append(normalizeNewlines(section.text()).strip());
    }

    private static void appendMetrics(NodeContext context, LazyPlan input, StringBuilder document,
                                      Section section, long totalRows) throws Exception {
        List<ColumnSpec> metrics = new ArrayList<>();
        StringJoiner sql = new StringJoiner(", ");
        sql.add("CAST(COUNT(*) AS VARCHAR) AS \"m0\"");
        metrics.add(ColumnSpec.of("m0", "Utf8"));
        List<String> values = new ArrayList<>();
        int ordinal = 1;
        for (String name : section.columns()) {
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
        appendTable(document, List.of("metric", "value"), table);
    }

    private static String cell(List<String> row, int index) {
        return index < row.size() ? row.get(index) : null;
    }

    private static void appendTable(StringBuilder document, List<String> headers, List<List<String>> rows) {
        StringJoiner header = new StringJoiner(" | ", "| ", " |");
        for (String name : headers) {
            header.add(escapeCell(name));
        }
        document.append(header);
        document.append('\n').append("|");
        for (int index = 0; index < headers.size(); index++) {
            document.append(" --- |");
        }
        for (List<String> row : rows) {
            StringJoiner cells = new StringJoiner(" | ", "| ", " |");
            for (int index = 0; index < headers.size(); index++) {
                cells.add(escapeCell(index < row.size() ? row.get(index) : null));
            }
            document.append('\n').append(cells);
        }
    }

    private static int indexOf(List<ColumnSpec> columns, String name) {
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).name().equals(name)) {
                return index;
            }
        }
        throw new IllegalArgumentException(NODE + ": unknown column '" + name + "'");
    }

    // ---------------------------------------------------------------- escaping

    /** A table cell: pipes and newlines cannot break the row, HTML cannot break the document. */
    static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        escaped = escaped.replace("|", "\\|");
        return normalizeNewlines(escaped).replace("\n", "<br>");
    }

    private static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** A heading or title: one line, so a newline cannot open a new block. */
    static String singleLine(String value) {
        return normalizeNewlines(value).replace('\n', ' ').strip();
    }
}
