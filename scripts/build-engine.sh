#!/bin/bash
# Собирает DAP engine из исходников против vendored-рантайма (KNIME не нужен).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${DAP_JDK:-$HOME/Projects/tools/jdk-21.0.13+9/Contents/Home}"
RUNTIME="$ROOT/runtime/knime"
if [ ! -d "$RUNTIME/plugins" ]; then
  echo "runtime not found: $RUNTIME (see scripts/vendor-runtime.sh)" >&2
  exit 1
fi
CP=$(find "$RUNTIME/plugins" -maxdepth 2 -name "*.jar" | tr '\n' ':')
rm -rf "$ROOT/build/classes" && mkdir -p "$ROOT/build/classes"
SOURCES=()
while IFS= read -r file; do
  SOURCES+=("$file")
done < <(find "$ROOT/engine/src/main/java" -name "*.java")
if [ "${#SOURCES[@]}" -eq 0 ]; then
  echo "no Java sources under engine/src/main/java" >&2
  exit 1
fi
"$JDK/bin/javac" -nowarn -d "$ROOT/build/classes" -cp "$CP" "${SOURCES[@]}"
"$JDK/bin/jar" cfm "$ROOT/build/com.dap.engine_1.0.0.jar" \
  "$ROOT/engine/META-INF/MANIFEST.MF" \
  -C "$ROOT/build/classes" . -C "$ROOT/engine" plugin.xml
# Убираем прежний бандл, если он был.
rm -f "$RUNTIME/plugins"/com.example.knime.headless_*.jar
cp "$ROOT/build/com.dap.engine_1.0.0.jar" "$RUNTIME/plugins/"
python3 "$ROOT/scripts/gen-bundles-info.py"
echo "built: $ROOT/build/com.dap.engine_1.0.0.jar"
