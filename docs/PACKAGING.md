# Packaging

DAP is shipped as a self-contained archive: one unpacked directory with a
minimal JRE, a pinned Python interpreter, the native jar and the MCP adapter.
No JDK, no Python and no Maven are needed on the user machine.

## Archive layout

```text
dap-<version>-<os>-<arch>/
  bin/dap                 unified entry point
  bin/dap-engine          shortcut for "dap engine"
  bin/dap-mcp             shortcut for "dap mcp"
  lib/dap-native.jar      native engine
  runtime/                jlink JRE (Java 21 modules only)
  python/                 python-build-standalone 3.12.16 (stripped)
  adapters/mcp/*.py       MCP adapter, CLI, recipes and resources
  api/openapi.yaml        /v1 contract, used by `dap mcp version`
  recipes/                shipped recipes, when present
  examples/               demo data, when present
  VERSION
  LICENSE, NOTICE, README.md
  SHA256SUMS
dap-<version>-<os>-<arch>.tar.gz
dap-<version>-<os>-<arch>.tar.gz.sha256
```

`os` is `macos` or `linux`; `arch` is `arm64` or `x64`.
`engine-native/target/dap-native.jar` inside the archive is a relative
symlink to `lib/dap-native.jar`: the shared MCP CLI doctor uses that path.

## Build

Requirements on the build machine: JDK 21 (`DAP_JDK` or `JAVA_HOME`), curl,
tar, shasum or sha256sum, and Python 3 with the standard library.
The JDK is only used at build time to run jdeps and jlink.

```bash
make test-package       # pure unit tests, no jlink and no network
make runtime            # jlink JRE -> build/package/runtime
make python-runtime     # pinned Python -> build/tools/python (once)
make package            # build jar + both runtimes + archive
```

Useful variants:

```bash
python3 scripts/package.py --skip-python              # system Python, no download
python3 scripts/package.py --skip-runtime             # inspect layout only
python3 scripts/package.py --python-runtime DIR       # preinstalled Python
python3 scripts/package.py --runtime DIR              # preinstalled jlink runtime
python3 scripts/package.py --version 1.3.0 --keep     # reuse an existing stage
DAP_BUNDLE_PYTHON=0 make package                      # same as --skip-python
```

The staging directory stays in `build/package/`; `--keep` reuses it instead
of recreating it. `SHA256SUMS` covers every regular file in the stage except
itself. Bytecode caches are stripped and disabled at runtime so the archive
stays byte-for-byte stable after use. The `.sha256` file next to the archive
contains the archive digest.

## Verify

```bash
bash -n scripts/jlink-runtime.sh scripts/python-runtime.sh scripts/dap.sh
python3 -m unittest tests.test_package -v
./build/package/dap-*/bin/dap version
cd build/package && shasum -a 256 -c dap-*/SHA256SUMS
```

Runtime smoke test with the bundled JRE:

```bash
DAP_PORT=7899 ./build/package/dap-*/bin/dap engine &
curl -fsS http://127.0.0.1:7899/v1/health
curl -fsS -X POST http://127.0.0.1:7899/v1/shutdown
```

`dap doctor` runs the full preflight (jar, engine, MCP preflight, client
config and optional DuckDB extensions) against the unpacked directory.
`DAP_HOME` defaults to the archive root; `DAP_JAVA_OPTS` and `DAP_HEAP`
are forwarded to the engine. The launcher sets `JAVA_HOME` to the bundled
runtime so the MCP CLI starts the same Java binary.

## Release requirements

- Build the archive on macOS arm64, macOS x64, Linux arm64 and Linux x64.
- Pin python-build-standalone 3.12.16+20261110 and verify its SHA256;
  a mismatch must fail the build.
- Publish the `.tar.gz` and its `.sha256` together.
- `VERSION`, `api/openapi.yaml` and the MCP adapter must agree.
- No CJK characters in documentation or metadata.
- Verify `dap doctor`, `/v1/health` and at least one recipe on each platform
  before tagging the release.

The installer and the end-to-end installation tests are maintained
separately; the archive itself never runs an installer or writes outside
its own directory.
