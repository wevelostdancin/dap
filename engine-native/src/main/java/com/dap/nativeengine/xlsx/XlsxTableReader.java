package com.dap.nativeengine.xlsx;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.TypeMap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal OOXML .xlsx reader: java.util.zip plus StAX, no third-party Office
 * libraries, over the parts the DAP pipeline needs. Values become an Arrow
 * table; NULL semantics follow the DAP rule (a missing cell is NULL, an empty
 * shared/inline string is a value unless {@code emptyStringIsNull} is set).
 */
public final class XlsxTableReader {
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Set<String> TYPES =
            Set.of("Int64", "Float64", "Utf8", "Boolean", "Date32", "Timestamp");

    /** Parsed node configuration. */
    public record Options(String sheet, boolean header, List<ColumnSpec> columns, boolean emptyStringIsNull) {
        public Options {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    private XlsxTableReader() {
    }

    public static ArrowTable read(Path file, Options options) throws InterruptedException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        validate(options);
        checkInterrupted();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file + ": file does not exist");
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            checkInterrupted();
            if (zip.getEntry("xl/workbook.xml") == null) {
                throw new IllegalArgumentException("not an .xlsx workbook: " + file
                        + " (missing xl/workbook.xml)");
            }
            Workbook workbook = readWorkbook(zip, file);
            Sheet sheet = selectSheet(workbook, options, file);
            Map<String, String> relationships = readRelationships(zip, file);
            String target = relationships.get(sheet.relId());
            if (target == null) {
                throw new IllegalArgumentException("excel_reader: workbook part for sheet '" + sheet.name()
                        + "' is missing in " + file);
            }
            ZipEntry sheetEntry = zip.getEntry(target);
            if (sheetEntry == null) {
                throw new IllegalArgumentException("excel_reader: worksheet part '" + target
                        + "' is missing in " + file);
            }
            List<String> sharedStrings = readSharedStrings(zip);
            Styles styles = readStyles(zip);
            ParsedSheet parsed = readSheet(zip, sheetEntry, sharedStrings, styles, file);
            return build(parsed, sheet.name(), options, file, workbook.date1904());
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file + ": "
                    + failure.getMessage(), failure);
        } catch (XMLStreamException failure) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file + ": "
                    + failure.getMessage(), failure);
        }
    }

    private static void validate(Options options) {
        for (ColumnSpec column : options.columns()) {
            String type = TypeMap.canonical(column.arrowType());
            if (!TYPES.contains(type)) {
                throw new IllegalArgumentException("excel_reader: unsupported type '" + column.arrowType()
                        + "' for column '" + column.name() + "'");
            }
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("excel_reader interrupted");
        }
    }

    private static XMLStreamReader reader(InputStream in) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        return factory.createXMLStreamReader(in);
    }

    private static boolean isTrue(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    // ---------------------------------------------------------------- workbook

    private record Workbook(List<Sheet> sheets, boolean date1904) {
    }

    private record Sheet(String name, String relId) {
    }

    private static Workbook readWorkbook(ZipFile zip, Path file) throws IOException, XMLStreamException {
        List<Sheet> sheets = new ArrayList<>();
        boolean date1904 = false;
        try (InputStream in = zip.getInputStream(zip.getEntry("xl/workbook.xml"))) {
            XMLStreamReader xml = reader(in);
            while (xml.hasNext()) {
                int event = xml.next();
                if (event != XMLStreamReader.START_ELEMENT) {
                    continue;
                }
                if ("workbookPr".equals(xml.getLocalName())) {
                    date1904 = isTrue(xml.getAttributeValue(null, "date1904"));
                } else if ("sheet".equals(xml.getLocalName())) {
                    String name = xml.getAttributeValue(null, "name");
                    String relId = xml.getAttributeValue(REL, "id");
                    if (relId == null) {
                        relId = xml.getAttributeValue(null, "r:id");
                    }
                    if (name == null) {
                        name = "";
                    }
                    if (relId == null || relId.isBlank()) {
                        throw new IllegalArgumentException("excel_reader: sheet '" + name
                                + "' has no relationship id in " + file);
                    }
                    sheets.add(new Sheet(name, relId));
                }
            }
        }
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("excel_reader: workbook has no worksheets: " + file);
        }
        return new Workbook(List.copyOf(sheets), date1904);
    }

    private static Map<String, String> readRelationships(ZipFile zip, Path file)
            throws IOException, XMLStreamException {
        ZipEntry entry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (entry == null) {
            throw new IllegalArgumentException("not an .xlsx workbook: " + file
                    + " (missing xl/_rels/workbook.xml.rels)");
        }
        Map<String, String> targets = new LinkedHashMap<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader xml = reader(in);
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamReader.START_ELEMENT
                        && "Relationship".equals(xml.getLocalName())) {
                    String id = xml.getAttributeValue(null, "Id");
                    String target = xml.getAttributeValue(null, "Target");
                    if (id != null && target != null) {
                        targets.put(id, resolvePart(target));
                    }
                }
            }
        }
        return targets;
    }

    private static String resolvePart(String target) {
        String value = target.replace('\\', '/');
        if (value.startsWith("/")) {
            value = value.substring(1);
        } else {
            value = "xl/" + value;
        }
        return Path.of(value).normalize().toString().replace('\\', '/');
    }

    private static Sheet selectSheet(Workbook workbook, Options options, Path file) {
        String selector = options.sheet();
        if (selector == null || selector.isBlank()) {
            return workbook.sheets().get(0);
        }
        for (Sheet sheet : workbook.sheets()) {
            if (sheet.name().equals(selector)) {
                return sheet;
            }
        }
        int index = -1;
        if (selector.matches("\\d+")) {
            try {
                index = Integer.parseInt(selector);
            } catch (NumberFormatException ignored) {
                index = -1;
            }
        }
        if (index < 0 || index >= workbook.sheets().size()) {
            List<String> names = workbook.sheets().stream().map(Sheet::name).toList();
            throw new IllegalArgumentException("excel_reader: unknown sheet '" + selector + "' in " + file
                    + "; available sheets: " + names);
        }
        return workbook.sheets().get(index);
    }

    // ------------------------------------------------------------ shared strings

    private static List<String> readSharedStrings(ZipFile zip)
            throws IOException, XMLStreamException, InterruptedException {
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader xml = reader(in);
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamReader.START_ELEMENT && "si".equals(xml.getLocalName())) {
                    checkInterrupted();
                    strings.add(readTextContainer(xml, "si"));
                }
            }
        }
        return strings;
    }

    // ------------------------------------------------------------------ styles

    private record Styles(List<Integer> cellXfs, Map<Integer, String> formats) {
        static Styles empty() {
            return new Styles(List.of(), Map.of());
        }

        boolean isDate(int styleIndex) {
            Integer format = formatId(styleIndex);
            if (format == null) {
                return false;
            }
            if ((format >= 14 && format <= 22) || (format >= 45 && format <= 47)) {
                return true;
            }
            String code = formats.get(format);
            return code != null && hasDateToken(stripLiterals(code));
        }

        boolean hasTime(int styleIndex) {
            Integer format = formatId(styleIndex);
            if (format == null) {
                return false;
            }
            if ((format >= 18 && format <= 22) || (format >= 45 && format <= 47)) {
                return true;
            }
            String code = formats.get(format);
            if (code == null) {
                return false;
            }
            String stripped = stripLiterals(code);
            return stripped.indexOf('h') >= 0 || stripped.indexOf('s') >= 0;
        }

        private Integer formatId(int styleIndex) {
            if (styleIndex < 0 || styleIndex >= cellXfs.size()) {
                return null;
            }
            return cellXfs.get(styleIndex);
        }

        private static boolean hasDateToken(String code) {
            for (int i = 0; i < code.length(); i++) {
                if ("ymdhs".indexOf(code.charAt(i)) >= 0) {
                    return true;
                }
            }
            return false;
        }

        private static String stripLiterals(String code) {
            StringBuilder stripped = new StringBuilder();
            boolean quoted = false;
            int bracket = 0;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < code.length() && code.charAt(i + 1) == '"') {
                            i++;
                        } else {
                            quoted = false;
                        }
                    }
                    continue;
                }
                if (c == '"') {
                    quoted = true;
                } else if (c == '[') {
                    bracket++;
                } else if (c == ']') {
                    if (bracket > 0) {
                        bracket--;
                    }
                } else if (bracket > 0) {
                    continue;
                } else if (c == '\\' || c == '_' || c == '*') {
                    i++;
                } else {
                    stripped.append(Character.toLowerCase(c));
                }
            }
            return stripped.toString();
        }
    }

    private static Styles readStyles(ZipFile zip) throws IOException, XMLStreamException {
        ZipEntry entry = zip.getEntry("xl/styles.xml");
        if (entry == null) {
            return Styles.empty();
        }
        List<Integer> cellXfs = new ArrayList<>();
        Map<Integer, String> formats = new HashMap<>();
        boolean inCellXfs = false;
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader xml = reader(in);
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "numFmt" -> {
                            String id = xml.getAttributeValue(null, "numFmtId");
                            String code = xml.getAttributeValue(null, "formatCode");
                            if (id != null && code != null) {
                                formats.put(Integer.parseInt(id.trim()), code);
                            }
                        }
                        case "cellXfs" -> inCellXfs = true;
                        case "xf" -> {
                            if (inCellXfs) {
                                String id = xml.getAttributeValue(null, "numFmtId");
                                cellXfs.add(id == null ? 0 : Integer.parseInt(id.trim()));
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamReader.END_ELEMENT
                        && "cellXfs".equals(xml.getLocalName())) {
                    inCellXfs = false;
                }
            }
        }
        return new Styles(List.copyOf(cellXfs), Map.copyOf(formats));
    }

    // -------------------------------------------------------------------- sheet

    private sealed interface Cell {
        record Text(String value) implements Cell {
        }

        record Number(double value, String raw, boolean dateStyle, boolean timeStyle) implements Cell {
        }

        record Bit(boolean value) implements Cell {
        }

        record IsoDate(LocalDate value) implements Cell {
        }

        record IsoTimestamp(LocalDateTime value) implements Cell {
        }
    }

    private record ParsedSheet(TreeMap<Integer, Map<Integer, Cell>> rows, int maxColumn) {
    }

    private static ParsedSheet readSheet(ZipFile zip, ZipEntry entry, List<String> sharedStrings,
                                         Styles styles, Path file)
            throws IOException, XMLStreamException, InterruptedException {
        TreeMap<Integer, Map<Integer, Cell>> rows = new TreeMap<>();
        int maxColumn = -1;
        int rowNumber = 0;
        int nextColumn = 0;
        int cellColumn = -1;
        int styleIndex = -1;
        String cellType = null;
        String value = null;
        String inline = null;
        Map<Integer, Cell> row = null;
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader xml = reader(in);
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "row" -> {
                            checkInterrupted();
                            String attribute = xml.getAttributeValue(null, "r");
                            rowNumber = attribute == null ? rowNumber + 1 : Integer.parseInt(attribute.trim());
                            row = new HashMap<>();
                            rows.put(rowNumber, row);
                            nextColumn = 0;
                        }
                        case "c" -> {
                            String reference = xml.getAttributeValue(null, "r");
                            cellColumn = columnIndex(reference, nextColumn);
                            nextColumn = cellColumn + 1;
                            cellType = xml.getAttributeValue(null, "t");
                            String style = xml.getAttributeValue(null, "s");
                            styleIndex = style == null ? -1 : Integer.parseInt(style.trim());
                            value = null;
                            inline = null;
                            maxColumn = Math.max(maxColumn, cellColumn);
                        }
                        case "v" -> {
                            if (cellColumn >= 0) {
                                value = xml.getElementText();
                            }
                        }
                        case "is" -> {
                            if (cellColumn >= 0) {
                                inline = readTextContainer(xml, "is");
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event == XMLStreamReader.END_ELEMENT && "c".equals(xml.getLocalName())) {
                    if (row != null && cellColumn >= 0) {
                        Cell cell = toCell(cellType, value, inline, styleIndex, sharedStrings, styles, file);
                        if (cell != null) {
                            row.put(cellColumn, cell);
                        }
                    }
                    cellColumn = -1;
                    cellType = null;
                    styleIndex = -1;
                    value = null;
                    inline = null;
                }
            }
        }
        return new ParsedSheet(rows, maxColumn);
    }

    private static int columnIndex(String reference, int fallback) {
        if (reference == null || reference.isEmpty()) {
            return fallback;
        }
        int column = 0;
        int i = 0;
        while (i < reference.length() && Character.isLetter(reference.charAt(i))) {
            column = column * 26 + (Character.toUpperCase(reference.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : column - 1;
    }

    private static Cell toCell(String type, String value, String inline, int styleIndex,
                               List<String> sharedStrings, Styles styles, Path file) {
        String kind = type == null || type.isBlank() ? "n" : type;
        if ("n".equals(kind)) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return new Cell.Number(Double.parseDouble(value.trim()), value,
                        styles.isDate(styleIndex), styles.hasTime(styleIndex));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("excel_reader: invalid numeric cell value '" + value
                        + "' in " + file, failure);
            }
        }
        if ("s".equals(kind)) {
            if (value == null || value.isBlank()) {
                return null;
            }
            int index;
            try {
                index = Integer.parseInt(value.trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("excel_reader: invalid shared string index '" + value
                        + "' in " + file, failure);
            }
            if (index < 0 || index >= sharedStrings.size()) {
                throw new IllegalArgumentException("excel_reader: shared string index " + index
                        + " is out of range (" + sharedStrings.size() + " strings) in " + file);
            }
            return new Cell.Text(sharedStrings.get(index));
        }
        if ("inlineStr".equals(kind)) {
            return new Cell.Text(inline == null ? "" : inline);
        }
        if ("str".equals(kind)) {
            return value == null ? null : new Cell.Text(value);
        }
        if ("b".equals(kind)) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String text = value.trim();
            if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
                return new Cell.Bit(true);
            }
            if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
                return new Cell.Bit(false);
            }
            throw new IllegalArgumentException("excel_reader: invalid boolean cell value '" + value
                    + "' in " + file);
        }
        if ("e".equals(kind)) {
            return null;
        }
        if ("d".equals(kind)) {
            return isoDate(value, file);
        }
        throw new IllegalArgumentException("excel_reader: unsupported cell type '" + kind + "' in " + file);
    }

    private static Cell isoDate(String value, Path file) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.length() == 10) {
            try {
                return new Cell.IsoDate(LocalDate.parse(text));
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException("excel_reader: invalid ISO date cell '" + value
                        + "' in " + file, failure);
            }
        }
        try {
            return new Cell.IsoTimestamp(parseTimestamp(text));
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("excel_reader: invalid ISO date cell '" + value
                    + "' in " + file, failure);
        }
    }

    private static LocalDateTime parseTimestamp(String text) {
        String normalized = text.replace(' ', 'T');
        if (normalized.endsWith("Z")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException failure) {
            return OffsetDateTime.parse(text).toLocalDateTime();
        }
    }

    private static String readTextContainer(XMLStreamReader xml, String root) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        boolean phonetic = false;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamReader.START_ELEMENT) {
                String local = xml.getLocalName();
                if ("rPh".equals(local)) {
                    phonetic = true;
                } else if ("t".equals(local) && !phonetic) {
                    text.append(xml.getElementText());
                }
            } else if (event == XMLStreamReader.END_ELEMENT) {
                String local = xml.getLocalName();
                if ("rPh".equals(local)) {
                    phonetic = false;
                } else if (root.equals(local)) {
                    return text.toString();
                }
            }
        }
        throw new IllegalArgumentException("excel_reader: unterminated " + root + " element");
    }

    // ------------------------------------------------------------------- table

    private static ArrowTable build(ParsedSheet parsed, String sheetName, Options options, Path file,
                                    boolean date1904)
            throws InterruptedException {
        List<Integer> rowNumbers = new ArrayList<>(parsed.rows().keySet());
        if (rowNumbers.isEmpty()) {
            if (!options.columns().isEmpty() && !options.header()) {
                return ArrowTable.of(options.columns(), List.of());
            }
            throw new IllegalArgumentException("excel_reader: worksheet '" + sheetName + "' has no rows in "
                    + file);
        }
        Map<Integer, Cell> headerRow = parsed.rows().get(rowNumbers.get(0));
        int headerWidth = headerRow.isEmpty() ? 0 : headerRow.keySet().stream()
                .mapToInt(Integer::intValue).max().orElse(-1) + 1;

        List<String> names;
        List<String> types = null;
        int[] sourceIndex;
        if (!options.columns().isEmpty()) {
            names = new ArrayList<>(options.columns().size());
            types = new ArrayList<>(options.columns().size());
            for (ColumnSpec column : options.columns()) {
                names.add(column.name());
                types.add(TypeMap.canonical(column.arrowType()));
            }
            sourceIndex = new int[names.size()];
            if (options.header()) {
                List<String> header = headerNames(headerRow, headerWidth);
                for (int i = 0; i < names.size(); i++) {
                    int position = header.indexOf(names.get(i));
                    if (position < 0) {
                        throw new IllegalArgumentException("excel_reader: worksheet '" + sheetName
                                + "' has no column '" + names.get(i) + "'; header is " + header);
                    }
                    sourceIndex[i] = position;
                }
            } else {
                for (int i = 0; i < names.size(); i++) {
                    sourceIndex[i] = i;
                }
            }
        } else if (options.header()) {
            if (headerWidth == 0) {
                throw new IllegalArgumentException("excel_reader: worksheet '" + sheetName
                        + "' has no header cells in " + file);
            }
            names = headerNames(headerRow, headerWidth);
            sourceIndex = new int[names.size()];
            for (int i = 0; i < names.size(); i++) {
                sourceIndex[i] = i;
            }
        } else {
            int width = parsed.maxColumn() + 1;
            names = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                names.add("column" + (i + 1));
            }
            sourceIndex = new int[width];
            for (int i = 0; i < width; i++) {
                sourceIndex[i] = i;
            }
        }

        int firstDataRow = options.header() ? 1 : 0;
        List<Map<Integer, Cell>> dataRows = new ArrayList<>(Math.max(0, rowNumbers.size() - firstDataRow));
        for (int i = firstDataRow; i < rowNumbers.size(); i++) {
            dataRows.add(parsed.rows().get(rowNumbers.get(i)));
        }

        List<ColumnSpec> columns = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String type = types != null ? types.get(i) : inferType(dataRows, sourceIndex[i], options);
            columns.add(ColumnSpec.of(names.get(i), type));
        }

        List<Object[]> data = new ArrayList<>(dataRows.size());
        for (int row = 0; row < dataRows.size(); row++) {
            checkInterrupted();
            Map<Integer, Cell> cells = dataRows.get(row);
            Object[] values = new Object[columns.size()];
            for (int column = 0; column < columns.size(); column++) {
                values[column] = convert(cells.get(sourceIndex[column]), columns.get(column), row + 1,
                        options, date1904);
            }
            data.add(values);
        }
        return ArrowTable.of(columns, data);
    }

    private static List<String> headerNames(Map<Integer, Cell> headerRow, int width) {
        List<String> names = new ArrayList<>(width);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < width; i++) {
            Cell cell = headerRow.get(i);
            String name = cell == null ? "" : display(cell);
            if (name.isBlank()) {
                name = "column" + (i + 1);
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("excel_reader: header contains duplicate column name '"
                        + name + "'");
            }
            names.add(name);
        }
        return names;
    }

    private static String inferType(List<Map<Integer, Cell>> rows, int column, Options options) {
        boolean any = false;
        boolean text = false;
        boolean bool = false;
        boolean number = false;
        boolean date = false;
        boolean timestamp = false;
        boolean integral = true;
        for (Map<Integer, Cell> row : rows) {
            Cell cell = row.get(column);
            if (isNull(cell, options)) {
                continue;
            }
            any = true;
            if (cell instanceof Cell.Text) {
                text = true;
            } else if (cell instanceof Cell.Bit) {
                bool = true;
            } else if (cell instanceof Cell.Number numeric) {
                if (numeric.dateStyle()) {
                    date = true;
                    timestamp |= numeric.timeStyle();
                } else {
                    number = true;
                    integral &= isIntegral(numeric.value());
                }
            } else if (cell instanceof Cell.IsoDate) {
                date = true;
            } else if (cell instanceof Cell.IsoTimestamp) {
                date = true;
                timestamp = true;
            }
        }
        if (!any || text) {
            return "Utf8";
        }
        if (date) {
            if (number || bool) {
                return "Utf8";
            }
            return timestamp ? "Timestamp" : "Date32";
        }
        if (bool) {
            return number ? "Utf8" : "Boolean";
        }
        if (number) {
            return integral ? "Int64" : "Float64";
        }
        return "Utf8";
    }

    private static boolean isIntegral(double value) {
        return !Double.isInfinite(value) && value == Math.rint(value)
                && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE;
    }

    private static Object convert(Cell cell, ColumnSpec column, int dataRow, Options options,
                                  boolean date1904) {
        if (isNull(cell, options)) {
            return null;
        }
        String type = column.arrowType();
        try {
            return switch (type) {
                case "Utf8" -> display(cell);
                case "Int64" -> toLong(cell);
                case "Float64" -> toDouble(cell);
                case "Boolean" -> toBoolean(cell);
                case "Date32" -> toDate(cell, date1904);
                case "Timestamp" -> toTimestamp(cell, date1904);
                default -> throw new IllegalArgumentException("unsupported column type '" + type + "'");
            };
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("cannot cast value '" + display(cell) + "' to " + type
                    + " in column '" + column.name() + "' (data row " + dataRow + ")", failure);
        }
    }

    private static boolean isNull(Cell cell, Options options) {
        if (cell == null) {
            return true;
        }
        return cell instanceof Cell.Text text && text.value().isEmpty() && options.emptyStringIsNull();
    }

    private static String display(Cell cell) {
        if (cell instanceof Cell.Text text) {
            return text.value();
        }
        if (cell instanceof Cell.Number numeric) {
            return numeric.raw();
        }
        if (cell instanceof Cell.Bit bit) {
            return bit.value() ? "true" : "false";
        }
        if (cell instanceof Cell.IsoDate date) {
            return date.value().toString();
        }
        if (cell instanceof Cell.IsoTimestamp timestamp) {
            return timestamp.value().toString();
        }
        throw new IllegalArgumentException("unsupported cell: " + cell);
    }

    private static long toLong(Cell cell) {
        if (cell instanceof Cell.Number numeric) {
            return new BigDecimal(numeric.raw().trim()).longValueExact();
        }
        if (cell instanceof Cell.Text text) {
            return Long.parseLong(text.value().trim());
        }
        throw new IllegalArgumentException("value is not a number");
    }

    private static double toDouble(Cell cell) {
        if (cell instanceof Cell.Number numeric) {
            return numeric.value();
        }
        if (cell instanceof Cell.Text text) {
            return Double.parseDouble(text.value().trim());
        }
        throw new IllegalArgumentException("value is not a number");
    }

    private static Boolean toBoolean(Cell cell) {
        if (cell instanceof Cell.Bit bit) {
            return bit.value();
        }
        if (cell instanceof Cell.Text text) {
            String value = text.value().trim();
            if ("true".equalsIgnoreCase(value)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(value)) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("expected true or false");
        }
        throw new IllegalArgumentException("value is not a boolean");
    }

    private static LocalDate toDate(Cell cell, boolean date1904) {
        if (cell instanceof Cell.IsoDate date) {
            return date.value();
        }
        if (cell instanceof Cell.IsoTimestamp timestamp) {
            return timestamp.value().toLocalDate();
        }
        if (cell instanceof Cell.Number numeric) {
            if (!numeric.dateStyle()) {
                throw new IllegalArgumentException("numeric cell is not date-formatted");
            }
            return excelDate(numeric.value(), date1904);
        }
        if (cell instanceof Cell.Text text) {
            String value = text.value().trim();
            if (value.length() == 10) {
                return LocalDate.parse(value);
            }
            return parseTimestamp(value).toLocalDate();
        }
        throw new IllegalArgumentException("value is not a date");
    }

    private static LocalDateTime toTimestamp(Cell cell, boolean date1904) {
        if (cell instanceof Cell.IsoTimestamp timestamp) {
            return timestamp.value();
        }
        if (cell instanceof Cell.IsoDate date) {
            return date.value().atStartOfDay();
        }
        if (cell instanceof Cell.Number numeric) {
            if (!numeric.dateStyle()) {
                throw new IllegalArgumentException("numeric cell is not date-formatted");
            }
            return excelTimestamp(numeric.value(), date1904);
        }
        if (cell instanceof Cell.Text text) {
            String value = text.value().trim();
            if (value.length() == 10) {
                return LocalDate.parse(value).atStartOfDay();
            }
            return parseTimestamp(value);
        }
        throw new IllegalArgumentException("value is not a date");
    }

    // 1900 epoch: serial 1 is 1900-01-01; Excel's fictitious 1900-02-29 needs
    // the one-day shift before serial 60. 1904 workbooks use their own epoch.
    private static LocalDate excelDate(double serial, boolean date1904) {
        long days = (long) Math.floor(serial);
        LocalDate epoch;
        if (date1904) {
            epoch = LocalDate.of(1904, 1, 1);
        } else if (days < 60) {
            epoch = LocalDate.of(1899, 12, 31);
        } else {
            epoch = LocalDate.of(1899, 12, 30);
        }
        return epoch.plusDays(days);
    }

    private static LocalDateTime excelTimestamp(double serial, boolean date1904) {
        LocalDate date = excelDate(serial, date1904);
        long dayMicros = 86_400_000_000L;
        long micros = Math.round((serial - Math.floor(serial)) * dayMicros);
        if (micros >= dayMicros) {
            date = date.plusDays(1);
            micros -= dayMicros;
        } else if (micros < 0) {
            date = date.minusDays(1);
            micros += dayMicros;
        }
        return date.atStartOfDay().plusNanos(micros * 1_000L);
    }
}
