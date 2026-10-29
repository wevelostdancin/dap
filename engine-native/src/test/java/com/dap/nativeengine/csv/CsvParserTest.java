package com.dap.nativeengine.csv;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvParserTest {
    @Test
    void parsesBomCrlfAndTabDelimiter() {
        List<List<CsvParser.Field>> rows = CsvParser.parse("\uFEFFa\tb\r\n1\t2\r\n", '\t');

        assertEquals(2, rows.size());
        assertEquals("a", rows.get(0).get(0).text());
        assertEquals("b", rows.get(0).get(1).text());
        assertEquals("1", rows.get(1).get(0).text());
        assertEquals("2", rows.get(1).get(1).text());
    }

    @Test
    void delimitersAndDoubledQuotesInsideQuotesAreLiteral() {
        List<List<CsvParser.Field>> rows = CsvParser.parse("\"a,b\",\"c\"\"d\"\n", ',');

        assertEquals(1, rows.size());
        assertEquals("a,b", rows.get(0).get(0).text());
        assertTrue(rows.get(0).get(0).quoted());
        assertEquals("c\"d", rows.get(0).get(1).text());
    }

    @Test
    void quotedEmptyFieldIsFlaggedAsQuoted() {
        List<CsvParser.Field> row = CsvParser.parse("1,\"\"\n", ',').get(0);

        assertFalse(row.get(0).quoted());
        assertEquals("1", row.get(0).text());
        assertTrue(row.get(1).quoted());
        assertEquals("", row.get(1).text());
    }

    @Test
    void unterminatedQuoteReportsStartingLine() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CsvParser.parse("a,b\n1,\"open\nmore", ','));

        assertTrue(failure.getMessage().contains("line 2"), failure.getMessage());
    }

    @Test
    void trailingNewlineDoesNotProduceAnExtraRow() {
        assertEquals(1, CsvParser.parse("a\n", ',').size());
        assertEquals(2, CsvParser.parse("a\rb\r\n", ',').size());
    }
}
