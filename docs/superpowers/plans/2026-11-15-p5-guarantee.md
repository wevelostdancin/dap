# DAP MCP Service — Phase 1a: Guarantee Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpower-subagent-driven-development (recommended) or superpower-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every DAP call bounded, explainable and non-leaking: read timeouts, session and job eviction, table cleanup, stable error codes, no ghost nodes, restart notice, concurrency fixes.

**Architecture:** Changes stay inside the existing engine (Java 21, Javalin, DuckDB) and the Python MCP adapter. New settings are read from env with documented defaults; `/v1` changes are additive except status codes the spec pins.

**Tech Stack:** Java 21, DuckDB 1.5.6, Javalin 6.7, JUnit 5.11.4, Python 3 stdlib.

**Spec:** `docs/superpowers/specs/2026-11-15-dap-mcp-service-design.md`

## Global Constraints

- JDK 21 via `DAP_JDK`; build with `engine-native/mvnw`; no new Maven dependencies; jar stays under 50 MiB.
- Every `/v1` change updates `api/openapi.yaml` and `docs/API.md`; contract changes update `docs/QUALITY.md`.
- Commits: conventional, English (`feat(api): ...`, `fix(engine): ...`).
- `make check` stays green after every task.
- Worktree `.worktrees/p5-guarantee`, branch `p5/guarantee`; never touch other worktrees.
- Tests follow the `NativeServiceTest` pattern: real Javalin on a random port, temp `DAP_HOME`, no shared state.

## Review Focus

1. Cross-join / 100M-row materialization on `/table` returns 408 `query_timeout` within the limit and the engine stays healthy.
2. `DELETE /v1/workflows/{id}` during an active run cancels the job, drops its tables and leaves no `csv_*` behind.
3. 100+ scripted sessions keep `sessionCount() <= DAP_MAX_SESSIONS` and heap bounded.
4. A typo in `add_node` settings returns 400 and the workflow node count does not change.
5. A quoted empty string in a numeric column produces an actionable error instead of a silent String column.

---

### Task 1: Error envelope (`code`, `hint`)

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/ApiException.java`
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/Http.java`
- Modify: `engine-native/src/main/java/com/dap/nativeengine/NativeService.java` (error mapper)
- Modify: `api/openapi.yaml` (Error schema), `docs/API.md`, `docs/QUALITY.md`
- Test: `engine-native/src/test/java/com/dap/nativeengine/api/ContractErrorTest.java`

**Interfaces:**
- Consumes: existing `ApiException(int status, String message)`.
- Produces: `ApiException(int status, String code, String message, String hint)` with getters `status()`, `code()`, `hint()`; factories `badRequest(code, message, hint)`, `notFound(code, message, hint)`, `conflict(code, message, hint)`, `timeout(code, message, hint)`. `Http.error(ctx, e)` writes `{"error": message, "code": code, "hint": hint}`; `code` defaults from status (`bad_request`, `not_found`, `conflict`, `server_error`) when null; `hint` omitted when null.

- [ ] **Step 1: Write the failing test** `ContractErrorTest.everyErrorBodyHasCode`

    // 400 unknown setting, 404 unknown workflow, 409 cancel of a finished job, 500 missing file
    for (Response r : List.of(badSetting, unknownWorkflow, cancelFinished, missingFile)) {
        assertTrue(r.body.get("error") != null && !r.body.get("error").isBlank());
        assertTrue(r.body.get("code") != null && !r.body.get("code").isBlank());
    }
    assertNotNull(badSetting.body.get("hint"));

- [ ] **Step 2: Run it and watch it fail**

    cd engine-native && ./mvnw -q -Dtest=ContractErrorTest test

Expected: FAIL, body has no `code`.

- [ ] **Step 3: Implement** the constructor and factories in `ApiException`, the envelope in `Http.error`, the mapper in `NativeService`, and document the schema in `openapi.yaml` (`Error` adds `code` required, `hint` optional) and `docs/API.md`.
- [ ] **Step 4: Run it and watch it pass**

    cd engine-native && ./mvnw -q -Dtest=ContractErrorTest test

Expected: PASS.

- [ ] **Step 5: Commit**

    git add -A && git commit -m "feat(api): add machine-readable error code and hint"

### Task 2: Status codes pinned by the spec

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/exec/WorkflowStore.java`
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/DataHandlers.java`
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/WorkflowHandlers.java` (unknown node in PATCH/DELETE/dialog)
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/Http.java` (`queryInt`)
- Modify: `api/openapi.yaml`, `docs/API.md`, `docs/QUALITY.md`
- Test: `engine-native/src/test/java/com/dap/nativeengine/api/StatusCodeContractTest.java`

