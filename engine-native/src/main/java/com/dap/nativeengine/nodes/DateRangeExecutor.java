package com.dap.nativeengine.nodes;

import com.dap.nativeengine.engine.ColumnSpec;
import com.dap.nativeengine.engine.LazyPlan;
import com.dap.nativeengine.exec.NodeContext;
import com.dap.nativeengine.exec.NodeExecutor;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/** dap.date_range: one Timestamp row per step between two inclusive bounds. */
public final class DateRangeExecutor implements NodeExecutor {
    private static final String OUTPUT = "date";

    @Override
    public String nodeId() {
        return "dap.date_range";
    }

    @Override
    public LazyPlan execute(NodeContext context, List<LazyPlan> inputs) {
        JsonNode config = context.config();
        String startText = NodeSql.requiredText(config, "start");
        String endText = NodeSql.requiredText(config, "end");
        String step = TimeSeriesSupport.interval(config, "step", "date_range");
        LocalDateTime start = timestamp(startText, "start");
        LocalDateTime end = timestamp(endText, "end");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("date_range: 'start' (" + startText
                    + ") must not be after 'end' (" + endText + ")");
        }

        String sql = "SELECT unnest(generate_series(TIMESTAMP "
                + NodeSql.literal(literal(start)) + ", TIMESTAMP " + NodeSql.literal(literal(end))
                + ", INTERVAL " + NodeSql.literal(step) + ")) AS " + NodeSql.quote(OUTPUT);
        return new LazyPlan(context.db(), sql, List.of(ColumnSpec.of(OUTPUT, "Timestamp")));
    }

    /** ISO local date or timestamp; a bare date starts at midnight. */
    private static LocalDateTime timestamp(String text, String field) {
        String value = text.trim();
        try {
            return LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (DateTimeParseException failure) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                throw new IllegalArgumentException("date_range: '" + field
                        + "' must be an ISO date or timestamp, got '" + value + "'");
            }
        }
    }

    /** DuckDB reads "YYYY-MM-DD HH:MM:SS" in both DATE and TIMESTAMP positions. */
    private static String literal(LocalDateTime value) {
        return value.toString().replace('T', ' ');
    }
}
