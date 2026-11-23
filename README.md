# DAP

[![license: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![version: 1.3.1](https://img.shields.io/badge/version-1.3.1-blue.svg)](CHANGELOG.md)
[![npm: dap-dsh](https://img.shields.io/npm/v/dap-dsh.svg?label=dsh%20plugin)](https://www.npmjs.com/package/dap-dsh)

**DAP** is a local analytics backend for agents: a single JVM process with an
HTTP `/v1` API and a stdio MCP adapter. An agent builds a workflow from JSON
nodes, runs it, and gets a table plus a column profile. Everything is computed
in-process; data never leaves the machine.

Engine: Javalin 6.7 + DuckDB 1.5.6 (in-memory) + Arrow 19.0 on Java 21.
Catalog: 66 `dap.*` nodes. Contract: 31 paths and 38 operations under `/v1`.
Version: **1.3.1**.

## What it is

- **Analytics on demand.** The adapter starts the engine on the first call;
  there is no daemon, no Docker and no external database in the daily path.
- **Two interfaces.** MCP (stdio, 33 `dap_*` tools) for agents and HTTP `/v1`
  for scripts and tests.
- **Nodes as data.** Every node is described by a JSON spec: id, ports,
  `configSchema`. The agent reads the settings schema and builds a graph
  without XML and without a GUI.
- **66 nodes.** IO (5 readers, multi-file/glob `file_reader`, SQL databases and
  file listing, 4 writers, `db_writer`), cleaning (filters, deduplication,
  value replacement, type casting, column order, outliers, regex/split,
  `text_normalize`), transformation (date/time, rules, binning, normalization,
  encoding, sampling, train/test, `calendar`, `geo_distance`), aggregation and
  shape (group_by with median/percentile, row aggregates, pivot/unpivot, window,
  top-N, joiner/concatenate/asof/cross, `fuzzy_join`), statistics (describe,
  value_counts, correlation with p-value/partial, hypothesis_test with post-hoc
  and non-parametric tests, linear/logistic regression, outlier_detect with
  DBSCAN/LOF/IF, cluster, PCA, resample_test, survival, ab_test, histogram,
  quantile), time series (resample, rolling, diff, date_range, `time_series`),
  SQL (`sql_query`), visualization and reports (`chart` with
  pie/boxplot/heatmap/map/facets, `chart_grid`, `markdown_report` in
  md/html/pdf/pptx), and quality (`data_quality`).
- **DuckDB inside.** Transformations are lazy SQL relations; data is
  materialized only when a table or profile is read.
- **Parquet cache.** `POST /v1/datasets` (the `dap_ingest` tool) converts
  CSV/Excel/JSON/Parquet once into `$DAP_HOME/build/cache` (or `~/.dap/cache`);
  later analyses read Parquet. The key covers path, size, mtime, format, read
  options and a content fingerprint (first and last 64 KiB), so `dap_ingest` is
  idempotent by content: an unchanged file returns `cached: true`, while a
  rewrite with the same size and mtime produces a new entry. A corrupt cache
  entry is recreated automatically; see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

DAP is not a GUI, a multi-user service or a cloud product. There is one user,
the data is local, and the interface is the agent's chat.

## Install

The release archive bundles a JRE and Python: **no JDK or Python is required**
on the target machine.

```bash
curl -fsSL https://raw.githubusercontent.com/wevelostdancin/dap/main/scripts/install.sh | sh
~/.dap/bin/dap doctor
```

For DSH, install the `dap-dsh` plugin from npm (**Settings -> Plugins** in
Desktop, or `dsh plugin --profile <name> add dap-dsh` for a CLI profile) — the
plugin registers the MCP server and adds startup diagnostics. Without the
plugin, `dap install --client dsh` writes the MCP entry by hand. Details, flags
and upgrades: [docs/INSTALL.md](docs/INSTALL.md) and
[docs/DSH-PLUGIN.md](docs/DSH-PLUGIN.md).

## Build from source

Building requires **JDK 21** (tested with Temurin 21.0.13, path in `DAP_JDK`)
and **Python 3.10+** for the MCP adapter and checks (standard library only).
macOS or Linux, x86_64/aarch64. The jar contains the DuckDB native library for
the build platform; for cross-platform packaging use
`-Ddap.duckdb.native=all`. No external database or Docker is needed.

```bash
export DAP_JDK=/path/to/jdk-21        # defaults to ~/Projects/tools/jdk-21.0.13+9/Contents/Home

make build-native                     # engine-native/target/dap-native.jar (~44 MiB)
make run-native                       # 127.0.0.1:7800, blocking process
make package                          # build/package/dap-<version>-<os>-<arch>.tar.gz
```

In another terminal:

```bash
curl -s http://127.0.0.1:7800/v1/health
```

```json
{"ok":true,"name":"DAP","version":"1.3.1","api":"1","nodes":66,"coreNodes":66,
 "workflows":0,"sessions":0,"jobs":0,"memory":{"usedMb":…,"maxMb":…}}
```

(`memory` is the current JVM `usedMb`/`maxMb`.)

Cold start is about 0.4–0.5 s from `java -jar` to the first `200`. Right after
`health` answers, the engine warms up in the background (`DAP_WARMUP`), so the
first call does not pay DuckDB and JIT initialization. Shutdown happens via
`POST /v1/shutdown`, a process signal, or idle timeout (`DAP_IDLE_TIMEOUT_SEC`).

Environment variables: `DAP_PORT` (7800), `DAP_BIND` (`127.0.0.1`; also
`localhost` and `0.0.0.0`), `DAP_MAX_WORKFLOWS` (100 per session),
`DAP_JOB_TIMEOUT` (600 s), `DAP_IDLE_TIMEOUT_SEC` (900 s; `0` disables
auto-shutdown), `DAP_QUERY_TIMEOUT_SEC` (60 s; materialization limit for
`/table` and `/profile`; `0` disables), `DAP_MAX_SESSIONS` (64; LRU eviction),
`DAP_SESSION_TTL_SEC` (1800 s; `0` disables TTL), `DAP_MAX_RETAINED_JOBS` (200;
cap on job history per session), `DAP_TABLE_MAX_LIMIT` (100,000; `limit` cap for
`/table`, above it a 400 `limit_too_large`), `DAP_WARMUP` (1; warm the engine in
a background thread at startup; `0` disables), `DAP_ALLOW_STALE_ENGINE` (0; the
MCP adapter compares the running engine version with `VERSION` and replaces an
older engine with the current jar; if the jar or Java is not ready the engine is
left running and the adapter returns an error; `1` connects to it as is),
`DAP_DUCKDB_MEMORY_LIMIT`, `DAP_DUCKDB_THREADS`, `DAP_DUCKDB_TEMP_DIR`,
`DAP_DUCKDB_EXTENSION_DIR` (DuckDB extensions; installed by
`--install-extensions`), `DAP_CACHE_DIR` (Parquet cache; defaults to
`$DAP_HOME/build/cache` when `DAP_HOME` is set, otherwise `~/.dap/cache`; in a
sandbox `$TMPDIR/dap-cache`).

## Example call

```bash
B=http://127.0.0.1:7800

# 1. workflow
curl -s -X POST $B/v1/workflows -H 'Content-Type: application/json' \
  -d '{"name":"demo"}'
# {"id":"wf-1","name":"demo","session":"default","nodes":0}

# 2. source
curl -s -X POST $B/v1/workflows/wf-1/nodes -H 'Content-Type: application/json' -d '{
  "factory":"dap.csv_reader",
  "settings":{"file":"/data/iris.csv","delimiter":",","header":true}
}'
# {"node":"1:1","name":"CSV Reader"}

# 3. filter
curl -s -X POST $B/v1/workflows/wf-1/nodes -H 'Content-Type: application/json' -d '{
  "factory":"dap.row_filter",
  "settings":{"logic":"and","where":[{"column":"species","op":"eq","value":"setosa"}]}
}'
# {"node":"1:2","name":"Row Filter"}

# 4. connect and run
curl -s -X POST $B/v1/workflows/wf-1/connect -H 'Content-Type: application/json' \
  -d '{"from":"1:1","to":"1:2"}'
curl -s -X POST $B/v1/workflows/wf-1/run -H 'Content-Type: application/json' -d '{}'
# {"ok":true,"durationMs":…,"workflow":"wf-1","nodes":[{"node":"1:1","name":"CSV Reader","state":"EXECUTED"}, …]}

# 5. table and profile
curl -s "$B/v1/workflows/wf-1/nodes/1:2/table?limit=5"
curl -s "$B/v1/workflows/wf-1/nodes/1:2/profile"
```

A node id is `<workflow number>:<index>`, for example `1:2` for `wf-1`. The
session comes from the `X-DAP-Session` header or `?session=`, defaulting to
`default`. For asynchronous runs use `POST .../run?async=true` or
`POST .../jobs` (202 + `job-N`).

## MCP adapter

The adapter is a thin stdio JSON-RPC 2.0 transport; computation stays in the
engine.

```bash
python3 adapters/mcp/dap_mcp.py
```

If the engine does not answer `/v1/health`, the adapter starts the jar itself
and waits up to 60 s; it never restarts a service that is already running.
MCP client configuration:

```json
{
  "mcpServers": {
    "dap": {
      "command": "python3",
      "args": ["/absolute/path/to/dap/adapters/mcp/dap_mcp.py"],
      "env": {
        "DAP_HOME": "/absolute/path/to/dap",
        "DAP_PORT": "7800",
        "DAP_SESSION": "agent"
      }
    }
  }
}
```

One-command setup (stdlib CLI, idempotent, backs up and supports `--dry-run`):

```bash
python3 adapters/mcp/dap_cli.py install --client hermes   # or --client dsh
python3 adapters/mcp/dap_cli.py doctor --client hermes
```

In an installed archive the same is shorter: `dap install --client dsh` and
`dap doctor --client dsh`. The DSH plugin is published as
[`dap-dsh`](https://www.npmjs.com/package/dap-dsh).

Distribution: `make package` → `build/package/dap-<version>-<os>-<arch>.tar.gz`
(a self-contained archive with JRE and Python); verify with `make package-e2e`.
Install from an archive with `scripts/install.sh`; see
[docs/PACKAGING.md](docs/PACKAGING.md).

Tools: `dap_search_nodes`, `dap_node_dialog_schema`, `dap_workflow_create`,
`dap_add_node`, `dap_connect`, `dap_run`, `dap_get_table`, `dap_profile_table`,
`dap_ingest`, `dap_save_workflow`, `dap_load_workflow`, `dap_shutdown` and the
rest of `dap_*` — 33 in total, plus MCP resources (`dap://catalog`,
`dap://recipes`) and prompts. The full list with arguments is in
[adapters/mcp/README.md](adapters/mcp/README.md).

## Checks

```bash
make test-native    # JUnit: 970 tests
make goldens        # 35 golden workflows on a dedicated :7811 engine
make coverage       # 69 taxonomy tasks through /v1 (currently 68/69, F6 is a non-goal)
make smoke          # black-box HTTP smoke against a running engine (DAP_BASE)
make mcp-e2e        # create -> add_node -> connect -> run -> table -> delete over MCP
make mcp-recipe-e2e # resources/prompts/recipes/dap_sql over MCP
make install-e2e    # install from dist/ into a temp HOME + stdio preflight
make package-e2e    # build the self-contained archive + install into a temp HOME
make recipe-library-e2e # all 8 recipes on demo data over MCP
make accuracy       # reference accuracy (27 cases, scipy/statsmodels/sklearn)
make bench-gate     # p6 bench with accuracy and token thresholds
make extensions     # sqlite/postgres/mysql/httpfs into $DAP_HOME/build/duckdb-ext
make soak           # 200 x create/run/table/delete
make lint           # static repository checks (including no CJK in docs)
make check          # test-native + goldens + test-python + mcp-e2e + lint
```

## Repository layout

```
engine-native/   engine: Java 21, specs/*.json, JUnit tests
adapters/mcp/    stdio MCP adapter (Python, stdlib only)
api/             /v1 contract: openapi.yaml
recipes/         8 ready-made recipes (dap-native-workflow/1 + recipe)
examples/        demo data for the recipes
dsh-plugin/      DSH bundle dap-dsh (MCP server + diagnostics)
docs/            INSTALL, PACKAGING, DSH-PLUGIN, ARCHITECTURE, NODES, QUALITY, ROADMAP
tests/           goldens-native/, native_smoke.py, mcp_e2e.py, package_e2e.py, bench.py
deploy/          optional Dockerfile (Docker is not needed for local work)
scripts/         install.sh, dap.sh, jlink/python runtime, package.py, checks
Makefile         build-native, package, test-native, goldens, check
```

## Documentation

- [docs/INSTALL.md](docs/INSTALL.md) — one-command install, upgrade, removal.
- [docs/PACKAGING.md](docs/PACKAGING.md) — jlink/Python runtime, archive build.
- [docs/RELEASING.md](docs/RELEASING.md) — tag, CI, GitHub Release, npm plugin.
- [docs/DSH-PLUGIN.md](docs/DSH-PLUGIN.md) — the `dap-dsh` plugin.
- [docs/NATIVE-IMAGE.md](docs/NATIVE-IMAGE.md) — GraalVM native-image spike.
- [recipes/README.md](recipes/README.md) — 8 scenarios for the agent.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — components, request lifecycle,
  memory and DuckDB resources, cancellation, MCP adapter.
- [docs/NODES.md](docs/NODES.md) — 66 nodes: purpose, config, JSON examples.
- [docs/QUALITY.md](docs/QUALITY.md) — unit tests, goldens, comparison rules,
  error contract, definition of done for a node.
- [docs/ROADMAP.md](docs/ROADMAP.md) — product plan and node pool.
- [docs/API.md](docs/API.md) — human-readable `/v1` contract;
  [docs/MCP.md](docs/MCP.md) — one-command setup, local Postgres/MySQL, the
  "two calls" scenario, tools and lifecycle;
  [api/openapi.yaml](api/openapi.yaml) — machine-readable.
- [docs/BENCH-P7.md](docs/BENCH-P7.md) — effect of changes A–E: reads, paging,
  reports, statistics on large data, MCP.
- [docs/ACCURACY.md](docs/ACCURACY.md) — reference statistics checks (scipy,
  statsmodels, sklearn, lifelines) and approximation limits.
- [docs/BENCH.md](docs/BENCH.md) — ingest, cold start and memory measurements;
  [docs/BENCH-P6.md](docs/BENCH-P6.md) — 10 large datasets, MCP overhead,
  accuracy and weak spots found.

## Limitations

- Single user, no authentication: the service listens on loopback and must not
  be exposed to a network without a proxy.
- State lives in memory: workflows, jobs and tables disappear when the process
  stops; `POST .../save` writes `workflow.json`, `POST /v1/workflows/load` reads
  it back. Only an explicitly saved `workflow.json` and the ingest Parquet cache
  remain on disk.
- Shutdown is explicit (`POST /v1/shutdown`, a process signal) or automatic on
  idle: `DAP_IDLE_TIMEOUT_SEC` (900 s by default, `0` disables) when no jobs are
  active.
- The catalog has 66 nodes; file sources and SQL follow a path allowlist
  (`DAP_ALLOWED_ROOTS`; defaults are home, tmp, DAP_HOME and cache; `~/.ssh`,
  `~/.dsh` and other secrets are always denied). `DAP_DENY_ROOTS` adds more.
  DuckDB extensions (sqlite/postgres/httpfs) are installed once by
  `make extensions` into `$DAP_HOME/build/duckdb-ext` and then work offline.
- Out-of-core by default: `parquet_reader` and `csv_reader` build lazy
  `read_parquet`/`read_csv`; `POST /v1/datasets` also converts CSV through
  `read_csv` without Arrow in the heap. `DAP_DUCKDB_MEMORY_LIMIT=512MB` and
  spilling to `$DAP_HOME/build/duckdb-tmp` are on by default. The ceiling is now
  disk, not `-Xmx`.

## Contributing

- [CONTRIBUTING.md](CONTRIBUTING.md) — build, tests, commit style, DCO, adding a node.
- [SECURITY.md](SECURITY.md) — private vulnerability reporting.
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) — community rules.
- [Issues](https://github.com/wevelostdancin/dap/issues) and
  [Discussions](https://github.com/wevelostdancin/dap/discussions) — questions and ideas.
- [CITATION.cff](CITATION.cff) — how to cite DAP.

## License

DAP is distributed under **Apache-2.0**; the text is in [LICENSE](LICENSE),
third-party components in [NOTICE](NOTICE). The engine is built from its own
code and the libraries listed in [engine-native/pom.xml](engine-native/pom.xml);
there is no warranty.
