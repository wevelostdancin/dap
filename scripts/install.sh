#!/bin/sh
# DAP installer: download a self-contained archive, verify it, unpack to ~/.dap.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/wevelostdancin/dap/main/scripts/install.sh | sh
#   sh scripts/install.sh --version 1.3.0 --client dsh --path
#   sh scripts/install.sh --archive build/package/dap-1.3.0-macos-arm64.tar.gz
#
# The archive contains its own JRE and Python runtime, so no JDK or Python is
# required on the machine. The installer never uses sudo and only writes to the
# DAP home and (with --path) the shell rc file.
set -eu

REPO="wevelostdancin/dap"
VERSION="${DAP_VERSION:-latest}"
HOME_DIR="${DAP_HOME:-$HOME/.dap}"
CLIENT=""
ARCHIVE=""
ADD_PATH=0
FORCE=0
DRY_RUN=0
SKIP_DOCTOR=0

usage() {
  sed -n '2,16p' "$0"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION=$2; shift 2 ;;
    --home) HOME_DIR=$2; shift 2 ;;
    --client) CLIENT=$2; shift 2 ;;
    --archive) ARCHIVE=$2; shift 2 ;;
    --path) ADD_PATH=1; shift ;;
    --force) FORCE=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --skip-doctor) SKIP_DOCTOR=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "install.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

os=$(uname -s)
case "$os" in
  Darwin) os_tag=macos ;;
  Linux) os_tag=linux ;;
  *) echo "install.sh: unsupported OS: $os (macOS and Linux only)" >&2; exit 2 ;;
esac
arch=$(uname -m)
case "$arch" in
  arm64|aarch64) arch_tag=arm64 ;;
  x86_64|amd64) arch_tag=x64 ;;
  *) echo "install.sh: unsupported architecture: $arch" >&2; exit 2 ;;
esac

if [ -n "$ARCHIVE" ]; then
  archive=$ARCHIVE
  checksum="$ARCHIVE.sha256"
else
  if [ "$VERSION" = "latest" ]; then
    asset="dap-latest-$os_tag-$arch_tag.tar.gz"
  else
    asset="dap-$VERSION-$os_tag-$arch_tag.tar.gz"
  fi
  base="https://github.com/$REPO/releases"
  if [ "$VERSION" = "latest" ]; then
    url="$base/latest/download/$asset"
  else
    url="$base/download/v$VERSION/$asset"
  fi
  tmpdir=$(mktemp -d "${TMPDIR:-/tmp}/dap-install.XXXXXX")
  trap 'rm -rf "$tmpdir"' EXIT INT TERM
  archive="$tmpdir/$asset"
  checksum="$archive.sha256"
  echo "install.sh: downloading $url"
  curl -fL --retry 3 --connect-timeout 15 -o "$archive" "$url"
  if ! curl -fL --retry 3 --connect-timeout 15 -o "$checksum" "$url.sha256"; then
    echo "install.sh: checksum file not found: $url.sha256" >&2
    exit 1
  fi
fi

[ -f "$archive" ] || { echo "install.sh: archive not found: $archive" >&2; exit 1; }
[ -f "$checksum" ] || { echo "install.sh: checksum not found: $checksum" >&2; exit 1; }

expected=$(awk '{print $1; exit}' "$checksum")
if command -v shasum >/dev/null 2>&1; then
  actual=$(shasum -a 256 "$archive" | awk '{print $1}')
else
  actual=$(sha256sum "$archive" | awk '{print $1}')
fi
if [ "$expected" != "$actual" ]; then
  echo "install.sh: checksum mismatch" >&2
  echo "  expected: $expected" >&2
  echo "  actual:   $actual" >&2
  exit 1
fi
echo "install.sh: checksum ok"

if ! tar -tzf "$archive" | awk 'BEGIN { bad=0 } /^\// || /(^|\/)\.\.(\/|$)/ { bad=1 } END { exit bad }'; then
  echo "install.sh: archive contains unsafe paths" >&2
  exit 1
fi

if [ "$DRY_RUN" -eq 1 ]; then
  echo "install.sh: dry run; would install to $HOME_DIR"
  exit 0
fi

if [ -e "$HOME_DIR" ] && [ "$FORCE" -eq 0 ]; then
  echo "install.sh: $HOME_DIR already exists; use --force to upgrade" >&2
  exit 1
fi

parent=$(dirname "$HOME_DIR")
mkdir -p "$parent"
staging="$HOME_DIR.new.$$"
rm -rf "$staging"
mkdir -p "$staging"
tar -xzf "$archive" -C "$staging"
# The archive has one top-level directory; flatten it.
top=$(find "$staging" -mindepth 1 -maxdepth 1 -type d | head -1)
if [ -z "$top" ]; then
  echo "install.sh: archive is empty" >&2
  rm -rf "$staging"; exit 1
fi
if [ -d "$HOME_DIR" ]; then
  # Keep user recipes and cache across upgrades.
  if [ -d "$HOME_DIR/recipes" ]; then
    cp -R "$HOME_DIR/recipes/." "$top/recipes/" 2>/dev/null || true
  fi
  if [ -d "$HOME_DIR/build" ]; then
    mkdir -p "$top/build"
    cp -R "$HOME_DIR/build/." "$top/build/" 2>/dev/null || true
  fi
  old="$HOME_DIR.old.$$"
  mv "$HOME_DIR" "$old"
  mv "$top" "$HOME_DIR"
  rm -rf "$old" "$staging"
else
  mv "$top" "$HOME_DIR"
  rm -rf "$staging"
fi
chmod +x "$HOME_DIR/bin/dap" 2>/dev/null || true
echo "install.sh: installed to $HOME_DIR"

if [ "$ADD_PATH" -eq 1 ]; then
  rc="$HOME/.zshrc"
  case "${SHELL:-}" in
    */bash) rc="$HOME/.bashrc" ;;
    */zsh) rc="$HOME/.zshrc" ;;
  esac
  marker="# >>> dap >>>"
  if ! grep -F "$marker" "$rc" >/dev/null 2>&1; then
    {
      echo ""
      echo "$marker"
      echo "export PATH=\"$HOME_DIR/bin:\$PATH\""
      echo "# <<< dap <<<"
    } >>"$rc"
    echo "install.sh: added $HOME_DIR/bin to $rc"
  fi
fi

if [ -n "$CLIENT" ]; then
  "$HOME_DIR/bin/dap" install --client "$CLIENT"
fi

if [ "$SKIP_DOCTOR" -eq 0 ]; then
  if [ -n "$CLIENT" ]; then
    "$HOME_DIR/bin/dap" doctor --client "$CLIENT" || true
  else
    "$HOME_DIR/bin/dap" doctor || true
  fi
fi

echo "install.sh: done"
echo "  next: $HOME_DIR/bin/dap version"
echo "  MCP:  $HOME_DIR/bin/dap install --client dsh"