**Interfaces:**
- Consumes: `ApiException` factories from Task 1; `WorkflowStore.create`; `DataHandlers.table/profile`; `WorkflowNode.state()` and `.message()`.
- Produces: workflow limit -> 409 `workflow_limit` with `hint` and `limit`; `/table`/`/profile` before run -> 409 `not_materialized` with `hint`; failed node -> 409 `node_failed` with the node message; unknown node everywhere -> 404 `unknown_node`; `?limit=abc` -> 400 `invalid_query_param`.

- [ ] **Step 1: Write the failing test** `StatusCodeContractTest.pinnedCodes`

    assertEquals(409, create101stWorkflow().status);
    assertEquals("workflow_limit", create101stWorkflow().body.get("code"));
    assertEquals(409, tableBeforeRun().status);
    assertEquals("not_materialized", tableBeforeRun().body.get("code"));
    assertEquals(409, tableOfFailedNode().status);
    assertEquals("node_failed", tableOfFailedNode().body.get("code"));
    assertEquals(404, tableOfUnknownNode().status);
    assertEquals(400, get("/v1/workflows/wf-1/nodes/1:1/table?limit=abc").status);

- [ ] **Step 2: Run it and watch it fail**

    cd engine-native && ./mvnw -q -Dtest=StatusCodeContractTest test

Expected: FAIL (500/400 instead of 409/404).

- [ ] **Step 3: Implement** the four mappings; update docs; keep `400` for out-of-range port only.
- [ ] **Step 4: Run it and watch it pass** (same command).
- [ ] **Step 5: Commit**

    git add -A && git commit -m "feat(api): pin 409/404/400 status codes for workflow and node errors"

### Task 3: Read timeout for `/table` and `/profile`

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/NativeService.java` (Settings + env)
- Modify: `engine-native/src/main/java/com/dap/nativeengine/engine/DuckDbSession.java`
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/DataHandlers.java`
- Test: `engine-native/src/test/java/com/dap/nativeengine/api/MaterializationTimeoutTest.java`

**Interfaces:**
- Consumes: `ApiException.timeout("query_timeout", ...)`; `DuckDbSession.interruptReads(String workflowId)`.
- Produces: `Settings.queryTimeoutSec()` default 60, env `DAP_QUERY_TIMEOUT_SEC`; `DuckDbSession.preview(sql, columns, limit, long timeoutMillis, String workflowId, String nodeId)` and `.profile(..., long timeoutMillis, workflowId, nodeId)` throwing 408 `query_timeout` with hint.

- [ ] **Step 1: Write the failing test** `MaterializationTimeoutTest.materializationTimesOut`

    // DAP_QUERY_TIMEOUT_SEC=1; two 10k-row csv_readers -> math_formula constant key -> joiner on that key (100M rows)
    Response r = client.get("/v1/workflows/" + wf + "/nodes/" + joinNode + "/table", timeoutMs = 10_000);
    assertEquals(408, r.status);
    assertEquals("query_timeout", r.body.get("code"));
    assertEquals(200, client.get("/v1/workflows/" + wf + "/nodes/" + readerNode + "/table").status);

- [ ] **Step 2: Run it and watch it fail** — before the fix the call exceeds the 10 s client timeout (FAIL, not hang).
- [ ] **Step 3: Implement**: run the materialization in a virtual thread, `future.get(timeout)`, on expiry call `interruptReads(workflowId)` and close the read connection, then throw `ApiException.timeout`. `DataHandlers` passes `settings.queryTimeoutSec()`.
- [ ] **Step 4: Run it and watch it pass**
- [ ] **Step 5: Commit** `fix(engine): bound /table and /profile with a query timeout`

### Task 4: Drop workflow tables on delete

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/engine/DuckDbSession.java`
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/WorkflowHandlers.java` (delete)
- Test: `engine-native/src/test/java/com/dap/nativeengine/api/WorkflowDeleteDropsTablesTest.java`

**Interfaces:**
- Produces: `void dropTablesForWorkflow(String workflowId)` — drops every table whose name starts with `csv_<workflowId>_` or `excel_<workflowId>_` (query `information_schema.tables`, quote identifiers).

- [ ] **Step 1: Write the failing test** — load a table named `csv_wf-1_1:1`, call `dropTablesForWorkflow("wf-1")`, assert `SELECT count(*) FROM information_schema.tables WHERE table_name LIKE "csv_wf-1_%"` is 0; integration: delete a run workflow, assert 200 and the next run of another workflow is unaffected.
- [ ] **Step 2: Run it and watch it fail.**
- [ ] **Step 3: Implement** the drop and call it from `WorkflowHandlers.delete` after cancelling jobs.
- [ ] **Step 4: Run it and watch it pass.**
- [ ] **Step 5: Commit** `fix(engine): drop workflow tables when a workflow is deleted`

