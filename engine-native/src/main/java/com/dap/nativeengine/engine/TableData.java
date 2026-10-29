package com.dap.nativeengine.engine;

import java.util.List;

/** One table preview: total row count, schema and the requested page of values. */
public record TableData(long rows, List<ColumnSpec> columns, List<List<String>> data) {
    public int preview() {
        return data.size();
    }
}
