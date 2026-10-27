# Linearizable reads (ReadIndex)

## Why reads do not go through the log

Serving a `Get` through the log costs an append, an fsync and a replication round per read. It also grows the log and the snapshots, and it consumes client session slots. ReadIndex gives the same linearizability guarantee with a single heartbeat round and leaves the log untouched.

## Protocol

- `Input.ReadIndex(requestId)` never appends a log entry. A leader records `readIndex = commitIndex` when the read arrives.
- The leader confirms its leadership with a majority of the effective configuration acknowledging a round tagged with the current `readSeq`. Only a round sent after the read counts. The leader counts itself only if it is a member. Catch-up targets do not count.
- A read completes when `lastApplied >= readIndex`, and the core emits `Effect.ReadReady(requestId, readIndex)` at most once.
- `KvServer` answers a `Get` by reading its local `KvStateMachine` when it receives `ReadReady`.

## Current-term commit requirement

A new leader's `commitIndex` may lag entries committed by its predecessor until its own NoOp commits. Until then, reads are rejected with `Rejection.Busy`, and `KvServer` maps `Busy` to `Timeout`.

## Losing leadership

Pending reads receive `Rejection.NotLeader` when the leader steps down on a higher term or is removed from the configuration. They never complete afterwards. A leader cut off from the majority keeps its pending reads without an answer, so the client times out.

## Why not lease reads

Lease reads depend on bounded clock drift and timing assumptions. The simulator and the project charter make no such assumptions. ReadIndex is safe under arbitrary message delays.

## Trace

ReadIndex adds no trace events. Every `AppendEntries` carries `readSeq`, which stays `0` on a node until it serves its first read. Runs without reads therefore keep identical traces.
