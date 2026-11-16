package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.DuckDbSession;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.engine.TableData;
import com.dap.nativeengine.exec.NodeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for the issues found in the P3 independent review. */
class P3ReviewRegressionTest {
    @TempDir
    Path temp;

    @Test
    void spearmanUsesAverageRanksForTies() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("x", "Int64"), ColumnSpec.of("y", "Int64"));
            List<Object[]> rows = List.of(
                    new Object[]{1L, 2L}, new Object[]{2L, 3L}, new Object[]{2L, 3L},
                    new Object[]{2L, 3L}, new Object[]{2L, 3L}, new Object[]{3L, 1L});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"columns":["x","y"],"method":"spearman"}
                    """);

            TableData data = new CorrelationExecutor().execute(context, List.of(input)).preview(10);
            // P6 long output: column_a, column_b, method, n, correlation, p_value, ci_low, ci_high, partial.
            assertEquals(-0.2, Double.parseDouble(data.data().get(0).get(4)), 1e-9,
                    "Spearman must use average ranks for ties");
        }
    }

    @Test
    void excelDropsXmlInvalidCharacters() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("s", "Utf8"));
            List<Object[]> rows = List.<Object[]>of(new Object[]{"bad\u000Bchar"});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            Path file = temp.resolve("ctrl.xlsx");
            NodeContext context = NodeTestSupport.context(db, "{\"file\":\"" + file + "\"}");

            new ExcelWriterExecutor().execute(context, List.of(input));
            String sheet = entry(file, "xl/worksheets/sheet1.xml");
            assertFalse(sheet.contains("\u000B"), "control characters must not reach the XML");
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(sheet.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void excelWritesDecimalAsNumber() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("d", "Decimal128(12,2)"));
            List<Object[]> rows = List.<Object[]>of(new Object[]{new BigDecimal("1234.50")});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            Path file = temp.resolve("decimal.xlsx");
            NodeContext context = NodeTestSupport.context(db, "{\"file\":\"" + file + "\"}");

            new ExcelWriterExecutor().execute(context, List.of(input));
            assertTrue(entry(file, "xl/worksheets/sheet1.xml").contains("<v>1234.50</v>"),
                    "Decimal128 must stay numeric in the sheet");
        }
    }

    @Test
    void excelRejectsReservedOrLongSheetNames() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Int64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.<Object[]>of(new Object[]{1L}));

            NodeContext reserved = NodeTestSupport.context(db,
                    "{\"file\":\"" + temp.resolve("h.xlsx") + "\",\"sheet\":\"History\"}");
            assertThrows(IllegalArgumentException.class,
                    () -> new ExcelWriterExecutor().execute(reserved, List.of(input)));

            String longName = "x".repeat(32);
            NodeContext tooLong = NodeTestSupport.context(db,
                    "{\"file\":\"" + temp.resolve("l.xlsx") + "\",\"sheet\":\"" + longName + "\"}");
            assertThrows(IllegalArgumentException.class,
                    () -> new ExcelWriterExecutor().execute(tooLong, List.of(input)));
        }
    }

    @Test
    void resampleDropsNullTimestamps() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("ts", "Timestamp"), ColumnSpec.of("v", "Int64"));
            List<Object[]> rows = List.of(
                    new Object[]{LocalDateTime.of(2024, 1, 1, 10, 0), 1L},
                    new Object[]{null, 2L});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, """
                    {"timeColumn":"ts","every":"1 day","aggregations":[{"column":"v","fn":"sum"}]}
                    """);

            TableData data = new ResampleExecutor().execute(context, List.of(input)).preview(10);
            assertTrue(data.data().stream().noneMatch(row -> row.get(0) == null),
                    "NULL timestamps must not produce a NULL bucket");
        }
    }

    @Test
    void rollingRejectsOverflowingWindow() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("v", "Int64"));
            LazyPlan input = NodeTestSupport.table(db, columns, List.<Object[]>of(new Object[]{1L}));
            NodeContext context = NodeTestSupport.context(db, """
                    {"column":"v","output":"r","window":18446744073709551617,"fn":"mean",
                     "orderBy":[{"column":"v","direction":"asc"}]}
                    """);

            assertThrows(IllegalArgumentException.class,
                    () -> new RollingExecutor().execute(context, List.of(input)));
        }
    }

    @Test
    void valueCountsOrderTiesByTypedValue() throws Exception {
        try (DuckDbSession db = new DuckDbSession()) {
            List<ColumnSpec> columns = List.of(ColumnSpec.of("n", "Int64"));
            List<Object[]> rows = List.of(new Object[]{10L}, new Object[]{2L});
            LazyPlan input = NodeTestSupport.table(db, columns, rows);
            NodeContext context = NodeTestSupport.context(db, "{\"column\":\"n\"}");

            TableData data = new ValueCountsExecutor().execute(context, List.of(input)).preview(10);
            assertEquals("2", data.data().get(0).get(0));
            assertEquals("10", data.data().get(1).get(0));
        }
    }

    private static String entry(Path file, String name) throws Exception {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry(name);
            try (InputStream stream = zip.getInputStream(entry)) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
