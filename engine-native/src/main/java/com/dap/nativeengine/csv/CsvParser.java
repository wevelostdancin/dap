package com.dap.nativeengine.csv;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180-style CSV tokenizer. The quoted flag is kept per field so that a
 * quoted empty field ({@code ""}) can be told apart from an empty unquoted
 * field, which is SQL NULL.
 */
public final class CsvParser {
    /** One raw field: text plus whether it was written quoted. */
    public record Field(String text, boolean quoted) {
    }

    private CsvParser() {
    }

    /**
     * Split CSV text into rows of fields. Accepts CR, LF and CRLF line endings,
     * a leading UTF-8 BOM, doubled quotes inside quoted fields and newlines
     * inside quoted fields.
     */
    public static List<List<Field>> parse(String content, char delimiter) {
        if (content == null) {
            throw new IllegalArgumentException("CSV content is null");
        }
        if (delimiter == '"' || delimiter == '\r' || delimiter == '\n') {
            throw new IllegalArgumentException("invalid CSV delimiter: " + delimiter);
        }
        List<List<Field>> rows = new ArrayList<>();
        List<Field> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean inQuotes = false;
        boolean fieldStarted = false;
        int quoteLine = 0;
        int line = 1;
        int index = content.startsWith("\uFEFF") ? 1 : 0;
        int length = content.length();
        while (index < length) {
            char current = content.charAt(index);
            if (inQuotes) {
                if (current == '"') {
                    if (index + 1 < length && content.charAt(index + 1) == '"') {
                        field.append('"');
                        index += 2;
                        continue;
                    }
                    inQuotes = false;
                    index++;
                    continue;
                }
                if (current == '\n') {
                    line++;
                }
                field.append(current);
                index++;
                continue;
            }
            if (current == delimiter) {
                row.add(new Field(field.toString(), quoted));
                field.setLength(0);
                quoted = false;
                fieldStarted = true;
                index++;
                continue;
            }
            if (current == '\r' || current == '\n') {
                if (current == '\r' && index + 1 < length && content.charAt(index + 1) == '\n') {
                    index++;
                }
                index++;
                if (row.isEmpty() && field.length() == 0 && !quoted && !fieldStarted) {
                    line++;
                    continue;
                }
                row.add(new Field(field.toString(), quoted));
                rows.add(row);
                row = new ArrayList<>();
                field.setLength(0);
                quoted = false;
                fieldStarted = false;
                line++;
                continue;
            }
            if (current == '"') {
                if (field.length() == 0 && !quoted) {
                    inQuotes = true;
                    quoted = true;
                    fieldStarted = true;
                    quoteLine = line;
                    index++;
                    continue;
                }
                field.append('"');
                fieldStarted = true;
                index++;
                continue;
            }
            if (quoted) {
                throw new IllegalArgumentException("unexpected character after closing quote at line " + line);
            }
            field.append(current);
            fieldStarted = true;
            index++;
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quoted field starting at line " + quoteLine);
        }
        if (fieldStarted || field.length() > 0 || quoted || !row.isEmpty()) {
            row.add(new Field(field.toString(), quoted));
            rows.add(row);
        }
        return rows;
    }
}
