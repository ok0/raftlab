#!/usr/bin/env bash
#
# Checks a raftlab container image: --help lists every command, --version prints the expected
# version, the image runs as a non-root user, and a node started in a container logs its start
# line and, on docker stop (SIGTERM), logs its stop line and exits 0.
#
# Usage:
#   scripts/check-image.sh IMAGE EXPECTED_VERSION
#
# Requires a running docker daemon.
# Exit codes: 0 PASS; 1 FAIL; 2 usage or setup error (docker missing or daemon unavailable).

set -u -o pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: scripts/check-image.sh IMAGE EXPECTED_VERSION" >&2
  exit 2
fi

IMAGE=$1
EXPECTED=$2

if ! command -v docker >/dev/null 2>&1; then
  echo "check-image: docker not found" >&2
  exit 2
fi
if ! docker info >/dev/null 2>&1; then
  echo "check-image: docker daemon not available" >&2
  exit 2
fi

fails=0
fail() {
  echo "FAIL: $1"
  fails=$((fails + 1))
}

if ! help_out=$(docker run --rm "$IMAGE" --help); then
  fail "docker run $IMAGE --help exited non-zero"
fi
for cmd in scenario export docs fuzz node kv; do
  grep -qw -- "$cmd" <<<"$help_out" || fail "--help does not list '$cmd'"
done

if ! ver_out=$(docker run --rm "$IMAGE" --version); then
  fail "docker run $IMAGE --version exited non-zero"
elif [ "$ver_out" != "raftlab version $EXPECTED" ]; then
  fail "--version printed '$ver_out', expected 'raftlab version $EXPECTED'"
fi

uid_out=$(docker run --rm --entrypoint id "$IMAGE" -u)
uid_rc=$?
if [ "$uid_rc" -ne 0 ] || [ "$uid_out" = "0" ]; then
  fail "container runs as root (id -u printed '$uid_out', rc $uid_rc)"
fi

name="raftlab-check-$$"
trap 'docker rm -f "$name" >/dev/null 2>&1' EXIT

if ! docker run -d --name "$name" "$IMAGE" node --id n1 --cluster n1=127.0.0.1:7000 --data-dir /data >/dev/null; then
  fail "could not start the node container"
else
  started=0
  for _ in $(seq 1 120); do
    logs=$(docker logs "$name" 2>&1)
    if [[ $logs == *"raftlab node n1 started"* ]]; then
      started=1
      break
    fi
    sleep 0.5
  done

  if [ "$started" -ne 1 ]; then
    fail "node did not log 'raftlab node n1 started' within 60 s"
  else
    if ! docker stop -t 20 "$name" >/dev/null; then
      fail "docker stop $name failed"
    fi
    exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$name")
    if [ "$exit_code" != "0" ]; then
      fail "node exit code after SIGTERM is '$exit_code', expected 0"
    fi
    logs=$(docker logs "$name" 2>&1)
    if [[ $logs != *"raftlab node n1 stopped"* ]]; then
      fail "node did not log 'raftlab node n1 stopped'"
    fi
  fi
fi

if [ "$fails" -gt 0 ]; then
  echo "FAIL: image $IMAGE ($fails problem(s))"
  exit 1
fi

echo "PASS: image $IMAGE"
exit 0
