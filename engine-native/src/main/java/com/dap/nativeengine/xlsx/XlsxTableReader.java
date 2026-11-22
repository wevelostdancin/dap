package com.dap.nativeengine.xlsx;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.TypeMap;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Minimal OOXML .xlsx reader: java.util.zip plus StAX, no third-party Office
 * libraries. The node path streams worksheet rows in bounded chunks straight
 * into DuckDB via {@link DuckDBAppender}: neither the sheet nor a list of all
 * its rows is ever held in the JVM heap. {@link #read} stays as a materializing
 * helper for callers that need an {@link ArrowTable}.
 *
 * <p>NULL semantics match csv_reader: a missing cell and an empty string are
 * NULL by default. Cell errors and formulas without a cached value are hard
 * errors unless {@code errorsAsNull} is set.
 */
public final class XlsxTableReader {
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Set<String> TYPES =
            Set.of("Int64", "Float64", "Utf8", "Boolean", "Date32", "Timestamp");

    /** Default number of parsed rows buffered before an appender flush. */
    public static final int DEFAULT_CHUNK_ROWS = 50_000;

    /** Parsed node configuration. */
    public record Options(String sheet, boolean header, List<ColumnSpec> columns,
                          boolean emptyStringIsNull, boolean errorsAsNull) {
        public Options(String sheet, boolean header, List<ColumnSpec> columns, boolean emptyStringIsNull) {
            this(sheet, header, columns, emptyStringIsNull, false);
        }

        public Options {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /** Schema and bounded-chunk statistics of a streaming load. */
    public record LoadResult(List<ColumnSpec> columns, long rows, int chunks, int maxChunkRows) {
        public LoadResult {
            columns = List.copyOf(columns);
        }
    }

    private XlsxTableReader() {
    }

    /**
     * Materializing helper (ingest and tests): the rows still end up in an
     * Arrow table, but the sheet is streamed row by row instead of being first
     * collected into a TreeMap of cells.
     */
    public static ArrowTable read(Path file, Options options) throws InterruptedException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        validate(options);
        checkInterrupted();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file + ": file does not exist");
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            WorkbookContext context = open(zip, file, options);
            SchemaProbe probe = probe(context, options, file);
            Schema schema = resolve(probe, options, context.sheet().name(), file);
            if (schema == null) {
                return ArrowTable.of(options.columns(), List.of());
            }
            List<Object[]> rows = new ArrayList<>();
            collectRows(context, file, options, schema, rows::add);
            return ArrowTable.of(schema.columns(), rows);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file + ": "
                    + failure.getMessage(), failure);
        } catch (XMLStreamException failure) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file + ": "
                    + failure.getMessage(), failure);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file
                    + ": malformed numeric value (" + failure.getMessage() + ")", failure);
        } catch (SQLException failure) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file + ": "
                    + failure.getMessage(), failure);
        }
    }

    /**
     * Stream one worksheet into an existing DuckDB table name. Two bounded
     * passes are used: the first derives the header and infers types, the
     * second converts and appends rows in chunks of {@code chunkRows}. Peak JVM
     * heap therefore depends on the chunk size, not on the sheet size.
     */
    public static LoadResult streamInto(Connection connection, String tableName, Path file, Options options)
            throws SQLException, InterruptedException {
        return streamInto(connection, tableName, file, options, DEFAULT_CHUNK_ROWS);
    }

    public static LoadResult streamInto(Connection connection, String tableName, Path file, Options options,
                                        int chunkRows) throws SQLException, InterruptedException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        if (!(connection instanceof DuckDBConnection duck)) {
            throw new IllegalArgumentException("streamInto requires a DuckDBConnection, got "
                    + connection.getClass().getName());
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("table name must not be empty");
        }
        if (chunkRows <= 0) {
            throw new IllegalArgumentException("chunkRows must be positive, got " + chunkRows);
        }
        validate(options);
        checkInterrupted();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("cannot read Excel workbook " + file + ": file does not exist");
        }
        // Parallel readers share one job connection and DuckDB cannot interleave
        // pending query results; the same serialization as loadInto applies.
        synchronized (connection) {
            try (ZipFile zip = new ZipFile(file.toFile())) {
                WorkbookContext context = open(zip, file, options);
                SchemaProbe probe = probe(context, options, file);
                Schema schema = resolve(probe, options, context.sheet().name(), file);
                try {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(createTableDdl(
                                schema == null ? options.columns() : schema.columns(), tableName));
                    }
                    if (schema == null) {
                        return new LoadResult(options.columns(), 0, 0, 0);
                    }
                    try (DuckDBAppender appender = duck.createAppender(tableName)) {
                        AppenderSink sink = new AppenderSink(appender, schema.columns(), chunkRows);
                        long rows = collectRows(context, file, options, schema, sink);
                        sink.flush();
                        return new LoadResult(schema.columns(), rows, sink.chunks(), sink.maxChunkRows());
                    }
                } catch (SQLException | RuntimeException failure) {
                    dropTable(connection, tableName);
                    throw failure;
                }
            } catch (IOException failure) {
                throw new IllegalArgumentException("cannot read Excel workbook " + file + ": "
                        + failure.getMessage(), failure);
            } catch (XMLStreamException failure) {
                throw new IllegalArgumentException("cannot read Excel workbook " + file + ": "
                        + failure.getMessage(), failure);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("cannot read Excel workbook " + file
                        + ": malformed numeric value (" + failure.getMessage() + ")", failure);
            }
        }
    }

    private static String createTableDdl(List<ColumnSpec> columns, String tableName) {
        StringBuilder ddl = new StringBuilder("CREATE OR REPLACE TABLE ")
                .append(DuckDbSession.quote(tableName)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(DuckDbSession.quote(columns.get(i).name())).append(' ')
                    .append(columns.get(i).duckDbType());
        }
        return ddl.append(')').toString();
    }

    private static void dropTable(Connection connection, String tableName) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + DuckDbSession.quote(tableName));
        } catch (SQLException | RuntimeException ignored) {
            // best effort: the failed job owns this table
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

    private record WorkbookContext(ZipFile zip, Sheet sheet, ZipEntry sheetEntry,
                                   List<String> sharedStrings, Styles styles, boolean date1904) {
    }

    private static WorkbookContext open(ZipFile zip, Path file, Options options)
            throws IOException, XMLStreamException, InterruptedException {
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
        return new WorkbookContext(zip, sheet, sheetEntry, readSharedStrings(zip), readStyles(zip),
                workbook.date1904());
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

    @FunctionalInterface
    private interface RowVisitor {
        void visit(Map<Integer, Cell> cells, int rowNumber) throws SQLException, InterruptedException;
    }

    private record SheetScan(int maxColumn, long rowCount) {
    }

    /**
     * One StAX pass over sheetData. Only the current row is materialized; the
     * visitor decides whether to keep it, infer from it or append it.
     */
    private static SheetScan readSheet(WorkbookContext context, Path file, Options options, RowVisitor visitor)
            throws IOException, XMLStreamException, InterruptedException, SQLException {
        int maxColumn = -1;
        long rowCount = 0;
        int rowNumber = 0;
        int previousRowNumber = 0;
        int nextColumn = 0;
        int cellColumn = -1;
        int styleIndex = -1;
        String cellType = null;
        String cellRef = null;
        String value = null;
        String inline = null;
        boolean formula = false;
        Map<Integer, Cell> row = null;
        try (InputStream in = context.zip().getInputStream(context.sheetEntry())) {
            XMLStreamReader xml = reader(in);
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamReader.START_ELEMENT) {
                    switch (xml.getLocalName()) {
                        case "row" -> {
                            checkInterrupted();
                            String attribute = xml.getAttributeValue(null, "r");
                            rowNumber = attribute == null ? rowNumber + 1 : Integer.parseInt(attribute.trim());
                            if (rowCount > 0 && rowNumber <= previousRowNumber) {
                                throw new IllegalArgumentException("excel_reader: worksheet rows are not in "
                                        + "ascending order (row " + rowNumber + " follows " + previousRowNumber
                                        + ") in " + file);
                            }
                            previousRowNumber = rowNumber;
                            row = new HashMap<>();
                            nextColumn = 0;
                        }
                        case "c" -> {
                            String reference = xml.getAttributeValue(null, "r");
                            cellColumn = columnIndex(reference, nextColumn);
                            cellRef = reference == null || reference.isBlank()
                                    ? columnName(cellColumn) + rowNumber : reference;
                            nextColumn = cellColumn + 1;
                            cellType = xml.getAttributeValue(null, "t");
                            String style = xml.getAttributeValue(null, "s");
                            styleIndex = style == null ? -1 : Integer.parseInt(style.trim());
                            value = null;
                            inline = null;
                            formula = false;
                            maxColumn = Math.max(maxColumn, cellColumn);
                        }
                        case "f" -> {
                            if (cellColumn >= 0) {
                                formula = true;
                            }
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
                } else if (event == XMLStreamReader.END_ELEMENT) {
                    String local = xml.getLocalName();
                    if ("c".equals(local)) {
                        if (row != null && cellColumn >= 0) {
                            Cell cell = toCell(cellType, value, inline, formula, styleIndex,
                                    context.sharedStrings(), context.styles(), file, options, cellRef);
                            if (cell != null) {
                                row.put(cellColumn, cell);
                            }
                        }
                        cellColumn = -1;
                        cellType = null;
                        styleIndex = -1;
                        value = null;
                        inline = null;
                        formula = false;
                    } else if ("row".equals(local)) {
                        rowCount++;
                        visitor.visit(row == null ? Map.of() : row, rowNumber);
                        row = null;
                    }
                }
            }
        }
        return new SheetScan(maxColumn, rowCount);
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

    private static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        for (int value = index + 1; value > 0; value = (value - 1) / 26) {
            name.insert(0, (char) ('A' + (value - 1) % 26));
        }
        return name.toString();
    }

    private static Cell toCell(String type, String value, String inline, boolean formula, int styleIndex,
                               List<String> sharedStrings, Styles styles, Path file, Options options,
                               String ref) {
        String kind = type == null || type.isBlank() ? "n" : type;
        if (formula && (value == null || value.isBlank())) {
            if (options.errorsAsNull()) {
                return null;
            }
            throw new IllegalArgumentException("excel_reader: formula cell " + ref
                    + " has no cached value in " + file
                    + "; set errorsAsNull=true to read uncached formulas as NULL");
        }
        if ("n".equals(kind)) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return new Cell.Number(Double.parseDouble(value.trim()), value,
                        styles.isDate(styleIndex), styles.hasTime(styleIndex));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("excel_reader: invalid numeric cell value '" + value
                        + "' at " + ref + " in " + file, failure);
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
                        + "' at " + ref + " in " + file, failure);
            }
            if (index < 0 || index >= sharedStrings.size()) {
                throw new IllegalArgumentException("excel_reader: shared string index " + index
                        + " is out of range (" + sharedStrings.size() + " strings) at " + ref + " in " + file);
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
                    + "' at " + ref + " in " + file);
        }
        if ("e".equals(kind)) {
            if (options.errorsAsNull()) {
                return null;
            }
            throw new IllegalArgumentException("excel_reader: error cell " + ref + " ("
                    + (value == null ? "" : value) + ") in " + file
                    + "; set errorsAsNull=true to read error cells as NULL");
        }
        if ("d".equals(kind)) {
            return isoDate(value, file);
        }
        throw new IllegalArgumentException("excel_reader: unsupported cell type '" + kind + "' at " + ref
                + " in " + file);
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

    // ------------------------------------------------------------------- schema

    /** Incremental equivalent of the original whole-sheet inferType pass. */
    private static final class TypeStats {
        private boolean any;
        private boolean text;
        private boolean bool;
        private boolean number;
        private boolean date;
        private boolean timestamp;
        private boolean integral = true;

        void add(Cell cell) {
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

        String type() {
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
    }

    private static final class SchemaProbe {
        private final Options options;
        private final Map<Integer, TypeStats> stats = new HashMap<>();
        private Map<Integer, Cell> firstRow;
        private int maxColumn = -1;
        private long rowCount;

        private SchemaProbe(Options options) {
            this.options = options;
        }

        RowVisitor visitor() {
            return this::visit;
        }

        private void visit(Map<Integer, Cell> cells, int rowNumber) {
            long index = rowCount++;
            if (firstRow == null) {
                firstRow = cells;
            }
            if (options.header() && index == 0) {
                return;
            }
            for (Map.Entry<Integer, Cell> entry : cells.entrySet()) {
                if (isNull(entry.getValue(), options)) {
                    continue;
                }
                stats.computeIfAbsent(entry.getKey(), ignored -> new TypeStats()).add(entry.getValue());
            }
        }

        String type(int column) {
            TypeStats columnStats = stats.get(column);
            return columnStats == null ? "Utf8" : columnStats.type();
        }
    }

    private record Schema(List<String> names, List<ColumnSpec> columns, int[] sourceIndex,
                          Map<Integer, Cell> headerRow, boolean headerDerived) {
    }

    private static SchemaProbe probe(WorkbookContext context, Options options, Path file)
            throws IOException, XMLStreamException, InterruptedException, SQLException {
        SchemaProbe probe = new SchemaProbe(options);
        SheetScan scan = readSheet(context, file, options, probe.visitor());
        probe.maxColumn = scan.maxColumn();
        probe.rowCount = scan.rowCount();
        return probe;
    }

    /** {@code null} means "valid workbook, zero data columns" (empty table). */
    private static Schema resolve(SchemaProbe probe, Options options, String sheetName, Path file) {
        if (probe.rowCount == 0) {
            if (!options.columns().isEmpty() && !options.header()) {
                return null;
            }
            throw new IllegalArgumentException("excel_reader: worksheet '" + sheetName + "' has no rows in "
                    + file);
        }
        Map<Integer, Cell> headerRow = probe.firstRow == null ? Map.of() : probe.firstRow;
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
            int width = probe.maxColumn + 1;
            names = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                names.add("column" + (i + 1));
            }
            sourceIndex = new int[width];
            for (int i = 0; i < width; i++) {
                sourceIndex[i] = i;
            }
        }

        List<ColumnSpec> columns = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String type = types != null ? types.get(i) : probe.type(sourceIndex[i]);
            columns.add(ColumnSpec.of(names.get(i), type));
        }
        return new Schema(List.copyOf(names), List.copyOf(columns), sourceIndex, headerRow,
                options.columns().isEmpty() && options.header());
    }

    private static boolean isIntegral(double value) {
        return !Double.isInfinite(value) && value == Math.rint(value)
                && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE;
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

    // -------------------------------------------------------------------- rows

    @FunctionalInterface
    private interface RowSink {
        void accept(Object[] values) throws SQLException, InterruptedException;
    }

    /** Second pass: convert each row and hand it to the sink without retaining it. */
    private static long collectRows(WorkbookContext context, Path file, Options options, Schema schema,
                                    RowSink sink)
            throws IOException, XMLStreamException, InterruptedException, SQLException {
        long[] seen = {0};
        long[] written = {0};
        readSheet(context, file, options, (cells, rowNumber) -> {
            long index = seen[0]++;
            if (options.header() && index == 0) {
                return;
            }
            checkInterrupted();
            if (schema.headerDerived()) {
                checkDeclaredWidth(cells, schema, context.sheet().name(), rowNumber, file);
            }
            Object[] values = new Object[schema.columns().size()];
            for (int column = 0; column < values.length; column++) {
                values[column] = convert(cells.get(schema.sourceIndex()[column]), schema.columns().get(column),
                        rowNumber, options, context.date1904());
            }
            sink.accept(values);
            written[0]++;
        });
        return written[0];
    }

    private static void checkDeclaredWidth(Map<Integer, Cell> cells, Schema schema, String sheetName,
                                           int rowNumber, Path file) {
        for (int column : cells.keySet()) {
            if (column >= schema.columns().size()) {
                throw new IllegalArgumentException("excel_reader: worksheet '" + sheetName + "' row " + rowNumber
                        + " column " + columnName(column) + " is outside the header ("
                        + schema.columns().size() + " columns) in " + file
                        + "; add the column explicitly or remove the extra cells");
            }
        }
    }

    /** Bounded buffer flushed through one appender every {@code chunkRows} rows. */
    private static final class AppenderSink implements RowSink {
        private final DuckDBAppender appender;
        private final List<ColumnSpec> columns;
        private final int chunkRows;
        private final List<Object[]> chunk;
        private int chunks;
        private int maxChunkRows;

        private AppenderSink(DuckDBAppender appender, List<ColumnSpec> columns, int chunkRows) {
            this.appender = appender;
            this.columns = columns;
            this.chunkRows = chunkRows;
            this.chunk = new ArrayList<>(chunkRows);
        }

        @Override
        public void accept(Object[] values) throws SQLException {
            chunk.add(values);
            if (chunk.size() >= chunkRows) {
                flush();
            }
        }

        void flush() throws SQLException {
            if (chunk.isEmpty()) {
                return;
            }
            maxChunkRows = Math.max(maxChunkRows, chunk.size());
            for (Object[] values : chunk) {
                appender.beginRow();
                for (int column = 0; column < columns.size(); column++) {
                    append(appender, values[column]);
                }
                appender.endRow();
            }
            chunk.clear();
            appender.flush();
            chunks++;
        }

        int chunks() {
            return chunks;
        }

        int maxChunkRows() {
            return maxChunkRows;
        }
    }

    private static void append(DuckDBAppender appender, Object value) throws SQLException {
        if (value == null) {
            appender.appendNull();
        } else if (value instanceof Long typed) {
            appender.append(typed.longValue());
        } else if (value instanceof Double typed) {
            appender.append(typed.doubleValue());
        } else if (value instanceof Boolean typed) {
            appender.append(typed.booleanValue());
        } else if (value instanceof LocalDate typed) {
            appender.append(typed);
        } else if (value instanceof LocalDateTime typed) {
            appender.append(typed);
        } else if (value instanceof BigDecimal typed) {
            appender.append(typed);
        } else {
            appender.append(value.toString());
        }
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
