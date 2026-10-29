#!/bin/bash
# Vendoring: копирует слим-рантайм KNIME 5.12 в DAP.
# Нужен ТОЛЬКО сопровождающему при обновлении версии движка.
# Обычная сборка/запуск DAP установленный KNIME не требует.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KNIME_APP="${KNIME_APP:-/Applications/KNIME 5.12.0.app/Contents}"
SRC="$KNIME_APP/Eclipse/plugins"
OUT="${DAP_RUNTIME_OUT:-$ROOT/runtime/knime}"

if [ ! -d "$SRC" ]; then
  echo "KNIME plugins not found: $SRC" >&2
  echo "set KNIME_APP to a KNIME 5.12 app bundle" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/plugins" "$OUT/configuration"

EXCLUDES=(
  'com.equo.chromium*'
  'org.knime.ui.js*'
  'org.knime.product*'
  'org.knime.workbench.hubview*'
  'org.knime.workbench.editor*'
  'org.knime.workbench.docs*'
  'org.knime.workbench.search*'
  'org.knime.workbench.workflowcoach*'
  'org.eclipse.equinox.launcher.cocoa*'
  'org.knime.js.core*'
  'org.knime.js.cef*'
  'org.knime.js.quickforms*'
  'org.knime.js.swt*'
  'org.knime.js.views*'
  'org.knime.dynamic.js*'
  'org.knime.ext.js*'
  'org.knime.ai.assistant*'
  'com.knime.executor*'
  'com.knime.enterprise*'
  'com.knime.server*'
  'org.knime.cloud*'
  'org.knime.ext.google*'
  'org.knime.binary.jre*'
  'org.knime.reporting2*'
)
args=()
for e in "${EXCLUDES[@]}"; do args+=(--exclude="$e"); done
rsync -a "${args[@]}" "$SRC/" "$OUT/plugins/"

# Фронтенд core.ui не нужен.
rm -rf "$OUT"/plugins/org.knime.core.ui_*/js-src "$OUT"/plugins/org.knime.core.ui_*/js-src*

if [ -f "$ROOT/build/com.dap.engine_1.0.0.jar" ]; then
  cp "$ROOT/build/com.dap.engine_1.0.0.jar" "$OUT/plugins/"
fi

python3 "$ROOT/scripts/gen-bundles-info.py" >/dev/null
du -sh "$OUT"
echo "bundles: $(ls "$OUT/plugins" | wc -l | tr -d ' ')"
echo "NOTE: runtime contains GPLv3 KNIME bundles; see NOTICE."
