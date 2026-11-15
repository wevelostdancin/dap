package com.dap.nativeengine.report;

import com.dap.nativeengine.report.ReportDocument.Section;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Self-contained HTML report: one document with embedded CSS, no external stylesheets,
 * scripts or links. Images are inlined as data URIs, tables become plain HTML tables and a
 * table of contents is generated from section titles when {@code toc} is set. The output has
 * no timestamps or other non-deterministic data.
 */
public final class HtmlWriter {
    private static final String STYLE = """
            :root { --bg: #ffffff; --fg: #1a1a1a; --muted: #5c6773; --accent: #1f4e79;
                    --border: #d7dee8; --stripe: #f5f7fa; }
            body.theme-light { --bg: #ffffff; --fg: #333333; --muted: #666666;
                    --accent: #555555; --border: #dddddd; --stripe: #f7f7f7; }
            body.theme-dark { --bg: #14181f; --fg: #e6e9ef; --muted: #9aa4b2;
                    --accent: #7fb3ff; --border: #2c3542; --stripe: #1b2029; }
            body.theme-mono { --bg: #ffffff; --fg: #000000; --muted: #444444;
                    --accent: #000000; --border: #999999; --stripe: #f2f2f2; }
            * { box-sizing: border-box; }
            body { margin: 0; padding: 32px; background: var(--bg); color: var(--fg);
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                    Helvetica, Arial, sans-serif; font-size: 15px; line-height: 1.55; }
            main { max-width: 960px; margin: 0 auto; }
            header { border-bottom: 2px solid var(--accent); margin-bottom: 24px;
                    padding-bottom: 12px; }
            h1 { font-size: 2rem; line-height: 1.2; margin: 0 0 6px; }
            .subtitle { color: var(--muted); font-size: 1.15rem; margin: 0; }
            .author { color: var(--muted); font-size: .9rem; margin: 8px 0 0; }
            nav.toc { margin: 0 0 24px; padding: 16px 20px; border: 1px solid var(--border);
                    border-radius: 6px; }
            nav.toc h2 { font-size: 1rem; margin: 0 0 8px; border: 0; padding: 0; }
            nav.toc ol { margin: 0; padding-left: 20px; }
            nav.toc a { color: var(--accent); text-decoration: none; }
            section { margin: 0 0 28px; }
            h2 { color: var(--accent); font-size: 1.35rem; border-bottom: 1px solid var(--border);
                    padding-bottom: 4px; margin: 24px 0 12px; }
            table { border-collapse: collapse; width: 100%; margin: 12px 0; font-size: .92rem; }
            th, td { border: 1px solid var(--border); padding: 6px 10px; text-align: left;
                    vertical-align: top; }
            thead th { background: var(--accent); color: var(--bg); font-weight: 600; }
            tbody tr:nth-child(even) { background: var(--stripe); }
            img { max-width: 100%; height: auto; border: 1px solid var(--border);
                    border-radius: 4px; }
            p.text { white-space: pre-wrap; margin: 12px 0; }
            .page-break { break-after: page; page-break-after: always; height: 0; }
            @page { size: @PAGE@; margin: 18mm; }
            """;

    public byte[] write(ReportDocument document) {
        return render(document).getBytes(StandardCharsets.UTF_8);
    }

    String render(ReportDocument document) {
        ReportDocument.PageSize pageSize =
                document.pageSize() == null ? ReportDocument.PageSize.A4 : document.pageSize();
        ReportDocument.Orientation orientation = document.orientation() == null
                ? ReportDocument.Orientation.PORTRAIT : document.orientation();
        StringBuilder out = new StringBuilder();
        out.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        out.append("<meta charset=\"utf-8\">\n");
        out.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("<title>").append(escape(document.title() == null ? "Report" : document.title()))
                .append("</title>\n");
        out.append("<style>\n")
                .append(STYLE.replace("@PAGE@", pageSize.css() + " " + orientation.css()))
                .append("</style>\n</head>\n");
        out.append("<body class=\"theme-").append(document.theme().name().toLowerCase())
                .append("\">\n<main>\n");

        if (document.title() != null && !document.title().isBlank()) {
            out.append("<header>\n<h1>").append(escape(document.title())).append("</h1>\n");
            if (document.subtitle() != null && !document.subtitle().isBlank()) {
                out.append("<p class=\"subtitle\">").append(escape(document.subtitle()))
                        .append("</p>\n");
            }
            if (document.author() != null && !document.author().isBlank()) {
                out.append("<p class=\"author\">").append(escape(document.author()))
                        .append("</p>\n");
            }
            out.append("</header>\n");
        }

        if (document.toc()) {
            appendToc(out, document.sections());
        }
        for (int index = 0; index < document.sections().size(); index++) {
            appendSection(out, document.sections().get(index), index + 1);
        }
        out.append("</main>\n</body>\n</html>\n");
        return out.toString();
    }

    private static void appendToc(StringBuilder out, List<Section> sections) {
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            String title = sections.get(index).title();
            if (title != null && !title.isBlank()) {
                entries.add("<li><a href=\"#section-" + (index + 1) + "\">" + escape(title)
                        + "</a></li>");
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        out.append("<nav class=\"toc\">\n<h2>Contents</h2>\n<ol>\n");
        for (String entry : entries) {
            out.append(entry).append('\n');
        }
        out.append("</ol>\n</nav>\n");
    }

    private static void appendSection(StringBuilder out, Section section, int ordinal) {
        out.append("<section id=\"section-").append(ordinal).append("\">\n");
        if (section.title() != null && !section.title().isBlank()) {
            out.append("<h2>").append(escape(section.title())).append("</h2>\n");
        }
        switch (section) {
            case Section.Table table -> appendTable(out, table);
            case Section.Text text -> out.append("<p class=\"text\">")
                    .append(escape(text.text())).append("</p>\n");
            case Section.Image image -> appendImage(out, image);
            case Section.PageBreak ignored -> out.append("<div class=\"page-break\"></div>\n");
        }
        out.append("</section>\n");
    }

    private static void appendTable(StringBuilder out, Section.Table table) {
        out.append("<table>\n<thead><tr>");
        for (String column : table.columns()) {
            out.append("<th>").append(escape(column)).append("</th>");
        }
        out.append("</tr></thead>\n<tbody>\n");
        for (List<String> row : table.rows()) {
            out.append("<tr>");
            for (int index = 0; index < table.columns().size(); index++) {
                String cell = index < row.size() ? row.get(index) : null;
                out.append("<td>").append(cell == null ? "" : escape(cell)).append("</td>");
            }
            out.append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n");
    }

    private static void appendImage(StringBuilder out, Section.Image image) {
        String base64 = Base64.getEncoder().encodeToString(image.data());
        out.append("<img src=\"data:").append(image.mediaType()).append(";base64,")
                .append(base64).append("\" alt=\"").append(escape(image.alt() == null ? "" : image.alt()))
                .append('"');
        if (image.width() > 0) {
            out.append(" width=\"").append(image.width()).append('"');
        }
        out.append(">\n");
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
