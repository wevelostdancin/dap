# DAP MCP Service — Phase 3: Adaptability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpower-subagent-driven-development (recommended) or superpower-executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let an agent adapt pipelines and tools itself: MCP resources for the catalog and recipes, prompts for common analyses, recipe tools and a `dap_sql` tool.

**Architecture:** The existing stdio adapter gains MCP `resources` and `prompts` handlers and four tools; recipes are saved workflows (`dap-native-workflow/1` plus a `recipe` block) stored under `$DAP_HOME/recipes/` and executed through the existing `/v1` load/run endpoints.

**Tech Stack:** Python 3 stdlib adapter, existing Java engine `/v1`, JSON-RPC 2.0.

**Spec:** `docs/superpowers/specs/2026-11-15-dap-mcp-service-design.md` (§10)

## Global Constraints

- Python stdlib only; no new Maven dependencies.
- Resources and prompts must be advertised in `initialize` capabilities, otherwise Hermes and DSH register no wrappers.
- Recipe files are plain workflow JSON with an extra `recipe` object; they load through the existing `/v1/workflows/load` path.
- Commits conventional, English; `make check` green.
- Worktree `.worktrees/p5-recipes`, branch `p5/recipes`; depends on phase 1 (CLI, error codes) and phase 2 (`sql_query`).

## Review Focus

1. `resources/read` on an unknown recipe returns a JSON-RPC error, not a crash or empty content.
2. `prompts/get` with missing required arguments returns a clear error.
3. `dap_sql` rejects DDL and multiple statements before reaching the engine.
4. `dap_recipe_run` with a dataset parameter binds it without editing the stored recipe.
5. Two concurrent recipe runs do not share mutable state.

---

### Task A1: MCP resources for catalog and recipes

**Files:**
- Modify: `adapters/mcp/dap_mcp.py` (`initialize` capabilities, `resources/list`, `resources/read`)
- Create: `adapters/mcp/resources.py` (URI parsing and rendering)
- Test: `tests/test_mcp_resources.py`

**Interfaces:**
- Produces: `initialize` advertises `{"resources": {"listChanged": false}}`; `resources/list` returns `dap://catalog` (JSON of 44+ node ids, names, categories) and `dap://recipes` (names); `resources/read` returns `text/plain` JSON for `dap://catalog`, `dap://recipes`, `dap://recipes/{name}` (workflow JSON with the `recipe` block); unknown URI -> JSON-RPC error `-32602` with the URI.

- [ ] **Step 1: Write failing tests** - initialize capabilities, list contains both URIs, read catalog parses, unknown recipe errors.
- [ ] **Step 2: Run and watch fail.**
- [ ] **Step 3: Implement** the handlers and URI parsing.
- [ ] **Step 4: Run and watch pass.**
- [ ] **Step 5: Commit** `feat(mcp): expose catalog and recipes as MCP resources`

### Task A2: Prompts for common analyses

**Files:**
- Modify: `adapters/mcp/dap_mcp.py` (`prompts/list`, `prompts/get`), `adapters/mcp/resources.py`
- Test: `tests/test_mcp_prompts.py`

**Interfaces:**
- Produces: `initialize` advertises `{"prompts": {"listChanged": false}}`; prompts `analyze_csv` (args `path`, optional `question`), `quality_check` (args `path`), `profile_report` (args `path`, optional `output`); `prompts/get` returns a user message with the exact pipeline steps (ingest -> profile -> optional sql/report) and the passed arguments interpolated; missing required argument -> JSON-RPC error.

- [ ] **Step 1: Write failing tests** - list has three prompts; get returns a non-empty message containing the path; missing arg errors.
- [ ] **Step 2: Run and watch fail.**
- [ ] **Step 3: Implement** the handlers and templates.
- [ ] **Step 4: Run and watch pass.**
- [ ] **Step 5: Commit** `feat(mcp): add analysis prompts`

### Task A3: Recipe tools and `dap_sql`

**Files:**
- Modify: `adapters/mcp/dap_mcp.py` (tool schemas and handlers)
- Create: `adapters/mcp/recipes.py` (library read/write, parameter binding)
- Test: `tests/test_mcp_recipes.py`, `tests/test_mcp_sql.py`

**Interfaces:**
- Produces: `dap_recipe_list()` -> names and descriptions; `dap_recipe_save(name, workflow)` -> writes `$DAP_HOME/recipes/<name>.json` (rejects path separators); `dap_recipe_run(name, dataset?, params?)` -> loads the workflow, applies `params` to node settings, runs and returns the final table; `dap_sql(workflow, node?, sql)` -> calls `/v1` with a `dap.sql_query` node and returns the table; `dap_sql` rejects DDL/multi-statement with a JSON-RPC error before the HTTP call.

- [ ] **Step 1: Write failing tests** - save/list/run round-trip with a temp `DAP_HOME`; params override a setting; `dap_sql` rejects `DROP TABLE` and `SELECT 1; SELECT 2`.
- [ ] **Step 2: Run and watch fail.**
- [ ] **Step 3: Implement** the library and tools.
- [ ] **Step 4: Run and watch pass.**
- [ ] **Step 5: Commit** `feat(mcp): add recipe tools and dap_sql`

### Task A4: Docs and end-to-end scenario

**Files:**
- Modify: `docs/MCP.md` (resources, prompts, recipes, `dap_sql`)
- Create: `tests/mcp_recipe_e2e.py`, `Makefile` (`mcp-recipe-e2e`)

**Interfaces:**
- Produces: an e2e scenario that ingests a CSV, reads `dap://recipes`, runs a saved recipe or `dap_sql`, and returns a table plus a markdown report in <=3 agent tool calls; documented in `docs/MCP.md`.

- [ ] **Step 1: Write the failing e2e** (target missing).
- [ ] **Step 2: Run and watch fail.**
- [ ] **Step 3: Implement** the docs and Makefile target; make the scenario pass.
- [ ] **Step 4: Run and watch pass.**
- [ ] **Step 5: Commit** `docs(mcp): document resources, prompts and recipes`
