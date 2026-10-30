package com.dap.nativeengine.engine;

import org.junit.jupiter.api.Test;

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
        assertEquals("Int64", TypeMap.fromDuckDb("HUGEINT"));
        assertEquals("Int64", TypeMap.fromDuckDb("UBIGINT"));
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
}

