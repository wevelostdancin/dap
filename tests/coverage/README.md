# Coverage scenarios

Each file in this directory describes one or more executable coverage cases for a
capability of the taxonomy in `docs/superpowers/specs/2026-11-15-dap-mcp-service-design.md`
(§6). A case is a workflow run end-to-end through `/v1` plus value checks, using the same
conventions as the golden corpus (`tests/goldens-native`): logical node types, the
`dataset` shorthand for the fixtures in `tests/goldens-native/data`, and `expect` checks.

```json
{
  "format": "dap-coverage-scenario/1",
  "cases": [
    {
      "task": "I1",
      "title": "Arbitrary SQL over workflow node outputs",
      "kind": "node",
      "requires": [],
      "workflow": {
        "id": "cov-sql-query",
        "nodes": [{"id": "src", "type": "csv_reader", "config": {"dataset": "P2"}}],
        "edges": []
      },
      "checks": [{"node": "src", "kind": "expect", "rows": 6, "columns": ["id"]}]
    }
  ]
}
```

- `task` — taxonomy id from spec §6.2 (the coverage counter is PASS / 69).
- `kind` — `node` or `recipe` (a documented SQL recipe over `dap.sql_query`).
- `requires` — optional capabilities the case needs: `sqlite_scanner`,
  `httpfs` (DuckDB extensions installed by `make extensions`) or `network`.
  A runner that cannot provide them must report the case as skipped, not failed.
- `workflow` / `checks` — the same shape as a golden workflow file, so a runner can
  reuse `tests/goldens-native/runner.py` helpers (`native_binding`, `run_on_native`,
  `compare_table`). In addition to the dataset shorthand, config strings may contain
  the literal `{DATA}`, replaced with the absolute `tests/goldens-native/data` directory.
- `inputs` aliases in `dap.sql_query` are the names used inside `sql`; convention is the
  upstream node id, but the engine only requires the count to match the connected ports.
