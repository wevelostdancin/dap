package com.dap.nativeengine.report;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Format-neutral report model: an optional title, document format and an ordered list of
 * sections whose data is already materialized. The node executor resolves its input tables
 * into this model once and hands it to {@link ReportWriter}; every writer renders the same
 * document, so multi-input selection and section validation live in one place.
 */
public record ReportDocument(Format format, String title, List<Section> sections) {
    public ReportDocument {
        Objects.requireNonNull(format, "format");
        sections = List.copyOf(sections);
    }

    /** Output format; the value of the node's {@code format} setting. */
    public enum Format {
        MD;

        public static Format from(String value) {
            for (Format format : values()) {
                if (format.name().equalsIgnoreCase(value)) {
                    return format;
                }
            }
            StringJoiner expected = new StringJoiner(", ");
            for (Format format : values()) {
                expected.add(format.name().toLowerCase(Locale.ROOT));
            }
            throw new IllegalArgumentException("unknown format '" + value + "', expected one of "
                    + expected);
        }
    }

    /** One rendered section; {@code title} is an optional level-2 heading. */
    public sealed interface Section permits Section.Table, Section.Text, Section.Image, Section.PageBreak {
        String title();

        /** A table section: column headers plus already-selected, unescaped cells (null = NULL). */
        record Table(String title, List<String> columns, List<List<String>> rows)
                implements Section {
            public Table {
                columns = List.copyOf(columns);
                rows = List.copyOf(rows);
            }
        }

        /** A literal paragraph section. */
        record Text(String title, String text) implements Section {
        }

        /**
         * An image section: the path as configured, alt text, optional width in pixels
         * (0 = natural/format default) and the file bytes read once by the executor.
         */
        record Image(String title, String path, String alt, int width, byte[] data)
                implements Section {
        }

        /** An explicit page break; Markdown renders a horizontal rule, PPTX skips it. */
        record PageBreak(String title) implements Section {
        }
    }
}
