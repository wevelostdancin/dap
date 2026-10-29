package com.dap.nativeengine.csv;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.TypeMap;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns RFC 4180 CSV text into an Arrow table. NULL semantics follow the DAP
 * rule: an empty unquoted field is NULL, a quoted empty field is the empty
 * string unless {@code emptyStringIsNull} is set.
 */
public final class CsvTableReader {
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    /** Parsed node configuration. */
    public record Options(char delimiter, boolean header, List<ColumnSpec> columns, boolean emptyStringIsNull) {
        public Options {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    private CsvTableReader() {
    }

    public static ArrowTable read(Path file, Options options) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read CSV file " + file + ": " + failure.getMessage(), failure);
        }
        return parse(content, options);
    }

    public static ArrowTable parse(String content, Options options) {
        List<List<CsvParser.Field>> rows = CsvParser.parse(content, options.delimiter());
        boolean hasHeader = options.header() && !rows.isEmpty();
        List<String> header = hasHeader
                ? rows.get(0).stream().map(CsvParser.Field::text).toList()
                : null;
        int firstDataRow = hasHeader ? 1 : 0;

        List<String> names;
        List<String> types;
        int[] sourceIndex;
        int rowWidth;

        if (!options.columns().isEmpty()) {
            names = new ArrayList<>(options.columns().size());
            types = new ArrayList<>(options.columns().size());
            for (ColumnSpec column : options.columns()) {
                if (!TypeMap.isSupported(column.arrowType())) {
                    throw new IllegalArgumentException("unsupported column type '" + column.arrowType()
                            + "' for column '" + column.name() + "'");
                }
                names.add(column.name());
                types.add(column.arrowType());
            }
            sourceIndex = new int[names.size()];
            if (hasHeader) {
                for (int i = 0; i < names.size(); i++) {
                    int position = header.indexOf(names.get(i));
                    if (position < 0) {
                        throw new IllegalArgumentException("CSV header has no column '" + names.get(i)
                                + "'; header is " + header);
                    }
                    sourceIndex[i] = position;
                }
                rowWidth = header.size();
            } else {
                for (int i = 0; i < names.size(); i++) {
                    sourceIndex[i] = i;
                }
                rowWidth = names.size();
            }
        } else {
            if (hasHeader) {
                names = new ArrayList<>(header.size());
                for (int i = 0; i < header.size(); i++) {
                    String name = header.get(i);
                    if (name.isBlank()) {
                        throw new IllegalArgumentException("CSV header column " + (i + 1) + " has no name");
                    }
                    names.add(name);
                }
                rowWidth = header.size();
            } else {
                if (rows.isEmpty()) {
                    throw new IllegalArgumentException("CSV file is empty and no columns are configured");
                }
                rowWidth = rows.get(0).size();
                names = new ArrayList<>(rowWidth);
                for (int i = 0; i < rowWidth; i++) {
                    names.add("column" + (i + 1));
                }
            }
            types = null;
            sourceIndex = new int[names.size()];
            for (int i = 0; i < names.size(); i++) {
                sourceIndex[i] = i;
            }
        }

        validateRowWidths(rows, firstDataRow, rowWidth);

        List<ColumnSpec> columns = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String type = types != null ? types.get(i) : inferType(rows, firstDataRow, sourceIndex[i], options);
            columns.add(ColumnSpec.of(names.get(i), type));
        }

        List<Object[]> data = new ArrayList<>(Math.max(0, rows.size() - firstDataRow));
        for (int row = firstDataRow; row < rows.size(); row++) {
            List<CsvParser.Field> fields = rows.get(row);
            Object[] values = new Object[columns.size()];
            for (int column = 0; column < columns.size(); column++) {
                values[column] = convert(fields.get(sourceIndex[column]), columns.get(column),
                        row - firstDataRow + 1, options);
            }
            data.add(values);
        }
        return ArrowTable.of(columns, data);
    }

    private static void validateRowWidths(List<List<CsvParser.Field>> rows, int firstDataRow, int expected) {
        for (int row = firstDataRow; row < rows.size(); row++) {
            int actual = rows.get(row).size();
            if (actual != expected) {
                throw new IllegalArgumentException("CSV data row " + (row - firstDataRow + 1) + " has "
                        + actual + " fields, expected " + expected);
            }
        }
    }

    private static String inferType(List<List<CsvParser.Field>> rows, int firstDataRow, int sourceIndex,
                                    Options options) {
        boolean any = false;
        boolean integer = true;
        boolean decimal = true;
        boolean bool = true;
        for (int row = firstDataRow; row < rows.size(); row++) {
            CsvParser.Field field = rows.get(row).get(sourceIndex);
            if (isNull(field, options) || field.text().isEmpty()) {
                continue;
            }
            any = true;
            String text = field.text().trim();
            integer &= INTEGER.matcher(text).matches();
            decimal &= DECIMAL.matcher(text).matches();
            bool &= "true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text);
        }
        if (!any) {
            return "Utf8";
        }
        if (integer) {
            return "Int64";
        }
        if (decimal) {
            return "Float64";
        }
        if (bool) {
            return "Boolean";
        }
        return "Utf8";
    }

    private static boolean isNull(CsvParser.Field field, Options options) {
        if (!field.text().isEmpty()) {
            return false;
        }
        if (!field.quoted()) {
            return true;
        }
        return options.emptyStringIsNull();
    }

    private static Object convert(CsvParser.Field field, ColumnSpec column, int dataRow, Options options) {
        if (isNull(field, options)) {
            return null;
        }
        String text = field.text();
        String type = column.arrowType();
        try {
            if ("Int64".equals(type)) {
                return Long.parseLong(text.trim());
            }
            if ("Float64".equals(type)) {
                return Double.parseDouble(text.trim());
            }
            if ("Utf8".equals(type)) {
                return text;
            }
            if ("Boolean".equals(type)) {
                return parseBoolean(text);
            }
            if ("Date32".equals(type)) {
                return LocalDate.parse(text.trim());
            }
            if ("Time64".equals(type)) {
                return LocalTime.parse(text.trim());
            }
            if ("Timestamp".equals(type)) {
                return LocalDateTime.parse(text.trim().replace(' ', 'T'));
            }
            if (type.startsWith("Decimal128")) {
                return new BigDecimal(text.trim());
            }
            throw new IllegalArgumentException("unsupported column type '" + type + "' for column '" + column.name() + "'");
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("cannot parse value '" + text + "' as " + type + " in column '"
                    + column.name() + "' (data row " + dataRow + ")", failure);
        }
    }

    private static Boolean parseBoolean(String text) {
        String value = text.trim();
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("expected true or false");
    }
}
