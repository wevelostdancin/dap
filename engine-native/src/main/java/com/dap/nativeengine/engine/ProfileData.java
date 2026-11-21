package com.dap.nativeengine.engine;

import java.util.List;

/** Result of /profile: total rows plus one entry per column. */
public record ProfileData(long rows, List<ProfileColumn> columns) {

    /** The same profile with stddev dropped, i.e. exactly what {@code /profile} returns. */
    public ProfileData withoutStddev() {
        return new ProfileData(rows, columns.stream().map(ProfileColumn::withoutStddev).toList());
    }
}
