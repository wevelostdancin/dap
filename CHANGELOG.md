# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). DAP is licensed under [Apache-2.0](LICENSE).

## [Unreleased]

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
