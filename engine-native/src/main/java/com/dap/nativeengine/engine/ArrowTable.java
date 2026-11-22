package com.dap.nativeengine.engine;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.TimeMicroVector;
import org.apache.arrow.vector.TimeStampMicroVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-memory Arrow table used at the engine boundary (CSV ingestion and, later,
 * IPC / Polars interop). Transforms stay as DuckDB relations.
 */
public final class ArrowTable implements AutoCloseable {
    private final List<ColumnSpec> columns;
    private final VectorSchemaRoot root;
    private final BufferAllocator allocator;
    private final int rowCount;

    private ArrowTable(List<ColumnSpec> columns, VectorSchemaRoot root, BufferAllocator allocator, int rowCount) {
        this.columns = List.copyOf(columns);
        this.root = root;
        this.allocator = allocator;
        this.rowCount = rowCount;
    }

    public static ArrowTable of(List<ColumnSpec> columns, List<Object[]> rows) {
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(rows, "rows");
        BufferAllocator allocator = new RootAllocator();
        try {
            Schema schema = new Schema(fields(columns));
            VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator);
            root.allocateNew();
            for (int column = 0; column < columns.size(); column++) {
                FieldVector vector = root.getVector(column);
                for (int row = 0; row < rows.size(); row++) {
                    Object value = rows.get(row)[column];
                    set(vector, row, value);
                }
                vector.setValueCount(rows.size());
            }
            root.setRowCount(rows.size());
            return new ArrowTable(columns, root, allocator, rows.size());
        } catch (RuntimeException | Error failure) {
            allocator.close();
            throw failure;
        }
    }

    private static List<Field> fields(List<ColumnSpec> columns) {
        List<Field> fields = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns) {
            String type = TypeMap.canonical(column.arrowType());
            ArrowType arrowType;
            if ("Int64".equals(type)) {
                arrowType = new ArrowType.Int(64, true);
            } else if ("Float64".equals(type)) {
                arrowType = new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            } else if ("Utf8".equals(type)) {
                arrowType = ArrowType.Utf8.INSTANCE;
            } else if ("Boolean".equals(type)) {
                arrowType = ArrowType.Bool.INSTANCE;
            } else if ("Date32".equals(type)) {
                arrowType = new ArrowType.Date(org.apache.arrow.vector.types.DateUnit.DAY);
            } else if ("Time64".equals(type)) {
                arrowType = new ArrowType.Time(TimeUnit.MICROSECOND, 64);
            } else if ("Timestamp".equals(type)) {
                arrowType = new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
            } else if (type.startsWith("Decimal128")) {
                arrowType = new ArrowType.Decimal(decimalPrecision(type), decimalScale(type), 128);
            } else {
                throw new IllegalArgumentException("unsupported Arrow type: " + column.arrowType());
            }
            fields.add(new Field(column.name(), new FieldType(true, arrowType, null), null));
        }
        return fields;
    }

    private static int decimalPrecision(String type) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("Decimal128\\((\\d+),\\s*(\\d+)\\)").matcher(type);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 38;
    }

    private static int decimalScale(String type) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("Decimal128\\((\\d+),\\s*(\\d+)\\)").matcher(type);
        return matcher.matches() ? Integer.parseInt(matcher.group(2)) : 9;
    }

    private static void set(FieldVector vector, int row, Object value) {
        if (value == null) {
            vector.setNull(row);
            return;
        }
        if (vector instanceof BigIntVector typed) {
            typed.setSafe(row, ((Number) value).longValue());
        } else if (vector instanceof Float8Vector typed) {
            typed.setSafe(row, ((Number) value).doubleValue());
        } else if (vector instanceof VarCharVector typed) {
            typed.setSafe(row, value.toString().getBytes(StandardCharsets.UTF_8));
        } else if (vector instanceof BitVector typed) {
            typed.setSafe(row, Boolean.TRUE.equals(value) ? 1 : 0);
        } else if (vector instanceof DateDayVector typed) {
            typed.setSafe(row, Math.toIntExact(((LocalDate) value).toEpochDay()));
        } else if (vector instanceof TimeMicroVector typed) {
            typed.setSafe(row, ((LocalTime) value).toNanoOfDay() / 1_000L);
        } else if (vector instanceof TimeStampMicroVector typed) {
            typed.setSafe(row, ChronoUnit.MICROS.between(Instant.EPOCH,
                    ((LocalDateTime) value).toInstant(ZoneOffset.UTC)));
        } else if (vector instanceof DecimalVector typed) {
            // Exact BigDecimal at the Arrow boundary; profile/statistics widen to double by design.
            typed.setSafe(row, (BigDecimal) value);
        } else {
            throw new IllegalArgumentException("unsupported vector: " + vector.getClass().getName());
        }
    }

    public List<ColumnSpec> columns() {
        return columns;
    }

    public int rowCount() {
        return rowCount;
    }

    public VectorSchemaRoot root() {
        return root;
    }

    /** Row value in a DuckDB-Appender-friendly Java representation; null is SQL NULL. */
    public Object get(int column, int row) {
        FieldVector vector = root.getVector(column);
        if (vector.isNull(row)) {
            return null;
        }
        if (vector instanceof BigIntVector typed) {
            return typed.get(row);
        }
        if (vector instanceof Float8Vector typed) {
            return typed.get(row);
        }
        if (vector instanceof VarCharVector typed) {
            return new String(typed.get(row), StandardCharsets.UTF_8);
        }
        if (vector instanceof BitVector typed) {
            return typed.get(row) != 0;
        }
        if (vector instanceof DateDayVector typed) {
            return LocalDate.ofEpochDay(typed.get(row));
        }
        if (vector instanceof TimeMicroVector typed) {
            return LocalTime.ofNanoOfDay(typed.get(row) * 1_000L);
        }
        if (vector instanceof TimeStampMicroVector typed) {
            long micros = typed.get(row);
            long seconds = Math.floorDiv(micros, 1_000_000L);
            long nanos = Math.floorMod(micros, 1_000_000L) * 1_000L;
            return LocalDateTime.ofEpochSecond(seconds, (int) nanos, ZoneOffset.UTC);
        }
        if (vector instanceof DecimalVector typed) {
            return typed.getObject(row);
        }
        throw new IllegalArgumentException("unsupported vector: " + vector.getClass().getName());
    }

    @Override
    public void close() {
        root.close();
        allocator.close();
    }
}
