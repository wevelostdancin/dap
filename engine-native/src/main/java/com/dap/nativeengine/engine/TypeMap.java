package com.dap.nativeengine.engine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Arrow type names used by the native engine and their DuckDB / KNIME equivalents. */
public final class TypeMap {
    private static final Pattern DECIMAL = Pattern.compile("Decimal128\\((\\d+),\\s*(\\d+)\\)");
    private static final Pattern DUCKDB_DECIMAL = Pattern.compile("DECIMAL\\((\\d+),\\s*(\\d+)\\)");

    private TypeMap() {
    }

    /** Canonical Arrow-style type name (accepts KNIME names from corpus metadata too). */
    public static String canonical(String type) {
        if (type == null || type.isBlank()) {
            return "Utf8";
        }
        String value = type.trim();
        Matcher decimal = DECIMAL.matcher(value);
        if (decimal.matches()) {
            return "Decimal128(" + decimal.group(1) + "," + decimal.group(2) + ")";
        }
        return switch (value) {
            case "Number (Integer)", "Integer", "Long" -> "Int64";
            case "Number (Float)", "Double", "Float" -> "Float64";
            case "String", "VARCHAR", "UTF8" -> "Utf8";
            case "Boolean", "BOOL" -> "Boolean";
            case "Date", "DATE" -> "Date32";
            case "Time", "TIME", "Time64(us)" -> "Time64";
            case "Date&Time (Timestamp)", "Timestamp", "Timestamp(us)" -> "Timestamp";
            default -> value;
        };
    }

    public static String knimeType(String arrowType) {
        String type = canonical(arrowType);
        if (type.startsWith("Decimal128")) {
            return "Number (Decimal)";
        }
        return switch (type) {
            case "Int64" -> "Number (Integer)";
            case "Float64" -> "Number (Float)";
            case "Utf8" -> "String";
            case "Boolean" -> "Boolean";
            case "Date32" -> "Date";
            case "Time64" -> "Time";
            case "Timestamp" -> "Date&Time (Timestamp)";
            default -> type;
        };
    }

    public static String duckDbType(String arrowType) {
        String type = canonical(arrowType);
        if (type.startsWith("Decimal128")) {
            Matcher matcher = DECIMAL.matcher(type);
            if (matcher.matches()) {
                return "DECIMAL(" + matcher.group(1) + "," + matcher.group(2) + ")";
            }
            return "DECIMAL(38,9)";
        }
        return switch (type) {
            case "Int64" -> "BIGINT";
            case "Float64" -> "DOUBLE";
            case "Utf8" -> "VARCHAR";
            case "Boolean" -> "BOOLEAN";
            case "Date32" -> "DATE";
            case "Time64" -> "TIME";
            case "Timestamp" -> "TIMESTAMP";
            default -> throw new IllegalArgumentException("unsupported Arrow type: " + arrowType);
        };
    }

    public static boolean isNumeric(String arrowType) {
        String type = canonical(arrowType);
        return "Int64".equals(type) || "Float64".equals(type) || type.startsWith("Decimal128");
    }

    public static boolean isSupported(String arrowType) {
        String type = canonical(arrowType);
        return switch (type) {
            case "Int64", "Float64", "Utf8", "Boolean", "Date32", "Time64", "Timestamp" -> true;
            default -> type.startsWith("Decimal128");
        };
    }

    /** Map a DuckDB type name (as reported by DESCRIBE / typeof) to the engine's Arrow type name. */
    public static String fromDuckDb(String duckDbType) {
        if (duckDbType == null || duckDbType.isBlank()) {
            throw new IllegalArgumentException("empty DuckDB type name");
        }
        String value = duckDbType.trim().toUpperCase(Locale.ROOT);
        Matcher decimal = DUCKDB_DECIMAL.matcher(value);
        if (decimal.matches()) {
            return "Decimal128(" + decimal.group(1) + "," + decimal.group(2) + ")";
        }
        return switch (value) {
            // Narrow unsigned types max out below 2^63 and always fit in a signed Int64.
            case "BIGINT", "INTEGER", "INT", "SMALLINT", "TINYINT",
                 "UINTEGER", "USMALLINT", "UTINYINT" -> "Int64";
            // HUGEINT is 128-bit and UBIGINT reaches 2^64-1: both exceed Int64, so a
            // silent Int64 mapping would truncate values and break downstream BIGINT casts.
            case "HUGEINT", "UBIGINT" -> throw new IllegalArgumentException(
                    "DuckDB " + value + " value out of Int64 range; cast the column explicitly "
                            + "(BIGINT for in-range values, DECIMAL or VARCHAR for the full range)");
            case "DOUBLE", "REAL", "FLOAT" -> "Float64";
            case "VARCHAR", "TEXT", "STRING", "CHAR" -> "Utf8";
            case "BOOLEAN", "BOOL" -> "Boolean";
            case "DATE" -> "Date32";
            case "TIME" -> "Time64";
            case "TIMESTAMP", "DATETIME" -> "Timestamp";
            default -> throw new IllegalArgumentException("unsupported DuckDB type: " + duckDbType);
        };
    }

    /** Arrow type shown in /table and /profile columns, e.g. "Time64" for Time64(us). */
    public static String display(String arrowType) {
        return canonical(arrowType).toLowerCase(Locale.ROOT).startsWith("decimal128")
                ? canonical(arrowType) : canonical(arrowType);
    }
}
