#!/usr/bin/env bash
#
# Validates every ```mermaid block in committed Markdown with the pinned mermaid-cli (mmdc).
#
# Usage:
#   (cd .github/mermaid && npm ci)        # once: installs the pinned @mermaid-js/mermaid-cli
#   scripts/validate-mermaid.sh           # all git-tracked *.md except node_modules/, build/ and .github/mermaid/
#   scripts/validate-mermaid.sh FILE...   # only the given Markdown files (CI self-test)
#
# Environment:
#   MMDC                      mmdc binary (default: <repo>/.github/mermaid/node_modules/.bin/mmdc)
#   MERMAID_PUPPETEER_CONFIG  puppeteer config (default: <repo>/.github/mermaid/puppeteer-config.json)
#   PUPPETEER_CACHE_DIR       browser cache (default: <repo>/.github/mermaid/node_modules/.cache/puppeteer)
#
# Exit codes: 0 all blocks valid; 1 at least one block invalid (each reported as
# "FAIL: <file> block <k> (line <n>)"); 2 setup error (mmdc or config missing, file missing,
# not a git checkout, no Markdown files, or no mermaid blocks found).

set -u -o pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MMDC=${MMDC:-$ROOT/.github/mermaid/node_modules/.bin/mmdc}
CONFIG=${MERMAID_PUPPETEER_CONFIG:-$ROOT/.github/mermaid/puppeteer-config.json}
PUPPETEER_CACHE_DIR=${PUPPETEER_CACHE_DIR:-$ROOT/.github/mermaid/node_modules/.cache/puppeteer}
export PUPPETEER_CACHE_DIR

FENCE_OPEN='^[[:space:]]*```mermaid[[:space:]]*$'
FENCE_CLOSE='^[[:space:]]*```[[:space:]]*$'

setup_error() {
  echo "ERROR: $1" >&2
  exit 2
}

[ -x "$MMDC" ] || setup_error "mmdc not found at $MMDC (run: cd .github/mermaid && npm ci)"
[ -f "$CONFIG" ] || setup_error "puppeteer config not found at $CONFIG"

files=()
if [ "$#" -gt 0 ]; then
  for f in "$@"; do
    [ -f "$f" ] || setup_error "no such file: $f"
    files+=("$f")
  done
else
  git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || setup_error "not a git checkout: $ROOT"
  while IFS= read -r -d '' f; do
    case "$f" in
      node_modules/* | */node_modules/* | build/* | */build/* | .github/mermaid/*) continue ;;
    esac
    files+=("$ROOT/$f")
  done < <(git -C "$ROOT" ls-files -z -- '*.md')
fi
[ "${#files[@]}" -gt 0 ] || setup_error "no Markdown files to validate"

BASE=${RUNNER_TEMP:-$ROOT/build}
mkdir -p "$BASE" || setup_error "cannot create $BASE"
WORK=$(mktemp -d "$BASE/validate-mermaid.XXXXXX") || setup_error "mktemp failed under $BASE"
trap 'rm -rf "$WORK"' EXIT

blocks=0
failures=0
idx=0

validate_block() { # $1 display path, $2 block number in file, $3 start line, $4 .mmd file
  local svg="${4%.mmd}.svg" log="${4%.mmd}.log"
  blocks=$((blocks + 1))
  if "$MMDC" -q -p "$CONFIG" -i "$4" -o "$svg" >"$log" 2>&1; then
    echo "ok: $1 block $2 (line $3)"
  else
    failures=$((failures + 1))
    echo "FAIL: $1 block $2 (line $3)"
    sed -n '1,15p' "$log" | sed 's/^/    /'
  fi
}

for file in "${files[@]}"; do
  rel=${file#"$ROOT"/}
  k=0
  in_block=0
  ln=0
  start=0
  mmd=""
  while IFS= read -r line || [ -n "$line" ]; do
    ln=$((ln + 1))
    if [ "$in_block" -eq 1 ]; then
      if [[ $line =~ $FENCE_CLOSE ]]; then
        in_block=0
        validate_block "$rel" "$k" "$start" "$mmd"
      else
        printf '%s\n' "$line" >>"$mmd"
      fi
    elif [[ $line =~ $FENCE_OPEN ]]; then
      k=$((k + 1))
      idx=$((idx + 1))
      in_block=1
      start=$ln
      mmd="$WORK/$idx.mmd"
      : >"$mmd"
    fi
  done <"$file"
  if [ "$in_block" -eq 1 ]; then
    validate_block "$rel" "$k" "$start" "$mmd"
  fi
done

[ "$blocks" -gt 0 ] || setup_error "no mermaid blocks found in ${#files[@]} file(s)"
if [ "$failures" -gt 0 ]; then
  echo "FAIL: $failures of $blocks mermaid block(s) invalid"
  exit 1
fi
echo "PASS: $blocks mermaid block(s) in ${#files[@]} file(s) valid"
