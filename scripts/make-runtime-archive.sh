#!/bin/bash
# Создаёт архив vendored-рантайма для CI/релизов (runtime/knime).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${DAP_RUNTIME_ARCHIVE:-$ROOT/build/dap-runtime-1.0.0.tar.zst}"
[ -d "$ROOT/runtime/knime/plugins" ] || { echo "ERROR: runtime/knime/plugins missing" >&2; exit 1; }
command -v zstd >/dev/null 2>&1 || { echo "ERROR: zstd not installed" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
echo "[runtime-archive] packing runtime/knime -> $OUT"
tar -C "$ROOT" -cf - runtime/knime | zstd -19 -T0 -o "$OUT"
ls -lh "$OUT"
if command -v shasum >/dev/null 2>&1; then
  echo "sha256: $(shasum -a 256 "$OUT" | awk '{print $1}')"
fi
