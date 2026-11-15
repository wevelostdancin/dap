# Coverage scenarios

Coverage of the 69-task analytics taxonomy in
`docs/superpowers/specs/2026-11-15-dap-mcp-service-design.md` (§6.2). A task counts as
covered when a scenario for it runs end to end through `/v1` and its rows, columns and
values match the pinned expectations.

Two layers:

- `tasks.json` — the manifest: `{"format": "dap-coverage-tasks/1", "tasks": [...]}` with
  all 69 taxonomy ids, a title, `kind` (`node` or `recipe`) and the `scenario` file that
  proves the task. A task that cannot be covered carries `not_covered` with the reason
  instead of a scenario (F6, seasonality/forecast is a non-goal).
- `*.json` scenario files — `{"format": "dap-coverage-scenario/1", "cases": [...]}`; one
  case per task. Cases use the golden workflow conventions (`tests/goldens-native`):
  logical node types, the `dataset` shorthand for fixtures in `tests/goldens-native/data`,
  and `expect`/`expect_profile` checks.

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
      "checks": [{"node": "src", "kind": "expect", "rows": 6, "columns": [{"name": "id", "type": "Number (Integer)"}]}]
    }
  ]
}
```

- `task` — taxonomy id from spec §6.2; each id appears in exactly one case.
- `kind` — `node` or `recipe` (a documented SQL recipe over `dap.sql_query`).
- `requires` — optional capabilities: `sqlite_scanner`, `httpfs` (installed by
  `make extensions` into `build/duckdb-ext`) or `network`. A runner that cannot provide
  them reports the case as SKIP, not FAIL; SKIP does not count as covered.
- `workflow` / `checks` — the same shape as a golden workflow file. Config strings may
  contain `{DATA}` (absolute `tests/goldens-native/data`) and `{TMP}` (the runner's
  temporary directory, used by writer/reader round-trips).
- `steps` — a case may instead list `[{"workflow": ..., "checks": [...]}, ...]`; the
  workflows run in order in one session, so a file written by a writer step can be read
  back by a reader step.
- `mode: "save_load"` — the workflow is saved with `POST /v1/workflows/{id}/save`,
  deleted, loaded back with `POST /v1/workflows/load`, and the loaded copy is run and
  checked (task I2).
- `inputs` aliases in `dap.sql_query` are the names used inside `sql`; the convention is
  the upstream node id, but the engine only requires the count to match the connected
  ports.

Checks reuse the golden comparison rules (`docs/QUALITY.md`): doubles are compared with
`abs(a - b) <= 1e-9 * max(1, |a|, |b|)`, integers exactly, NULL positions exactly, `""`
is a value. `expect` asserts rows, the column list and (with `data`) the cells; a
`columns[]` entry may also carry `missing`, `distinct`, `min`, `max` or `mean` to compare
profile statistics. `expect_profile` asserts the declared profile columns.

Run it with:

```bash
make coverage                                  # starts/stops its own engine
python3 tests/coverage/runner.py --validate    # structural checks, no engine
python3 tests/coverage/runner.py --base URL    # reuse a running engine
python3 tests/coverage/runner.py --only B1     # limit to task ids with a prefix
```

The harness starts the engine on a free port with its own temporary `DAP_HOME` and reuses
the installed DuckDB extensions (`make extensions`), so A6 (SQLite) works offline and A8
(HTTP) runs when the network is reachable. It prints `coverage: N/69` and exits non-zero
below the target of 67.
