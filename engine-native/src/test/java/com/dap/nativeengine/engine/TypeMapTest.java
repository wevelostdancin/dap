package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeMapTest {
    @Test
    void mapsDuckDbTypeNamesReportedByDescribeToArrowTypes() {
        assertEquals("Int64", TypeMap.fromDuckDb("BIGINT"));
        assertEquals("Int64", TypeMap.fromDuckDb("INTEGER"));
        assertEquals("Int64", TypeMap.fromDuckDb("SMALLINT"));
        assertEquals("Int64", TypeMap.fromDuckDb("TINYINT"));
        assertEquals("Int64", TypeMap.fromDuckDb("UINTEGER"));
        assertEquals("Int64", TypeMap.fromDuckDb("USMALLINT"));
        assertEquals("Int64", TypeMap.fromDuckDb("UTINYINT"));
        assertEquals("Decimal128(38,0)", TypeMap.fromDuckDb("HUGEINT"));
        assertEquals("Decimal128(20,0)", TypeMap.fromDuckDb("UBIGINT"));
        assertEquals("Float64", TypeMap.fromDuckDb("DOUBLE"));
        assertEquals("Float64", TypeMap.fromDuckDb("REAL"));
        assertEquals("Float64", TypeMap.fromDuckDb("FLOAT"));
        assertEquals("Utf8", TypeMap.fromDuckDb("VARCHAR"));
        assertEquals("Utf8", TypeMap.fromDuckDb("TEXT"));
        assertEquals("Boolean", TypeMap.fromDuckDb("BOOLEAN"));
        assertEquals("Date32", TypeMap.fromDuckDb("DATE"));
        assertEquals("Time64", TypeMap.fromDuckDb("TIME"));
        assertEquals("Timestamp", TypeMap.fromDuckDb("TIMESTAMP"));
    }

    @Test
    void mapsDecimalTypesWithPrecisionAndScaleAndIgnoresCaseAndSpaces() {
        assertEquals("Decimal128(18,3)", TypeMap.fromDuckDb("DECIMAL(18,3)"));
        assertEquals("Decimal128(21,1)", TypeMap.fromDuckDb(" decimal(21, 1) "));
    }

    @Test
    void rejectsUnsupportedDuckDbTypes() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeMap.fromDuckDb("BLOB"));
        assertTrue(failure.getMessage().contains("BLOB"), failure.getMessage());
    }

    @Test
    void mapsWideIntegerTypesToExactDecimals() {
        assertEquals("Decimal128(38,0)", TypeMap.fromDuckDb(" hugeint "));
        assertEquals("Decimal128(38,0)", TypeMap.fromDuckDb("hugeint"));
        assertEquals("Decimal128(20,0)", TypeMap.fromDuckDb(" ubigint "));
        assertEquals("Decimal128(20,0)", TypeMap.fromDuckDb("ubigint"));
    }

    @Test
    void integerBoundariesReportedByDuckDbAreMappedWithoutLoss() throws Exception {
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            assertEquals("Int64", TypeMap.fromDuckDb(typeOf(statement, "CAST(9223372036854775807 AS BIGINT)")));
            assertEquals("Int64", TypeMap.fromDuckDb(typeOf(statement, "CAST(4294967295 AS UINTEGER)")));
            assertEquals("Int64", TypeMap.fromDuckDb(typeOf(statement, "CAST(65535 AS USMALLINT)")));
            assertEquals("Int64", TypeMap.fromDuckDb(typeOf(statement, "CAST(255 AS UTINYINT)")));
            assertEquals("Decimal128(38,0)",
                    TypeMap.fromDuckDb(typeOf(statement, "CAST(9223372036854775808 AS HUGEINT)")));
            assertEquals("Decimal128(20,0)",
                    TypeMap.fromDuckDb(typeOf(statement, "CAST(18446744073709551615 AS UBIGINT)")));
        }
    }

    @Test
    void wideDecimalTypeIsPrintedAsDecimalByDisplayAndColumnSpec() {
        assertEquals("Decimal128(38,0)", TypeMap.display("Decimal128(38,0)"));
        ColumnSpec spec = ColumnSpec.of("total", TypeMap.fromDuckDb("HUGEINT"));
        assertEquals("Decimal128(38,0)", spec.arrowType());
        assertEquals("Number (Decimal)", spec.knimeType());
        assertEquals("DECIMAL(38,0)", spec.duckDbType());
        assertTrue(spec.numeric());
    }

    @Test
    void hugeIntAboveDecimal38RangeFailsCastInsteadOfTruncating() throws Exception {
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            assertEquals("HUGEINT", typeOf(statement, "170141183460469231731687303715884105727"));
            java.sql.SQLException failure = assertThrows(java.sql.SQLException.class, () -> statement.executeQuery(
                    "SELECT CAST(170141183460469231731687303715884105727 AS DECIMAL(38,0))"));
            String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
            assertTrue(message.contains("conversion error"), failure.getMessage());
            assertTrue(message.contains("decimal(38,0)"), failure.getMessage());
            assertTrue(message.contains("170141183460469231731687303715884105727"), failure.getMessage());
        }
    }

    @Test
    void describeKeepsExactDecimalPrecisionAndScale() throws Exception {
        try (DuckDbSession db = new DuckDbSession(); Connection connection = db.newConnection()) {
            List<ColumnSpec> columns = db.describe(connection,
                    "SELECT CAST(1.5 AS DECIMAL(38,9)) AS wide, CAST(2 AS DECIMAL(18,4)) AS narrow");
            assertEquals("Decimal128(38,9)", columns.get(0).arrowType());
            assertEquals("Decimal128(18,4)", columns.get(1).arrowType());
        }
    }

    private static String typeOf(Statement statement, String expression) throws Exception {
        try (ResultSet result = statement.executeQuery("DESCRIBE SELECT " + expression + " AS v")) {
            result.next();
            return result.getString("column_type");
        }
    }
}

