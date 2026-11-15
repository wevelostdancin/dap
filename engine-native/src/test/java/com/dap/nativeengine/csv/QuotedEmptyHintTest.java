package com.dap.nativeengine.csv;

import com.dap.nativeengine.engine.ArrowTable;
import com.dap.nativeengine.engine.ColumnSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A quoted empty string in a numeric column must point at emptyStringIsNull. */
class QuotedEmptyHintTest {
    private static final String CSV = "a,b\n1,\"\"\n";
    private static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("a", "Int64"), ColumnSpec.of("b", "Float64"));

    @Test
    void numericQuotedEmptyStringCarriesTheHint() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CsvTableReader.parse(CSV,
                        new CsvTableReader.Options(',', true, COLUMNS, false)));

        assertTrue(failure.getMessage().contains("emptyStringIsNull"),
                "the error must hint at emptyStringIsNull: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("column 'b'"), failure.getMessage());
    }

    @Test
    void emptyStringIsNullParsesTheSameCsv() {
        try (ArrowTable table = CsvTableReader.parse(CSV,
                new CsvTableReader.Options(',', true, COLUMNS, true))) {
            assertEquals(1, table.rowCount());
            assertNull(table.get(1, 0), "with emptyStringIsNull=true the quoted empty is NULL");
        }
    }
}
