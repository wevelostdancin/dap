#!/bin/bash
# Пересобирает каталог и предрасчитывает схемы всех узлов.
# Нужен установленный JDK; сам KNIME не нужен (runtime уже vendored).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${DAP_JDK:-$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home}"
RUNTIME="$ROOT/runtime/knime"
LAUNCHER=$(ls "$RUNTIME"/plugins/org.eclipse.equinox.launcher_*.jar | head -1)
DATA="$(mktemp -d /tmp/dap-build.XXXXXX)"
trap 'rm -rf "$DATA"' EXIT

run_app() {
  "$JDK/bin/java" -Xmx3g -jar "$LAUNCHER" \
    -configuration "$RUNTIME/configuration" \
    -data "$DATA" -application "$1" "${@:2}" -consoleLog
}

mkdir -p "$ROOT/catalog/schemas"
echo "[catalog] rebuilding node catalog"
run_app com.dap.engine.catalog -out="$ROOT/catalog/catalog.json"
echo "[catalog] precomputing dialog schemas"
run_app com.dap.engine.audit \
  -catalog="$ROOT/catalog/catalog.json" \
  -schemas="$ROOT/catalog/schemas" \
  -curated="$ROOT/catalog/curated.json" \
  -out="$ROOT/catalog/schema-report.json"
python3 - <<PY
import json
r=json.load(open("$ROOT/catalog/schema-report.json"))
print("[catalog] nodes=%s core=%s available=%s" % (r.get("nodes"), r.get("core"), r.get("available")))
PY
