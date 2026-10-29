package com.dap.nativeengine.exec;

import java.util.List;

/** Successful workflow run payload (POST /run, sync mode; also stored on a Job). */
public record RunResult(boolean ok, long durationMs, String workflow, List<NodeStateView> nodes) {
}
