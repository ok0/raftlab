# Client semantics

This note describes what a client of the key-value store can rely on: how a write is deduplicated across retries, how a request reaches the leader, how `get` avoids the log, and what the timeouts mean. The store is `KvStateMachine` in `raftlab-kv`; the TCP client is `TcpKvClient` in `raftlab-net`.

## Operations and identity

Every client operation is a `KvCommand` with a `clientId`, a per-client `seq` starting at 1, and an operation (`Put`, `Delete`, `Cas` or `Get`). `put`, `delete` and `cas` are proposed through the log. `get` is served through `Input.ReadIndex` and does not append an entry (`KvServer`; see [read-index.md](read-index.md)).

## Duplicate suppression

`KvStateMachine` keeps one session per client: the last applied `seq` and its cached result. A command whose `seq` is not greater than the cached `seq` returns the cached result without being applied again. Retries of the same operation therefore do not apply twice.

Sessions never expire. This is a documented limit of the state machine: the session table grows with the number of clients and is part of the snapshot.

## Routing to the leader

`KvClientCore` is a pure client state machine. It has at most one operation in flight and rotates through the known members in id order. A `NotLeader` answer with a hint sends the request to the hinted node and adds that node to the rotation if it was unknown. A `NotLeader` answer without a hint, and a timeout, move the request to the next node in the rotation. Each retry reuses the same `seq`, so duplicate suppression applies.

`TcpKvClient` wraps this core with real connections. Its KDoc states that one operation runs at a time, and that a call fails with `KvClientTimeoutException` within about `callTimeoutMillis + 2 * attemptTimeoutMillis`. Its defaults are `attemptTimeoutMillis = 1000` (`DEFAULT_ATTEMPT_TIMEOUT_MS`) and a random `clientId` per instance.

After a timed-out call, the client continues under a fresh session id `"<clientId>#<n>"`, because the abandoned operation may still be applied. The server keys its session table by client id, so the fresh id keeps later operations separate from the abandoned one.

## The CLI timeout

`raftlab kv` accepts `--timeout-millis` as the upper bound for one operation. Its help text gives the default as 10000 ms (`CliDefaults.KV_TIMEOUT_MILLIS`), and the value must be at least 1. The per-attempt timeout is the smaller of 1000 ms and this bound. When an operation exceeds it, the CLI prints an error that names the operation and the bound.

## Compare-and-set

`raftlab kv ... cas KEY EXPECTED NEW` replaces the value of `KEY` when it equals `EXPECTED`. `--expect-absent` replaces `EXPECTED` with the requirement that `KEY` is absent, and `--new-absent` deletes `KEY` on success instead of setting `NEW`. The command prints `OK` on success, and `CAS_FAILED <actual>` (or `CAS_FAILED (nil)` when the key is absent) with exit code 1 on failure.

## Outcome of a timed-out write

A timed-out write has an unknown outcome: the entry may still be committed later. The client does not learn which, and the session rule above is what keeps a later retry from applying twice.

## Linearizability

Reads through `ReadIndex` are linearizable by the rule in `Input.ReadIndex`. The simulator tests run the `LinearizabilityChecker` over recorded client histories (see `ScenarioLinearizabilityTest`). `StaleReadScenarioTest` checks that a stale read is never served over 20 seeds and that an unconfirmed read is rejected by the checker.

## Limitations

- Sessions never expire (see above).
- A timed-out write can be applied later without the client knowing the outcome.
- The timeouts above are defaults in code, not measurements.

## Where to see it

- State machine and sessions: [`KvStateMachine.kt`](../../raftlab-kv/src/main/kotlin/io/raftlab/kv/KvStateMachine.kt), [`KvSession.kt`](../../raftlab-kv/src/main/kotlin/io/raftlab/kv/KvSession.kt), [`KvCommand.kt`](../../raftlab-kv/src/main/kotlin/io/raftlab/kv/KvCommand.kt)
- Server and client core: [`KvServer.kt`](../../raftlab-kv/src/main/kotlin/io/raftlab/kv/KvServer.kt), [`KvClientCore.kt`](../../raftlab-kv/src/main/kotlin/io/raftlab/kv/KvClientCore.kt), [`ClientRequest.kt`](../../raftlab-kv/src/main/kotlin/io/raftlab/kv/ClientRequest.kt), [`ClientResponse.kt`](../../raftlab-kv/src/main/kotlin/io/raftlab/kv/ClientResponse.kt)
- TCP client: [`TcpKvClient.kt`](../../raftlab-net/src/main/kotlin/io/raftlab/net/TcpKvClient.kt)
- Tests: [`KvStateMachineTest.kt`](../../raftlab-kv/src/test/kotlin/io/raftlab/kv/KvStateMachineTest.kt), [`KvClientCoreTest.kt`](../../raftlab-kv/src/test/kotlin/io/raftlab/kv/KvClientCoreTest.kt), [`ClientWriteScenarioTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/ClientWriteScenarioTest.kt), [`StaleReadScenarioTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/StaleReadScenarioTest.kt)
- Checker and how it is run: [testing.md](testing.md)
- Generated diagram of the first client write: [architecture](../generated/architecture.md)

## Paper

Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm (Extended Version)", §8.
