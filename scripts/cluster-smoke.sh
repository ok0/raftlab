#!/usr/bin/env bash
#
# Process-level smoke test for the raftlab CLI (PAC6).
#
# Starts a 3-node cluster as separate `raftlab node` processes over TCP, runs KV
# operations through `raftlab kv`, kills one node with SIGKILL, restarts it on the
# same data dir and port, and checks that every acknowledged write is still readable
# after a second node is killed (so the restarted node is needed for a majority).
#
# Usage:
#   ./gradlew installDist && scripts/cluster-smoke.sh
#
# Environment:
#   RAFTLAB_DIST            distribution directory
#                           (default: <repo>/raftlab-cli/build/install/raftlab)
#   RAFTLAB_SMOKE_VICTIM    node to kill first: leader (default), n1, n2 or n3
#   RAFTLAB_SMOKE_TIMEOUT   watchdog limit in seconds (default: 90)
#   RAFTLAB_SMOKE_TICK_MILLIS  node tick interval in ms (default: 20)
#   RAFTLAB_SMOKE_BASE_PORT first port scanned for the 3 cluster ports
#                           (default: random in 20000..39999)
#
# Victim policy: "leader" kills the node that most recently reported LEADER in its
# trace files (highest term wins); "n1".."n3" kill that node regardless of role.
#
# Exit codes: 0 PASS, 1 FAIL (step reported on stdout as "FAIL: cluster smoke: <step>"),
# 130 interrupted (SIGINT), 143 terminated (SIGTERM or watchdog timeout).
#
# Uses only bash, grep, sed, sort, tail, mktemp, pkill, sleep and the distribution's JVM.

set -u -o pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RAFTLAB_DIST=${RAFTLAB_DIST:-$ROOT/raftlab-cli/build/install/raftlab}
VICTIM=${RAFTLAB_SMOKE_VICTIM:-leader}
LIMIT=${RAFTLAB_SMOKE_TIMEOUT:-90}
TICK=${RAFTLAB_SMOKE_TICK_MILLIS:-20}
BASE=${RAFTLAB_SMOKE_BASE_PORT:-$((20000 + RANDOM % 20000))}
BIN=$RAFTLAB_DIST/bin/raftlab

WORK=$(mktemp -d "${TMPDIR:-/tmp}/raftlab-smoke.XXXXXX")
PIDS=("" "" "" "")
RUNS=(0 0 0 0)
STEP="starting"
WATCHDOG=""
CLUSTER=""

finish() {
  trap - EXIT INT TERM
  local i
  for i in 1 2 3; do
    stop_node "$i"
  done
  if [ -n "$WATCHDOG" ]; then
    local wd_child
    wd_child=$(pgrep -P "$WATCHDOG" 2>/dev/null)
    kill -KILL "$WATCHDOG" 2>/dev/null
    wait "$WATCHDOG" 2>/dev/null
    [ -n "$wd_child" ] && kill "$wd_child" 2>/dev/null
  fi
  rm -rf "$WORK"
  if [ "$1" -eq 0 ]; then
    echo "PASS: cluster smoke"
  else
    echo "FAIL: cluster smoke: $STEP"
  fi
  exit "$1"
}

trap 'finish $?' EXIT
trap 'STEP="interrupted"; finish 130' INT
trap 'STEP="terminated (timeout or signal)"; finish 143' TERM

( sleep "$LIMIT"; kill -TERM $$ ) >/dev/null 2>&1 &
WATCHDOG=$!

fail() {
  STEP=$1
  local f
  for f in "$WORK"/n*.log; do
    [ -e "$f" ] || continue
    echo "--- $f" >&2
    tail -n 20 "$f" >&2
  done
  exit 1
}

