#!/bin/bash
# Скачивает vendored-рантайм DAP (архив с runtime/knime) и распаковывает в репозиторий.
# Используется CI, когда runtime не хранится в git.
#   DAP_RUNTIME_URL=https://.../dap-runtime-1.0.0.tar.zst scripts/fetch-runtime.sh
#   scripts/fetch-runtime.sh https://.../runtime.tar.zst
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
URL="${1:-${DAP_RUNTIME_URL:-}}"
SHA="${DAP_RUNTIME_SHA256:-}"

if [ -z "$URL" ]; then
  echo "ERROR: runtime URL is empty (pass argument or DAP_RUNTIME_URL)" >&2
  exit 2
fi

TMP="$(mktemp -d /tmp/dap-runtime.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
ARCHIVE="$TMP/runtime"
echo "[fetch-runtime] downloading $URL"
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 --retry-delay 2 -o "$ARCHIVE" "$URL"
else
  wget -O "$ARCHIVE" "$URL"
fi

if [ -n "$SHA" ]; then
  if command -v shasum >/dev/null 2>&1; then
    GOT="$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')"
  else
    GOT="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
  fi
  if [ "$GOT" != "$SHA" ]; then
    echo "ERROR: sha256 mismatch: got $GOT expected $SHA" >&2
    exit 1
  fi
fi

case "$URL" in
  *.tar.zst|*.tzst) tar --zstd -xf "$ARCHIVE" -C "$ROOT" ;;
  *.tar.gz|*.tgz)   tar -xzf "$ARCHIVE" -C "$ROOT" ;;
  *.tar)            tar -xf "$ARCHIVE" -C "$ROOT" ;;
  *) echo "ERROR: unsupported archive extension: $URL" >&2; exit 1 ;;
esac

if [ ! -d "$ROOT/runtime/knime/plugins" ]; then
  echo "ERROR: archive did not produce runtime/knime/plugins" >&2
  exit 1
fi
echo "[fetch-runtime] OK: $ROOT/runtime/knime ($(ls "$ROOT/runtime/knime/plugins" | wc -l | tr -d ' ') plugins)"
