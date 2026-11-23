#!/bin/sh
# Unified DAP entry point: engine | mcp | doctor | install | version | home.
set -eu

here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${DAP_HOME:=$here}"
export DAP_HOME
# Do not write bytecode into the archive: SHA256SUMS covers every file.
PYTHONDONTWRITEBYTECODE=1
export PYTHONDONTWRITEBYTECODE

python="$here/python/bin/python3"
if [ ! -x "$python" ]; then
  python=python3
fi

if [ -x "$here/runtime/bin/java" ]; then
  JAVA_HOME="$here/runtime"
  export JAVA_HOME
fi

usage() {
  cat <<'EOF'
Usage: dap <command> [args...]

  engine [args...]   run the native engine (HTTP on 127.0.0.1)
  mcp [args...]      run the MCP CLI (stdio server by default)
  doctor [args...]   preflight the installation
  install [args...]  register DAP in an MCP client
  version            print DAP, Java and Python versions
  home               print DAP_HOME
EOF
}

engine() {
  java="$here/runtime/bin/java"
  if [ ! -x "$java" ]; then
    echo "dap: bundled runtime missing: $java (run make runtime)" >&2
    exit 2
  fi
  opts=${DAP_JAVA_OPTS:-}
  if [ -n "${DAP_HEAP:-}" ]; then
    case " $opts " in
      *" -Xmx"*) ;;
      *) opts="-Xmx${DAP_HEAP}${opts:+ $opts}" ;;
    esac
  fi
  exec "$java" $opts -jar "$here/lib/dap-native.jar" "$@"
}

mcp() {
  exec "$python" "$here/adapters/mcp/dap_cli.py" "$@"
}

case "${1:-}" in
  engine) shift; engine "$@" ;;
  mcp) shift; mcp "$@" ;;
  doctor) shift; mcp doctor "$@" ;;
  install) shift; mcp install "$@" ;;
  version)
    java=$here/runtime/bin/java
    if [ ! -x "$java" ]; then
      java=${JAVA_HOME:+$JAVA_HOME/bin/java}
    fi
    if [ ! -x "$java" ]; then java=java; fi
    printf "dap: %s\n" "$(cat "$here/VERSION")"
    java_version=$("$java" -version 2>&1 | sed -n "1p" || true)
    python_version=$("$python" -V 2>&1 || true)
    printf "java: %s\n" "${java_version:-not available}"
    printf "python: %s\n" "${python_version:-not available}"
    ;;
  home) printf "%s\n" "$DAP_HOME" ;;
  -h|--help|help) usage ;;
  "") usage >&2; exit 2 ;;
  *) echo "dap: unknown command: $1" >&2; usage >&2; exit 2 ;;
esac
