# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). DAP is licensed under [Apache-2.0](LICENSE).

## [Unreleased]

## [1.4.0] - 2026-11-23

### Added

- Ingest request options: `encoding` (auto-detection or an explicit charset),
  `allVarchar`, `decimalComma` and `sheet`. Ingest results now report the
  effective `encoding`, the Excel `sheet` that was read and all `sheets`.
  `decimalComma` only converts columns that actually use a decimal comma and
  keep identifiers, codes and long keys as text.
- MCP one-call tools pass the CSV/Excel read options through to ingest and
  return the sheet and encoding metadata.
- `dap_explore` reports the effective `sheet`, `sheets` and `encoding` of its
  source file.

### Changed

- An omitted or empty `delimiter` is auto-detected instead of defaulting to a
  comma; clients that relied on the comma default should pass `delimiter`
  explicitly.
- Ingest rebuilds open bounded DuckDB sessions and are capped by
  `DAP_INGEST_PARALLELISM`, including the dataset catalog path.

### Fixed

- CP1251 and other non-UTF CSV files are transcoded to UTF-8 before DuckDB,
  which only reads UTF-8/UTF-16/Latin-1.
- An omitted or empty `delimiter` now lets DuckDB detect the CSV dialect
  instead of assuming a comma.
- Legacy `.xls` (BIFF/OLE2) and `.xlsb` workbooks are rejected with a hint
  to save them as `.xlsx` or `.csv` and retry.
- Concurrent ingest rebuilds are bounded by `DAP_INGEST_PARALLELISM`
  (default 2), and ingest sessions inherit the engine's `memory_limit`,
  `threads` and `temp_directory`.
- Ingest errors report the underlying cause instead of starting with
  "Attempting to execute an unsuccessful or closed pending query result".
- An engine restart is reported as "engine restarted (idle shutdown or crash);
  workflows lost: " with `data.restarted=true`.

## [1.3.1] - 2026-11-23

### Added

- The `dap-dsh` plugin is published on npm (`dap-dsh@1.3.1`) and can be installed from the DSH plugin manager.

### Changed

- README rewritten in English and brought in line with 1.3.1: plugin install from the registry, 33 MCP tools, 31 API paths, 970 JUnit tests, 35 golden workflows.

### Fixed

- `dap doctor --client dsh` recognises a `dap-dsh` bundle install and no longer
  reports a missing manual `mcp-manager` entry in the profile patch.

## [1.3.0] - 2026-11-23

### Added

- jlink runtime archive: a self-contained distribution that runs without a system JDK.
- `install.sh` for one-command installation: HTTPS download, pinned SHA256 verification, idempotent PATH setup, and no `sudo`.
- `dap` CLI for engine, MCP, doctor, and recipe workflows, layered over the existing MCP adapter.
- Eight bundled recipes: `data_quality`, `cohort_analysis`, `ab_test`, `time_series`, `segmentation`, `regression`, `funnel`, and `exec_report`.
- Seven new MCP prompts (ten in total); the three existing prompts are preserved.
- CI gates for accuracy, benchmark, packaging, and documentation, with required checks on protected branches.
- DSH plugin (`dap-dsh`) that launches the installed `dap` binary from the agent host.

### Changed

- The project license is now **Apache-2.0** (permissive, OSI-approved); all new files, the DSH plugin and the documentation use the same SPDX identifier.
- Installation now uses `install.sh` and the `dap` CLI instead of a manual JDK and jar setup; `dap-mcp install` remains compatible.
- Documentation is split into installation, packaging, and releasing guides; open-source contribution, conduct, security, and changelog files were added.

### Fixed

- None in this release.
