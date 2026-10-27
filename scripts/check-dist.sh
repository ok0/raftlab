#!/usr/bin/env bash
#
# Checks a raftlab CLI distribution zip: the top folder holds bin/raftlab, bin/raftlab.bat and
# lib/raftlab-cli*.jar; the unpacked bin/raftlab --help lists every command; and, when an expected
# version is given, the zip name and bin/raftlab --version match it.
#
# Usage:
#   scripts/check-dist.sh ZIP [EXPECTED_VERSION]
#
# Environment:
#   RAFTLAB_DIST_WORK  scratch base directory (default: ${RUNNER_TEMP:-${TMPDIR:-/tmp}})
#
# Exit codes: 0 PASS; 1 FAIL; 2 usage or setup error (zip missing, unzip or java unavailable).

set -u -o pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: scripts/check-dist.sh ZIP [EXPECTED_VERSION]" >&2
  exit 2
fi

ZIP=$1
EXPECTED=${2:-}
WORK_BASE=${RAFTLAB_DIST_WORK:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}}

if ! command -v unzip >/dev/null 2>&1; then
  echo "check-dist: unzip not found" >&2
  exit 2
fi

java_ok=0
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_ok=1
fi
if command -v java >/dev/null 2>&1; then
  java_ok=1
fi
if [ "$java_ok" -ne 1 ]; then
  echo "check-dist: java not found (set JAVA_HOME or put java on PATH)" >&2
  exit 2
fi

if [ ! -f "$ZIP" ]; then
  echo "check-dist: zip not found: $ZIP" >&2
  exit 2
fi

fails=0
fail() {
  echo "FAIL: $1"
  fails=$((fails + 1))
}

if ! entries=$(unzip -Z1 "$ZIP" 2>/dev/null) || [ -z "$entries" ]; then
  echo "FAIL: cannot list entries of $ZIP"
  exit 1
fi

top=${entries%%$'\n'*}
top=${top%%/*}

for entry in "$top/bin/raftlab" "$top/bin/raftlab.bat"; do
  grep -qxF -- "$entry" <<<"$entries" || fail "missing entry $entry"
done

jar_found=0
while IFS= read -r entry; do
  case $entry in
    "$top/lib/raftlab-cli"*.jar)
      jar_found=1
      break
      ;;
  esac
done <<<"$entries"
if [ "$jar_found" -ne 1 ]; then
  fail "missing entry $top/lib/raftlab-cli*.jar"
fi

if [ -n "$EXPECTED" ] && [ "$(basename "$ZIP")" != "raftlab-$EXPECTED.zip" ]; then
  fail "zip name $(basename "$ZIP") does not match raftlab-$EXPECTED.zip"
fi

if [ "$fails" -gt 0 ]; then
  echo "FAIL: $ZIP ($fails problem(s))"
  exit 1
fi

if ! mkdir -p "$WORK_BASE"; then
  echo "check-dist: cannot create scratch directory $WORK_BASE" >&2
  exit 2
fi
work=$(mktemp -d "$WORK_BASE/raftlab-dist.XXXXXX") || {
  echo "check-dist: cannot create scratch directory under $WORK_BASE" >&2
  exit 2
}
trap 'rm -rf "$work"' EXIT

if ! unzip -q "$ZIP" -d "$work"; then
  echo "FAIL: cannot extract $ZIP"
  exit 1
fi

bin="$work/$top/bin/raftlab"

if ! help_out=$("$bin" --help); then
  fail "bin/raftlab --help exited non-zero"
fi
for cmd in scenario export docs fuzz node kv; do
  grep -qw -- "$cmd" <<<"$help_out" || fail "bin/raftlab --help does not list '$cmd'"
done

if [ -n "$EXPECTED" ]; then
  if ! ver_out=$("$bin" --version); then
    fail "bin/raftlab --version exited non-zero"
  elif [ "$ver_out" != "raftlab version $EXPECTED" ]; then
    fail "bin/raftlab --version printed '$ver_out', expected 'raftlab version $EXPECTED'"
  fi
fi

if [ "$fails" -gt 0 ]; then
  echo "FAIL: $ZIP ($fails problem(s))"
  exit 1
fi

if [ -n "$EXPECTED" ]; then
  echo "PASS: $ZIP (bin/raftlab --help lists all commands, version $EXPECTED)"
else
  echo "PASS: $ZIP (bin/raftlab --help lists all commands)"
fi
exit 0