port_free() {
  if (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; then
    return 1
  fi
  return 0
}

start_node() {
  local i=$1 n=0
  RUNS[i]=$(( RUNS[i] + 1 ))
  "$BIN" node --id "n$i" --cluster "$CLUSTER" --data-dir "$WORK/n$i" --tick-millis "$TICK" \
    --trace "$WORK/n$i.${RUNS[i]}.jsonl" >>"$WORK/n$i.log" 2>&1 &
  PIDS[i]=$!
  while ! grep -q "raftlab node n$i started" "$WORK/n$i.log" 2>/dev/null; do
    kill -0 "${PIDS[i]}" 2>/dev/null || fail "node n$i exited while starting"
    n=$(( n + 1 ))
    [ "$n" -ge 100 ] && fail "node n$i did not start within 20 s"
    sleep 0.2
  done
}

crash_node() {
  local i=$1
  kill -KILL "${PIDS[i]}" 2>/dev/null
  wait "${PIDS[i]}" 2>/dev/null
  PIDS[i]=""
}

stop_node() {
  local i=$1 pid=${PIDS[$1]:-} n=0
  [ -n "$pid" ] || return 0
  if kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null
    while kill -0 "$pid" 2>/dev/null && [ "$n" -lt 50 ]; do
      sleep 0.2
      n=$(( n + 1 ))
    done
    kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null
  fi
  wait "$pid" 2>/dev/null
  PIDS[i]=""
}

kv() {
  "$BIN" kv --cluster "$CLUSTER" --timeout-millis 5000 "$@"
}

put_retry() {
  local k=$1 v=$2 out deadline
  deadline=$(( SECONDS + 30 ))
  while :; do
    out=$(kv put "$k" "$v" 2>>"$WORK/kv.log")
    [ "$out" = OK ] && return 0
    [ "$SECONDS" -ge "$deadline" ] && fail "put $k"
    sleep 0.5
  done
}

expect_get() {
  local out
  out=$(kv get "$1" 2>>"$WORK/kv.log") || fail "get $1 exited nonzero"
  [ "$out" = "$2" ] || fail "get $1 returned '$out', expected '$2'"
}

find_leader() {
  local i t best="" bestterm=-1
  for i in 1 2 3; do
    [ -n "${PIDS[i]}" ] && kill -0 "${PIDS[i]}" 2>/dev/null || continue
    t=$(grep -h '"type":"RoleChanged"' "$WORK"/n$i.*.jsonl 2>/dev/null \
      | grep '"to":"LEADER"' \
      | sed -E 's/.*"term":([0-9]+).*/\1/' \
      | sort -n | tail -1)
    if [ -n "$t" ] && [ "$t" -gt "$bestterm" ]; then
      bestterm=$t
      best="n$i"
    fi
  done
  echo "$best"
}

STEP="distribution check"
[ -x "$BIN" ] || fail "distribution not found at $RAFTLAB_DIST (run ./gradlew installDist)"
case "$VICTIM" in
  leader|n1|n2|n3) ;;
  *) fail "invalid RAFTLAB_SMOKE_VICTIM '$VICTIM' (leader, n1, n2 or n3)" ;;
esac

STEP="port selection"
PORTS=()
p=$BASE
while [ "${#PORTS[@]}" -lt 3 ]; do
  [ "$p" -lt 65535 ] || fail "no free ports from $BASE upward"
  if port_free "$p"; then
    PORTS+=("$p")
  fi
  p=$(( p + 1 ))
done
CLUSTER="n1=127.0.0.1:${PORTS[0]},n2=127.0.0.1:${PORTS[1]},n3=127.0.0.1:${PORTS[2]}"

STEP="starting n1..n3"
start_node 1
start_node 2
start_node 3

STEP="initial writes"
put_retry a 1
put_retry b 2
expect_get a 1
expect_get b 2

STEP="selecting victim"
leader=$(find_leader)
if [ "$VICTIM" = leader ]; then
  [ -n "$leader" ] || fail "no leader found in trace files"
  victim=$leader
else
  victim=$VICTIM
fi
vi=${victim#n}
echo "killing $victim (leader: ${leader:-none})"
STEP="killing $victim"
crash_node "$vi"

STEP="writes with $victim down"
put_retry c 3
expect_get a 1
expect_get c 3

STEP="restarting $victim"
start_node "$vi"
put_retry d 4

STEP="killing a second node"
other=""
for i in 1 2 3; do
  if [ "$i" != "$vi" ] && [ -n "${PIDS[i]}" ] && kill -0 "${PIDS[i]}" 2>/dev/null; then
    other=$i
    break
  fi
done
[ -n "$other" ] || fail "no second live node to kill"
echo "killing n$other"
crash_node "$other"

STEP="writes after rejoin"
put_retry e 5
expect_get a 1
expect_get b 2
expect_get c 3
expect_get d 4
expect_get e 5

STEP="done"
exit 0