### Task 5: Session TTL, LRU cap and endpoints

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/ServiceState.java`
- Modify: `engine-native/src/main/java/com/dap/nativeengine/NativeService.java` (routes, Settings, idle watchdog tick)
- Modify: `api/openapi.yaml`, `docs/API.md`
- Test: `engine-native/src/test/java/com/dap/nativeengine/api/SessionLifecycleTest.java`

**Interfaces:**
- Produces: `Settings.maxSessions()` (`DAP_MAX_SESSIONS`, 64), `Settings.sessionTtlSec()` (`DAP_SESSION_TTL_SEC`, 1800); `ServiceState.session(id)` updates `lastAccess`; `ServiceState.evictIdle(long nowMillis)` closes TTL-expired sessions and LRU-evicts above the cap; `boolean closeSession(String id)`; `GET /v1/sessions` -> `{count, sessions:[{id, lastAccess, workflows}]}`; `DELETE /v1/sessions/{id}` -> `{ok:true, id}` or 404. Eviction cancels the session jobs, deletes its workflows and closes its `DuckDbSession`.

- [ ] **Step 1: Write the failing test** `SessionLifecycleTest` — 3 sessions via `X-DAP-Session`, delete one, assert 200, its workflow 404, health `sessions == 2`; with `DAP_MAX_SESSIONS=4` create 6 sessions and assert `sessions <= 4`; with `DAP_SESSION_TTL_SEC=1` create a session, sleep 1.2 s, call `evictIdle`, assert gone.
- [ ] **Step 2: Run it and watch it fail.**
- [ ] **Step 3: Implement** timestamps, eviction, routes; call `evictIdle` from the existing watchdog tick.
- [ ] **Step 4: Run it and watch it pass.**
- [ ] **Step 5: Commit** `feat(engine): evict idle sessions and expose session endpoints`

### Task 6: Bounded job retention

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/exec/JobManager.java`
- Modify: `engine-native/src/main/java/com/dap/nativeengine/NativeService.java` (Settings)
- Modify: `api/openapi.yaml`, `docs/API.md`
- Test: `engine-native/src/test/java/com/dap/nativeengine/exec/JobRetentionTest.java`

**Interfaces:**
- Produces: `Settings.maxRetainedJobs()` (`DAP_MAX_RETAINED_JOBS`, 200); `JobManager(WorkflowStore, Executors, int maxRetained)`; after a job becomes terminal, drop the oldest terminal jobs above the cap (never active ones); `totalCount()` reflects the cap.

- [ ] **Step 1: Write the failing test** — submit 250 trivial jobs sequentially, assert `totalCount() == 200`, `GET /v1/jobs` returns 200, the oldest job id returns 404.
- [ ] **Step 2: Run it and watch it fail.**
- [ ] **Step 3: Implement** the retention sweep in `complete*` paths and the constructor parameter.
- [ ] **Step 4: Run it and watch it pass.**
- [ ] **Step 5: Commit** `feat(engine): cap retained job history`

### Task 7: Ghost node and CSV empty-string hint

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/WorkflowHandlers.java` (`addNode`)
- Modify: `engine-native/src/main/java/com/dap/nativeengine/csv/CsvTableReader.java` (convert catch)
- Modify: `engine-native/src/main/resources/specs/dap.csv_reader.json` (description)
- Tests: `engine-native/src/test/java/com/dap/nativeengine/api/AddNodeRollbackTest.java`, `engine-native/src/test/java/com/dap/nativeengine/csv/QuotedEmptyHintTest.java`

**Interfaces:**
- Produces: `addNode` validates `settings` keys against the spec before `workflow.addNode`, so a 400 leaves the node count unchanged; `CsvTableReader` parse error for a quoted empty string appends `; quoted empty string — set emptyStringIsNull=true or type the column as Utf8`.

- [ ] **Step 1: Write failing tests** — (a) POST node with `{"keys": [], "typo": 1}`, expect 400 and `GET /nodes` count unchanged; (b) CSV `a,b` / `1,""` with `columns: [{a Int64}, {b Float64}]`, expect error text contains `emptyStringIsNull`.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement** validation-before-add and the hint.
- [ ] **Step 4: Run them and watch them pass.**
- [ ] **Step 5: Commit** `fix(api): validate node settings before adding and hint at emptyStringIsNull`

### Task 8: Engine generation and restart notice

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/NativeService.java` (health)
- Modify: `adapters/mcp/dap_mcp.py`
- Test: `engine-native/src/test/java/com/dap/nativeengine/api/HealthGenerationTest.java`, `tests/test_mcp_restart_notice.py`

**Interfaces:**
- Produces: `GET /v1/health` adds `generation` (UUID created at process start, stable for the process); the adapter remembers the last generation and prefixes the next error with `engine restarted; workflows lost: ` when it changes.

