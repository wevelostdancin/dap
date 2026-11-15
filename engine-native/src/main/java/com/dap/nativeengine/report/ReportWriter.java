package com.dap.nativeengine.report;

import com.dap.nativeengine.report.ReportDocument.Section;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

/**
 * Renders a {@link ReportDocument} to bytes. Markdown is rendered here (the node's default
 * and backward-compatible format); other formats are delegated to their writers. Every
 * writer is deterministic: no timestamps, no randomness, fixed iteration order.
 */
public final class ReportWriter {
    private ReportWriter() {
    }

    public static byte[] write(ReportDocument document) {
        return switch (document.format()) {
            case MD -> markdown(document).getBytes(StandardCharsets.UTF_8);
        };
    }

    /** Markdown rendering: byte-identical to the original single-input report output. */
    static String markdown(ReportDocument document) {
        StringBuilder out = new StringBuilder();
        if (document.title() != null && !document.title().isBlank()) {
            out.append("# ").append(singleLine(document.title()));
        }
        for (Section section : document.sections()) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            if (section.title() != null && !section.title().isBlank()) {
                out.append("## ").append(singleLine(section.title())).append('\n').append('\n');
            }
            switch (section) {
                case Section.Table table -> appendTable(out, table.columns(), table.rows());
                case Section.Text text -> out.append(normalizeNewlines(text.text()).strip());
                case Section.Image image -> out.append(image(image));
                case Section.PageBreak ignored -> out.append("---");
            }
        }
        out.append('\n');
        return out.toString();
    }

    private static void appendTable(StringBuilder out, List<String> columns, List<List<String>> rows) {
        StringJoiner header = new StringJoiner(" | ", "| ", " |");
        for (String column : columns) {
            header.add(escapeCell(column));
        }
        out.append(header);
        out.append('\n').append("|");
        for (int index = 0; index < columns.size(); index++) {
            out.append(" --- |");
        }
        for (List<String> row : rows) {
            StringJoiner cells = new StringJoiner(" | ", "| ", " |");
            for (int index = 0; index < columns.size(); index++) {
                cells.add(escapeCell(index < row.size() ? row.get(index) : null));
            }
            out.append('\n').append(cells);
        }
    }

    /** Markdown has no image sizing, so the configured width is used by the other formats. */
    private static String image(Section.Image image) {
        String alt = singleLine(image.alt() == null ? "" : image.alt()).replace("]", "\\]");
        String path = image.path();
        if (path.indexOf(' ') >= 0 || path.indexOf('(') >= 0 || path.indexOf(')') >= 0
                || path.indexOf('<') >= 0) {
            path = "<" + path + ">";
        }
        return "![" + alt + "](" + path + ")";
    }

    /** A table cell: pipes and newlines cannot break the row, HTML cannot break the document. */
    static String escapeCell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        escaped = escaped.replace("|", "\\|");
        return normalizeNewlines(escaped).replace("\n", "<br>");
    }

    static String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** A heading or title: one line, so a newline cannot open a new block. */
    static String singleLine(String value) {
        return normalizeNewlines(value).replace('\n', ' ').strip();
    }
}
