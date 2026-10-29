#!/bin/bash
# Собирает OCI-образ DAP (linux/amd64 + linux/arm64) в build/dap-1.0.0.tar.
#   ./scripts/package-oci.sh                 # multi-arch OCI tar
#   ./scripts/package-oci.sh --single-arch   # только текущая платформа, docker --load
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${DAP_IMAGE:-dap:1.0.0}"
PLATFORMS="${DAP_PLATFORMS:-linux/amd64,linux/arm64}"
OUT="${DAP_OCI_OUT:-$ROOT/build/dap-1.0.0.tar}"
SINGLE_ARCH=0

usage() {
  cat <<'USAGE'
Usage: scripts/package-oci.sh [options]

  -s, --single-arch      собрать только linux/<host arch> и загрузить в docker (--load)
  -t, --tag TAG          тег образа (по умолчанию dap:1.0.0)
  -p, --platforms LIST   платформы для multi-arch (по умолчанию linux/amd64,linux/arm64)
  -o, --output PATH      путь OCI-архива (по умолчанию build/dap-1.0.0.tar)
  -h, --help             эта справка
USAGE
}

die() { echo "ERROR: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--single-arch) SINGLE_ARCH=1 ;;
    -t|--tag) TAG="${2:?--tag требует значение}"; shift ;;
    -p|--platforms) PLATFORMS="${2:?--platforms требует значение}"; shift ;;
    -o|--output) OUT="${2:?--output требует значение}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "неизвестный аргумент: $1 (см. --help)" ;;
  esac
  shift
done

# --- проверки ---
command -v docker >/dev/null 2>&1 || die "docker не найден в PATH"
docker buildx version >/dev/null 2>&1 || die "docker buildx недоступен (Docker >= 23 + buildx)"
[ -f "$ROOT/deploy/Dockerfile" ] || die "нет deploy/Dockerfile"
[ -f "$ROOT/build/com.dap.engine_1.0.0.jar" ] || die "нет build/com.dap.engine_1.0.0.jar (scripts/build-engine.sh)"
[ -d "$ROOT/runtime/knime/plugins" ] || die "нет runtime/knime/plugins (vendored runtime собран?)"
PLUGIN_COUNT=$(ls -1 "$ROOT/runtime/knime/plugins" | wc -l | tr -d ' ')
[ "$PLUGIN_COUNT" -gt 100 ] || die "runtime/knime/plugins подозрительно пуст: $PLUGIN_COUNT файлов"
ls "$ROOT"/runtime/knime/plugins/org.eclipse.equinox.launcher_*.jar >/dev/null 2>&1 \
  || die "в runtime/knime/plugins нет org.eclipse.equinox.launcher_*.jar"
[ -f "$ROOT/catalog/catalog.json" ] || die "нет catalog/catalog.json"
[ -f "$ROOT/catalog/curated.json" ] || die "нет catalog/curated.json"
[ -d "$ROOT/catalog/schemas" ] || die "нет catalog/schemas"
[ -x "$ROOT/scripts/run.sh" ] || die "scripts/run.sh не executable"

echo "[package-oci] root=$ROOT"
echo "[package-oci] tag=$TAG plugins=$PLUGIN_COUNT"

START=$(date +%s)

if [ "$SINGLE_ARCH" -eq 1 ]; then
  case "$(uname -m)" in
    arm64|aarch64) PLATFORM=linux/arm64 ;;
    x86_64|amd64)  PLATFORM=linux/amd64 ;;
    *) die "неизвестная архитектура хоста: $(uname -m)" ;;
  esac
  echo "[package-oci] single-arch: $PLATFORM -> docker --load"
  docker buildx build \
    --platform "$PLATFORM" \
    --tag "$TAG" \
    --load \
    --file "$ROOT/deploy/Dockerfile" \
    "$ROOT"
  echo "[package-oci] image loaded:"
  docker image inspect --format '  id={{.Id}} size={{.Size}} bytes' "$TAG"
else
  mkdir -p "$(dirname "$OUT")"
  rm -f "$OUT"
  echo "[package-oci] multi-arch: $PLATFORMS -> $OUT (type=oci)"
  docker buildx build \
    --platform "$PLATFORMS" \
    --tag "$TAG" \
    --output "type=oci,dest=$OUT" \
    --file "$ROOT/deploy/Dockerfile" \
    "$ROOT"
fi

ELAPSED=$(( $(date +%s) - START ))

if [ "$SINGLE_ARCH" -eq 0 ]; then
  [ -s "$OUT" ] || die "buildx завершился без файла $OUT"
  echo "[package-oci] OK: $OUT"
  ls -lh "$OUT"
  if command -v shasum >/dev/null 2>&1; then
    echo "  sha256: $(shasum -a 256 "$OUT" | awk '{print $1}')"
  elif command -v sha256sum >/dev/null 2>&1; then
    echo "  sha256: $(sha256sum "$OUT" | awk '{print $1}')"
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$OUT" <<'PY'
import json, sys, tarfile

with tarfile.open(sys.argv[1]) as tar:
    def load(digest):
        return json.loads(tar.extractfile("blobs/sha256/" + digest.split(":")[1]).read())

    def show(manifests, indent="  "):
        for m in manifests:
            ann = m.get("annotations") or {}
            if ann.get("vnd.docker.reference.type") == "attestation-manifest":
                continue
            if m.get("mediaType", "").endswith("image.index.v1+json"):
                show(load(m["digest"])["manifests"], indent + "  ")
                continue
            p = m.get("platform") or {}
            name = ann.get("io.containerd.image.name", "")
            print("%splatform: %s/%s %s%s" % (
                indent, p.get("os", "?"), p.get("architecture", "?"), m.get("digest", ""),
                ("  (" + name + ")") if name else ""))

    show(json.loads(tar.extractfile("index.json").read()).get("manifests", []))
PY
  fi
  echo "[package-oci] загрузить: docker load -i $OUT"
else
  echo "[package-oci] запуск: docker run --rm -p 7800:7800 $TAG"
fi

echo "[package-oci] done in ${ELAPSED}s"