- [ ] **Step 1: Write failing tests** — (a) health body has a non-empty `generation` and it is stable across two calls; (b) adapter test kills the child engine, sends a tool call, asserts the error message contains the restart note.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement** the field and the adapter tracking (store generation in `ensure_service`).
- [ ] **Step 4: Run them and watch them pass.**
- [ ] **Step 5: Commit** `feat(engine): expose boot generation and warn about engine restarts`

### Task 9: Concurrency fixes

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/engine/DuckDbSession.java` (`interruptReads` under `readsLock`)
- Modify: `engine-native/src/main/java/com/dap/nativeengine/exec/JobManager.java` (`forgetWorkflow`)
- Modify: `engine-native/src/main/java/com/dap/nativeengine/api/WorkflowHandlers.java` (call `forgetWorkflow` on delete)
- Modify: `engine-native/src/main/java/com/dap/nativeengine/exec/Dag.java` (bounded close)
- Tests: `engine-native/src/test/java/com/dap/nativeengine/engine/ReadInterruptRaceTest.java`, `engine-native/src/test/java/com/dap/nativeengine/exec/WorkflowLockCleanupTest.java`, `engine-native/src/test/java/com/dap/nativeengine/exec/DagCloseBoundedTest.java`

**Interfaces:**
- Produces: `JobManager.forgetWorkflow(String workflowId)` removes the `workflowLocks` entry; `Dag.close()` uses `shutdownNow()` + `awaitTermination(5, SECONDS)`; `interruptReads` takes `readsLock` before iterating open reads.

- [ ] **Step 1: Write failing tests** — (a) interrupt a read right after `openRead` and assert the read does not complete after cancel; (b) delete a workflow and assert its lock is gone; (c) a node task that ignores interrupts does not block `Dag.close()` for more than 5 s.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Implement** the three fixes.
- [ ] **Step 4: Run them and watch them pass.**
- [ ] **Step 5: Commit** `fix(engine): close read-interrupt, lock-cleanup and DAG-close races`

### Task 10: Fault-injection and soak suite

**Files:**
- Create: `engine-native/src/test/java/com/dap/nativeengine/api/SoakDailyLoopTest.java` (`@Tag("soak")`)
- Create: `engine-native/src/test/java/com/dap/nativeengine/api/FaultInjectionTest.java`
- Modify: `Makefile` (`soak` target)
- Modify: `.github/workflows/ci.yml` (nightly soak job, `workflow_dispatch` + schedule)

**Interfaces:**
- Produces: `make soak` runs `./mvnw -q -Dgroups=soak test`; `FaultInjectionTest` covers cancel mid-ingest, timeout, missing file, malformed CSV, cross-join under timeout, engine restart; `SoakDailyLoopTest` runs 200 x create -> run -> table -> delete and asserts `sessionCount() == 1`, `jobs <= 200`, heap delta < 300 MB.

- [ ] **Step 1: Write the failing tests** (they fail because the harness targets do not exist yet or assertions are not met).
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Add the `soak` Makefile target and CI job; adjust assertions only to measured, justified bounds.**
- [ ] **Step 4: Run `make soak` and `./mvnw -q -Dtest=FaultInjectionTest test`; both pass.**
- [ ] **Step 5: Commit** `test(engine): add fault-injection and soak coverage`

### Task 11: DuckDB extension directory and installer (independent, can run in parallel with Task 10)

**Files:**
- Modify: `engine-native/src/main/java/com/dap/nativeengine/NativeService.java` (Settings, `--install-extensions` main mode)
- Modify: `engine-native/src/main/java/com/dap/nativeengine/engine/DuckDbSession.java` (apply `extension_directory`)
- Test: `engine-native/src/test/java/com/dap/nativeengine/engine/DuckDbExtensionSettingsTest.java`

**Interfaces:**
- Produces: `Settings.duckdbExtensionDirectory()` (`DAP_DUCKDB_EXTENSION_DIR`, default `$DAP_HOME/build/duckdb-ext`, else `~/.dap/extensions`); `DuckDbSession` executes `SET extension_directory` on open and never relies on autoinstall; `java -jar dap-native.jar --install-extensions sqlite postgres httpfs` installs into that directory and exits 0/1; `GET /v1/health` adds `extensions: {installed: [names]}`.

- [ ] **Step 1: Write the failing test** — `current_setting("extension_directory")` equals the configured path; health lists the installed names; `--install-extensions` into a temp dir creates `v1.5.6/<platform>/sqlite_scanner.duckdb_extension` (tag `network`, skipped when offline).
- [ ] **Step 2: Run it and watch it fail.**
- [ ] **Step 3: Implement** the setting, the CLI mode and the health field.
- [ ] **Step 4: Run it and watch it pass** (network-tagged test may be skipped offline).
- [ ] **Step 5: Commit** `feat(engine): add a configurable DuckDB extension directory and installer`
