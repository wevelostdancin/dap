package com.dap.nativeengine.engine;

import java.util.List;

/** Result of /profile: total rows plus one entry per column. */
public record ProfileData(long rows, List<ProfileColumn> columns) {
}
