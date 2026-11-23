# Contributing to DAP

Thank you for contributing. DAP is a local analytics backend: one JVM process, an HTTP `/v1` API, and a stdio MCP adapter. This guide covers the build, checks, contribution conventions, and the process for adding a node.

## License and Developer Certificate of Origin

- DAP is licensed under [GPL-3.0-or-later](LICENSE). See [NOTICE](NOTICE) for third-party notices. By contributing you agree that your contribution is licensed under the same terms.
- Every commit must carry a Developer Certificate of Origin (DCO) sign-off. Create it with `git commit -s`, which appends `Signed-off-by: Your Name <you@example.com>` to the commit message. By signing off you certify DCO 1.1: you have the right to submit the contribution under the project license and you grant permission to use it under those terms. See <https://developercertificate.org/>.
- Use the name and email associated with your GitHub account. Do not amend or remove an existing sign-off.
- Never commit secrets, credentials, personal data, build outputs, or generated cache files.

## Prerequisites

- JDK 21 (tested with Temurin 21.0.13). Set `DAP_JDK` to the JDK installation root: `export DAP_JDK=/path/to/jdk-21`. The Makefile default is `~/Projects/tools/jdk-21.0.13+9/Contents/Home`; override it on Linux or any other layout.
- Python 3.10+ for the test scripts and the MCP adapter. The adapter uses only the Python standard library; CI installs PyYAML for validation.
- macOS or Linux on x86_64 or aarch64. No external database, Docker, or long-running daemon is needed for the daily loop.

## Build

```bash
export DAP_JDK=/path/to/jdk-21
make build-native          # engine-native/target/dap-native.jar
```

`make build-native` runs the Maven wrapper with tests skipped. Use `make run-native` to start the engine on 127.0.0.1:7800 in the foreground, and `make dist` to stage a distributable tree with `SHA256SUMS`. The native library for DuckDB is bundled for the platform of the build; use `-Ddap.duckdb.native=all` for a cross-platform jar.

## Tests and checks

Run the relevant subset while iterating and the full suite before opening a pull request:

| Command | What it runs |
| --- | --- |
| `make test-native` | JUnit unit tests (`cd engine-native && ./mvnw test`) |
| `make goldens` | Golden suite; rebuilds the jar and starts/stops its own engine |
| `make goldens-validate` | Structural check of golden fixtures, no engine |
| `make mcp-e2e`, `make mcp-recipe-e2e` | MCP transport, tools, and recipe flows |
| `make lint` | `python3 scripts/check_repo.py` (static repository checks) |
| `make check` | JUnit, goldens, golden validation, MCP launch and e2e, lint |
| `make smoke` | Black-box HTTP smoke against a running engine (`DAP_BASE`) |
| `make coverage` | 69-task coverage suite (starts/stops its own engine) |

CI runs the `native` and `lint` jobs on every pull request, together with the `accuracy`, `bench`, `package`, and `docs` gates from the packaging design. `accuracy`, `bench`, `package`, and `lint` are required checks on protected branches. The `docs` gate rejects CJK characters in documentation and configuration files outside `tests/fixtures`.

## Style and scope

- Follow the surrounding code. Java sources live in `engine-native/src/main/java` and use four-space indentation; Python scripts target the standard library and run under `python3`.
- Keep diffs surgical: every changed line should trace to the request. Do not reformat unrelated code or remove someone else's dead code without asking.
- Do not add a dependency without discussion. Prefer the standard library and existing project utilities.
- Documentation and configuration are English only. Do not introduce CJK characters into `.md`, `.yml`, `.yaml`, or `.json` files outside `tests/fixtures`.
- User-visible changes add an entry under `Unreleased` in [CHANGELOG.md](CHANGELOG.md).

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) in English, one logical change per commit:

```text
type(scope): short imperative summary

Optional body explaining why the change is needed.

Signed-off-by: Your Name <you@example.com>
```

Recommended types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci`, `chore`. Useful scopes: `nodes`, `engine`, `api`, `mcp`, `packaging`, `plugin`, `docs`, `deps`. Examples: `feat(nodes): add fuzzy join node`, `fix(engine): close idle-shutdown race`, `docs(packaging): describe install script`. Use a `!` suffix and a `BREAKING CHANGE:` footer for incompatible changes. Squash fixup commits before review when practical.

## Branches and pull requests

- `main` is protected. Do not commit to it directly; branch from an up-to-date `main` as `feat/<topic>`, `fix/<topic>`, `docs/<topic>`, `test/<topic>`, `build/<topic>`, or `ci/<topic>`.
- Keep a branch focused on one topic, rebase or merge `main` regularly, and avoid force-pushing shared branches.
- Open a pull request against `main`. Explain what changed and why, link the related issue, and describe how you tested it.
- The PR checklist asks for a Conventional Commit title, DCO sign-off on every commit, `make check` passing, updated docs and changelog where relevant, no CJK text, and no secrets. Required CI checks must be green.
- Address review comments in new commits or an explicit squash when requested. Be kind and specific in reviews; the [Code of Conduct](CODE_OF_CONDUCT.md) applies to all interactions.

## Adding a node

A node consists of metadata, an executor, tests, a golden, and documentation:

1. Add the JSON spec `engine-native/src/main/resources/specs/dap.<type>.json` with the `dap.<type>` id, input/output ports, and `configSchema`. Add its filename to `engine-native/src/main/resources/specs/index.txt`; `NodeRegistry` reads that list and rejects missing or duplicate specs at startup.
2. Implement `NodeExecutor` in `engine-native/src/main/java/com/dap/nativeengine/nodes/<Name>Executor.java`. Validate configuration at run time and keep transformations lazy as in neighboring executors.
3. Register the executor in `NativeService.java` alongside the existing `.register(new ...)` calls.
4. Add unit tests under `engine-native/src/test/java/com/dap/nativeengine/nodes/`.
5. Add a workflow under `tests/goldens-native/workflows/` and its expected result in `tests/goldens-native/expected.json`. Run `make goldens-validate` and `make goldens`; put any new input fixture in `tests/fixtures`.
6. Add the node to [docs/NODES.md](docs/NODES.md), including behavior notes when the node has non-obvious semantics. If the `/v1` contract changes, update `api/openapi.yaml` and `docs/API.md` in the same change.
7. Run `make check` and update [CHANGELOG.md](CHANGELOG.md) under `Unreleased`.

Nodes must not perform network calls, read secrets, or write outside configured paths.

## Reporting problems

- Functional bugs: open an issue with the bug report template and a minimal reproducer.
- Ideas: open an issue with the feature request template.
- Security vulnerabilities: follow [SECURITY.md](SECURITY.md) and report privately, never in a public issue.
- Conduct concerns: use the contact in [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
