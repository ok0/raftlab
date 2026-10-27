#!/usr/bin/env bash
#
# Checks that every `uses:` reference in GitHub workflow files is either a local action
# or pinned to a full 40-char commit SHA with a version comment (e.g. `# v6.1.0`).
#
# Usage:
#   scripts/check-action-pins.sh            # all .github/workflows/*.yml and *.yaml
#   scripts/check-action-pins.sh FILE...    # only the given files
#
# Exit codes: 0 all references pinned; 1 at least one unpinned reference; 2 no workflow files.

set -u -o pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
shopt -s nullglob

files=()
if [ "$#" -gt 0 ]; then
  for f in "$@"; do
    files+=("$f")
  done
else
  for f in "$ROOT"/.github/workflows/*.yml "$ROOT"/.github/workflows/*.yaml; do
    files+=("$f")
  done
fi

if [ "${#files[@]}" -eq 0 ]; then
  echo "check-action-pins: no workflow files" >&2
  exit 2
fi

USES_LINE='^[[:space:]]*(-[[:space:]]+)?uses:'
LOCAL_ACTION='^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+\./'
PINNED='^[[:space:]]*(-[[:space:]]+)?uses:[[:space:]]+[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}[[:space:]]+#[[:space:]]*v[0-9][^[:space:]]*[[:space:]]*$'

count=0
bad=0

for file in "${files[@]}"; do
  rel=${file#"$ROOT"/}
  ln=0
  while IFS= read -r line || [ -n "$line" ]; do
    ln=$((ln + 1))
    if [[ $line =~ $USES_LINE ]]; then
      count=$((count + 1))
      if [[ $line =~ $LOCAL_ACTION ]] || [[ $line =~ $PINNED ]]; then
        continue
      fi
      bad=$((bad + 1))
      trimmed=${line#"${line%%[![:space:]]*}"}
      echo "UNPINNED: $rel:$ln: $trimmed"
    fi
  done <"$file"
done

if [ "$bad" -gt 0 ]; then
  echo "FAIL: $bad of $count uses: references are not pinned to a 40-char SHA with a version comment"
  exit 1
fi
echo "PASS: all $count uses: references are SHA-pinned"
exit 0
