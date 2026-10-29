#!/bin/bash
# DAP engine: резидентный HTTP-сервис (127.0.0.1:PORT).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${DAP_JDK:-$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home}"
PORT="${DAP_PORT:-7800}"
BIND="${DAP_BIND:-127.0.0.1}"
RUNTIME="$ROOT/runtime/knime"
LAUNCHER=$(ls "$RUNTIME"/plugins/org.eclipse.equinox.launcher_*.jar | head -1)
exec "$JDK/bin/java" -Xmx"${DAP_HEAP:-3g}" -jar "$LAUNCHER" \
  -configuration "$RUNTIME/configuration" \
  -data "${DAP_DATA:-$ROOT/workspace}" \
  -application com.dap.engine.service \
  -port="$PORT" -bind="$BIND" \
  -catalog="$ROOT/catalog/catalog.json" \
  -schemas="$ROOT/catalog/schemas" \
  -curated="$ROOT/catalog/curated.json" \
  -jobTimeoutSec="${DAP_JOB_TIMEOUT:-600}" \
  -maxWorkflowsPerSession="${DAP_MAX_WORKFLOWS:-100}" \
  -consoleLog
