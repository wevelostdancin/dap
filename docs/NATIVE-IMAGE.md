# Native-image spike

GraalVM native-image is a **timeboxed feasibility probe**, not a release
artifact. The shipping archive uses a jlink runtime (see
[PACKAGING.md](PACKAGING.md)); native-image is only promoted if it passes the
criteria below.

## Why it is not the default

The engine is a shaded jar on the classpath. Under native-image the hard parts
are:

- **DuckDB JDBC** extracts `libduckdb_java.so` from the jar and loads it through
  JNI; the library and its JNI registrations must be embedded and configured.
- **Javalin/Jetty** uses reflection, `ServiceLoader` and WebSocket classes.
- **Jackson** databind needs reflection configuration for every DTO.
- **Arrow/Netty** relies on `sun.misc.Unsafe`, direct buffers and platform
  checks that need run-time initialization.

The tracing agent in `scripts/native-image.sh` records the configuration while
the engine serves a health check and one analyze call. It is a starting point,
not a complete config.

## Prerequisites

- GraalVM CE 21 (Temurin does not ship `native-image`).
- `GRAALVM_HOME` set, or `native-image` on `PATH`.
- 8 GiB RAM and ~2 GiB free disk for the build.
- A built jar: `make build-native`.

## Run

```bash
export GRAALVM_HOME=/path/to/graalvm-ce-21
scripts/native-image.sh
```

Outputs:

- `build/native-image/config/` — tracing-agent configuration.
- `build/native-image/dap-engine` — native executable.
- `build/native-image/trace.log` — engine log from the tracing run.

## Go/no-go criteria

Promote native-image only if **all** hold:

1. `build/native-image/dap-engine` starts and answers `GET /v1/health`.
2. The 25 golden workflows pass against the native binary.
3. `tests/reference/validate_dap.py --only describe,correlation,linear_regression`
   passes with the same tolerances as the JVM.
4. Cold start is <= 200 ms (median of 5 runs).
5. The binary is <= 120 MiB and needs no JDK at runtime.

If any criterion fails, record the blocker in this file and keep jlink as the
supported artifact. Do not ship a half-working native binary.

## CI

`.github/workflows/native-image.yml` is manual (`workflow_dispatch`) and
non-blocking. It installs GraalVM CE 21, builds the engine, runs the script and
uploads the log. A red run is a data point, not a release failure.

## Decision log

| Date | Result | Notes |
|---|---|---|
| 2026-11-23 | not run | Spike scaffolded; GraalVM not installed in the build environment. |
