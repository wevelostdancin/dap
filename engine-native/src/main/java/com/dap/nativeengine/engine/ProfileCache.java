package com.dap.nativeengine.engine;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Run-scoped cache of column statistics. A profile is shared by the markdown_report
 * profile section and GET /profile of the same node; both ask for the same plan and
 * columns during one workflow run, so only one DuckDB scan is needed.
 *
 * <p>The generation is bumped at the start of every run, which invalidates the previous
 * run's entries without a map-wide clear. The SQL text of the plan is part of the key, so
 * a config change that produces a different plan cannot hit a stale entry. All access is
 * lock-free: DAG nodes materialize on a virtual-thread pool. Concurrent cold misses may
 * compute the same profile more than once; only the first result is cached, and reads
 * never observe a partial entry.
 */
public final class ProfileCache {
    /** Generations are few; entries are bounded so a long-lived session cannot grow forever. */
    private static final int MAX_ENTRIES = 256;

    private record Key(String workflowId, String nodeId, String sql, List<String> columns,
                       long generation) {
    }

    private final ConcurrentHashMap<Key, ProfileData> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();

    /** Start a new generation for the workflow; older entries become unreachable. */
    public void invalidate(String workflowId) {
        if (workflowId != null) {
            generations.computeIfAbsent(workflowId, ignored -> new AtomicLong()).incrementAndGet();
        }
    }

    public ProfileData get(String workflowId, String nodeId, String sql, List<ColumnSpec> columns) {
        if (workflowId == null || nodeId == null) {
            return null;
        }
        AtomicLong generation = generations.get(workflowId);
        return entries.get(new Key(workflowId, nodeId, sql,
                columns.stream().map(ColumnSpec::name).toList(),
                generation == null ? 0L : generation.get()));
    }

    public void put(String workflowId, String nodeId, String sql, List<ColumnSpec> columns,
                    ProfileData data) {
        if (workflowId == null || nodeId == null) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        AtomicLong generation = generations.computeIfAbsent(workflowId, ignored -> new AtomicLong());
        entries.putIfAbsent(new Key(workflowId, nodeId, sql,
                Objects.requireNonNull(columns).stream().map(ColumnSpec::name).toList(),
                generation.get()), Objects.requireNonNull(data));
    }
}
