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
    void rejectsWideIntegerTypesOutsideInt64Range() {
        String[][] cases = {{"HUGEINT", "HUGEINT"}, {"UBIGINT", "UBIGINT"},
                {" hugeint ", "HUGEINT"}, {"ubigint", "UBIGINT"}};
        for (String[] testCase : cases) {
            String input = testCase[0];
            String normalized = testCase[1];
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeMap.fromDuckDb(input), input);
            assertTrue(failure.getMessage().contains(normalized), failure.getMessage());
            assertTrue(failure.getMessage().contains("value out of Int64 range"), failure.getMessage());
            assertTrue(failure.getMessage().contains("cast the column explicitly"), failure.getMessage());
        }
    }

    @Test
    void integerBoundariesReportedByDuckDbAreMappedOrRejected() throws Exception {
        try (DuckDbSession db = new DuckDbSession();
             Connection connection = db.newConnection();
             Statement statement = connection.createStatement()) {
            assertEquals("Int64", TypeMap.fromDuckDb(typeOf(statement, "CAST(9223372036854775807 AS BIGINT)")));
            assertEquals("Int64", TypeMap.fromDuckDb(typeOf(statement, "CAST(4294967295 AS UINTEGER)")));
            assertEquals("Int64", TypeMap.fromDuckDb(typeOf(statement, "CAST(65535 AS USMALLINT)")));
            assertEquals("Int64", TypeMap.fromDuckDb(typeOf(statement, "CAST(255 AS UTINYINT)")));

            assertOutOfInt64Range(statement, "CAST(9223372036854775808 AS HUGEINT)");
            assertOutOfInt64Range(statement, "CAST(18446744073709551615 AS UBIGINT)");
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

    private static void assertOutOfInt64Range(Statement statement, String expression) throws Exception {
        String type = typeOf(statement, expression);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeMap.fromDuckDb(type), expression);
        assertTrue(failure.getMessage().contains("value out of Int64 range"), failure.getMessage());
    }

    private static String typeOf(Statement statement, String expression) throws Exception {
        try (ResultSet result = statement.executeQuery("DESCRIBE SELECT " + expression + " AS v")) {
            result.next();
            return result.getString("column_type");
        }
    }
}

