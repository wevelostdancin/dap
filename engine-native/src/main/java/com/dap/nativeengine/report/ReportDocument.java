package com.dap.nativeengine.report;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Format-neutral report model: a header, document settings and an ordered list of sections
 * whose data is already materialized. The node executor resolves its input tables into this
 * model once and hands it to {@link ReportWriter}; every writer renders the same document, so
 * multi-input selection and section validation live in one place. A {@code null} page size or
 * orientation means "the format's default" (A4 portrait for HTML and PDF, 16:9 for PPTX).
 */
public record ReportDocument(Format format, String title, String subtitle, String author,
                             boolean toc, Theme theme, PageSize pageSize,
                             Orientation orientation, List<Section> sections) {
    public ReportDocument {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(theme, "theme");
        sections = List.copyOf(sections);
    }

    /** Output format; the value of the node's {@code format} setting. */
    public enum Format {
        MD,
        HTML,
        PDF,
        PPTX
    }

    /** Colour palette; HTML uses the full palette, PDF and PPTX only its accent colour. */
    public enum Theme {
        DEFAULT(0x1F4E79),
        LIGHT(0x555555),
        DARK(0x7FB3FF),
        MONO(0x000000);

        private final int accent;

        Theme(int accent) {
            this.accent = accent;
        }

        /** Accent colour as 0xRRGGBB. */
        public int accent() {
            return accent;
        }
    }

    /** Paper size for paginated formats. */
    public enum PageSize {
        A4,
        LETTER;

        /** CSS/PDF name of the paper size. */
        public String css() {
            return this == LETTER ? "letter" : "A4";
        }
    }

    public enum Orientation {
        PORTRAIT,
        LANDSCAPE;

        public String css() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One rendered section; {@code title} is an optional level-2 heading. */
    public sealed interface Section permits Section.Table, Section.Text, Section.Image, Section.PageBreak {
        String title();

        /**
         * A table section: column headers plus already-selected, unescaped cells (null = NULL).
         * {@code maxRows} is the format-specific row cap (PPTX only); null means the default.
         */
        record Table(String title, List<String> columns, List<List<String>> rows, Integer maxRows)
                implements Section {
            public Table(String title, List<String> columns, List<List<String>> rows) {
                this(title, columns, rows, null);
            }

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
            /** Media type from the file signature; fails for formats the writers cannot embed. */
            public String mediaType() {
                if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P'
                        && data[2] == 'N' && data[3] == 'G') {
                    return "image/png";
                }
                if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
                    return "image/jpeg";
                }
                if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                        && data[3] == '8') {
                    return "image/gif";
                }
                throw new IllegalArgumentException("unsupported image format for '" + path
                        + "': expected png, jpeg or gif");
            }

            /** File extension that matches {@link #mediaType()}. */
            public String extension() {
                return switch (mediaType()) {
                    case "image/png" -> "png";
                    case "image/jpeg" -> "jpeg";
                    default -> "gif";
                };
            }
        }

        /** An explicit page break; Markdown renders a horizontal rule, PPTX skips it. */
        record PageBreak(String title) implements Section {
        }
    }
}
