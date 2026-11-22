package com.dap.nativeengine.engine;

import org.apache.arrow.vector.TimeStampMicroVector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Value fidelity at the ArrowTable boundary: temporal precision and exact decimals. */
class ArrowTableTest {
    @Test
    void timestampPreservesMicrosecondsInArrowAndDuckDbRoundTrip() throws Exception {
        LocalDateTime beforeEpoch = LocalDateTime.parse("1969-12-31T23:59:59.123456");
        LocalDateTime afterEpoch = LocalDateTime.parse("2024-05-06T07:08:09.123456");
        List<ColumnSpec> columns = List.of(ColumnSpec.of("ts", "Timestamp"));
        try (ArrowTable table = ArrowTable.of(columns,
                List.of(new Object[]{afterEpoch}, new Object[]{beforeEpoch}))) {
            TimeStampMicroVector vector = (TimeStampMicroVector) table.root().getVector(0);
            assertEquals(micros(afterEpoch), vector.get(0));
            assertEquals(micros(beforeEpoch), vector.get(1));
            assertEquals(afterEpoch, table.get(0, 0));
            assertEquals(beforeEpoch, table.get(0, 1));

            try (DuckDbSession db = new DuckDbSession()) {
                String name = db.load(table, "ts_roundtrip");
                try (Connection connection = db.newConnection();
                     Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT ts FROM \"" + name + "\" ORDER BY ts")) {
                    assertEquals(true, result.next());
                    assertEquals(beforeEpoch, result.getTimestamp(1).toLocalDateTime());
                    assertEquals(true, result.next());
                    assertEquals(afterEpoch, result.getTimestamp(1).toLocalDateTime());
                }
            }
        }
    }

    @Test
    void time64KeepsMicrosecondsAndDropsOnlySubMicrosecondNanos() {
        LocalTime expected = LocalTime.parse("12:34:56.123456");
        try (ArrowTable table = ArrowTable.of(List.of(ColumnSpec.of("t", "Time64")),
                List.of(new Object[]{expected}, new Object[]{LocalTime.ofNanoOfDay(1_234_567_890L)}))) {
            assertEquals(expected, table.get(0, 0));
            assertEquals(LocalTime.ofNanoOfDay(1_234_567_000L), table.get(0, 1));
        }
    }

    @Test
    void decimal128RoundTripsExactUnscaledValue() {
        BigDecimal value = new BigDecimal("12345678901234567890.123456789");
        try (ArrowTable table = ArrowTable.of(
                List.of(ColumnSpec.of("amount", "Decimal128(38,9)")), List.<Object[]>of(new Object[]{value}))) {
            assertEquals(value, table.get(0, 0));
        }
    }

    private static long micros(LocalDateTime value) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, value.toInstant(ZoneOffset.UTC));
    }
}
