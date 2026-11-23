#!/bin/sh
# Build a minimal JRE for the DAP archive. POSIX sh, no bashisms.
set -eu

output=build/package/runtime
jar=
base_modules=java.base,java.desktop,java.instrument,java.management,java.naming,java.security.jgss,java.sql,jdk.jfr,jdk.unsupported
extra_modules=jdk.crypto.ec,jdk.zipfs,jdk.charsets,jdk.localedata

usage() {
  cat <<'EOF'
Usage: scripts/jlink-runtime.sh --jar PATH [--output DIR]

  --jar PATH     dap-native.jar to analyse with jdeps (required)
  --output DIR   JRE destination (default: build/package/runtime)
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --jar)
      [ $# -ge 2 ] || { echo "jlink: --jar needs a path" >&2; exit 2; }
      jar=$2; shift 2 ;;
    --output)
      [ $# -ge 2 ] || { echo "jlink: --output needs a path" >&2; exit 2; }
      output=$2; shift 2 ;;
    --jar=*) jar=${1#--jar=}; shift ;;
    --output=*) output=${1#--output=}; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "jlink: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[ -n "$jar" ] || { echo "jlink: --jar is required" >&2; usage >&2; exit 2; }
jdk=${DAP_JDK:-${JAVA_HOME:-}}
if [ -z "$jdk" ]; then
  echo "jlink: set DAP_JDK or JAVA_HOME to a JDK 21" >&2
  exit 2
fi
[ -x "$jdk/bin/jlink" ] || { echo "jlink: not found: $jdk/bin/jlink" >&2; exit 2; }
[ -x "$jdk/bin/jdeps" ] || { echo "jlink: not found: $jdk/bin/jdeps" >&2; exit 2; }
[ -f "$jar" ] || { echo "jlink: jar not found: $jar" >&2; exit 2; }

modules=$("$jdk/bin/jdeps" --multi-release 21 --print-module-deps --ignore-missing-deps "$jar" 2>/dev/null || true)
modules=$(printf "%s\n" "$modules" | tr "\n" "," | sed "s/,,*/,/g; s/^,//; s/,$//")
if [ -z "$modules" ]; then
  echo "jlink: jdeps found no modules; using the fallback set"
  modules=$base_modules
fi
modules=$(printf "%s,%s\n" "$modules" "$extra_modules" | tr "," "\n" | sed "/^$/d" | sort -u | tr "\n" "," | sed "s/,$//")

case "$output" in
  /*) ;;
  *) output=$(pwd)/$output ;;
esac
case "$output" in
  /|/bin|/sbin|/usr|/usr/*|/System|/System/*)
    echo "jlink: refusing unsafe output directory: $output" >&2; exit 2 ;;
esac
printf "jlink: removing %s\n" "$output"
rm -rf "$output"
"$jdk/bin/jlink" --strip-debug --no-header-files --no-man-pages --compress=2 \
  --add-modules "$modules" --output "$output"
"$output/bin/java" -version
echo "jlink: $output"
