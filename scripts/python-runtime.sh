#!/bin/sh
# Download the pinned python-build-standalone runtime once. POSIX sh.
set -eu

release=20261110
version=3.12.16
target=build/tools/python

usage() {
  cat <<'EOF'
Usage: scripts/python-runtime.sh [--output DIR]

  --output DIR   install directory (default: build/tools/python)
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --output)
      [ $# -ge 2 ] || { echo "python-runtime: --output needs a path" >&2; exit 2; }
      target=$2; shift 2 ;;
    --output=*) target=${1#--output=}; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "python-runtime: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$target" in
  /*) ;;
  *) target=$(pwd)/$target ;;
esac
if [ -x "$target/bin/python3" ]; then
  echo "python-runtime: already installed at $target"
  exit 0
fi

system=$(uname -s)
machine=$(uname -m)
case "$system-$machine" in
  Darwin-arm64|Darwin-aarch64)
    triple=aarch64-apple-darwin
    sha=2f11b8aa3726bdbd87dd9eb76568412faddf4f47e26913fa45d6a2e424972acd ;;
  Darwin-x86_64)
    triple=x86_64-apple-darwin
    sha=b78a4c479f1964ec484880bee567568266f68da102269fb93a05fe8716b45f91 ;;
  Linux-x86_64|Linux-amd64)
    triple=x86_64-unknown-linux-gnu
    sha=6a9e62dad033790aef722dbd370e0ce76ce17b73627ba3ae679f537049350b39 ;;
  Linux-aarch64|Linux-arm64)
    triple=aarch64-unknown-linux-gnu
    sha=b6a41defec4975d94b4ff1950236e7383e5c02ba40ea48c9ec33c66087a35aab ;;
  *)
    echo "python-runtime: unsupported platform: $system $machine" >&2
    exit 2 ;;
esac

asset="cpython-${version}+${release}-${triple}-install_only_stripped.tar.gz"
url="https://github.com/astral-sh/python-build-standalone/releases/download/${release}/${asset}"
parent=$(dirname "$target")
mkdir -p "$parent"
work=$(mktemp -d "$parent/.python-download.XXXXXX")
trap 'rm -rf "$work"' 0
trap 'exit 1' HUP INT TERM

echo "python-runtime: downloading $asset"
curl -fL --retry 3 -o "$work/$asset" "$url"
if command -v shasum >/dev/null 2>&1; then
  actual=$(shasum -a 256 "$work/$asset" | awk "{print \$1}")
elif command -v sha256sum >/dev/null 2>&1; then
  actual=$(sha256sum "$work/$asset" | awk "{print \$1}")
else
  echo "python-runtime: no SHA256 tool (need shasum or sha256sum)" >&2
  exit 2
fi
if [ "$actual" != "$sha" ]; then
  echo "python-runtime: SHA256 mismatch for $asset" >&2
  echo "  expected: $sha" >&2
  echo "  actual:   $actual" >&2
  exit 1
fi
echo "python-runtime: SHA256 OK"

mkdir -p "$work/unpack"
tar -xzf "$work/$asset" -C "$work/unpack"
src=
for candidate in "$work"/unpack/*/bin/python3 "$work"/unpack/bin/python3; do
  if [ -x "$candidate" ]; then
    src=$(dirname "$(dirname "$candidate")")
    break
  fi
done
if [ -z "$src" ]; then
  echo "python-runtime: archive has no bin/python3" >&2
  exit 1
fi
"$src/bin/python3" -V
if [ -e "$target" ]; then
  printf "python-runtime: removing %s\n" "$target"
  rm -rf "$target"
fi
mv "$src" "$target"
echo "python-runtime: $target/bin/python3"
