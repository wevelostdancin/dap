#!/bin/sh
# GraalVM native-image spike for the DAP engine.
#
# This is a timeboxed feasibility probe, not a release build. It is expected to
# fail until the DuckDB JNI and Jetty/Jackson reflection configuration is
# complete. See docs/NATIVE-IMAGE.md for the go/no-go criteria.
#
# Usage:
#   scripts/native-image.sh [--jar PATH] [--out DIR] [--config-dir DIR]
#
# Requires GraalVM CE 21 with native-image on PATH (or GRAALVM_HOME/bin).
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
jar="$root/engine-native/target/dap-native.jar"
out="$root/build/native-image"
config="$root/build/native-image/config"

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) jar=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --config-dir) config=$2; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "native-image: unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -n "${GRAALVM_HOME:-}" ]; then
  PATH="$GRAALVM_HOME/bin:$PATH"
  export PATH
fi

if ! command -v native-image >/dev/null 2>&1; then
  echo "native-image: GraalVM native-image not found; set GRAALVM_HOME or install GraalVM CE 21" >&2
  exit 2
fi

if [ ! -f "$jar" ]; then
  echo "native-image: jar not found: $jar (run make build-native)" >&2
  exit 2
fi

mkdir -p "$out" "$config"

echo "native-image: tracing agent -> $config"
# The tracing agent records reflection/JNI/resource use while the engine runs a
# small workload. Start the engine with the agent attached, hit health and one
# analyze call, then shut it down.
port=7899
DAP_PORT=$port DAP_HOME="$root" DAP_WARMUP=0 \
  java -agentlib:native-image-agent=config-output-dir="$config" \
       -jar "$jar" >"$out/trace.log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT
i=0
while [ $i -lt 200 ]; do
  if curl -fsS -m 1 "http://127.0.0.1:$port/v1/health" >/dev/null 2>&1; then break; fi
  i=$((i + 1)); sleep 0.1
done
if ! curl -fsS -m 1 "http://127.0.0.1:$port/v1/health" >/dev/null 2>&1; then
  echo "native-image: engine did not start; see $out/trace.log" >&2
  exit 1
fi
curl -fsS -m 5 -X POST "http://127.0.0.1:$port/v1/shutdown" >/dev/null 2>&1 || true
wait "$pid" 2>/dev/null || true
trap - EXIT

echo "native-image: building -> $out/dap-engine"
native-image \
  --no-fallback \
  -H:+ReportExceptionStackTraces \
  -H:ConfigurationFileDirectories="$config" \
  -H:IncludeResources='libduckdb_java\.so.*' \
  --enable-url-protocols=http,https \
  -jar "$jar" \
  "$out/dap-engine"

echo "native-image: built $out/dap-engine"
"$out/dap-engine" --version >/dev/null 2>&1 || true
