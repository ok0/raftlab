# Core and determinism

`RaftNode` in `raftlab-core` is a single-threaded state machine. A driver feeds it one `Input` at a time and carries out the `Effect`s it returns. The node itself has no clock, no sockets, no threads and no disk access: time, message delivery, storage, the application and randomness all enter through interfaces. The simulator (`raftlab-sim`) and the TCP runtime (`raftlab-net`) host the same class, which is why the same consensus code can be replayed deterministically in tests.

## The step function

`RaftNode` is constructed with `id`, `initialMembers`, a `RaftConfig`, a `RaftStorage`, a `StateMachine`, a `RandomSource` and a `TraceSink`. Its entry point is `fun step(input: Input): List<Effect>`, whose KDoc states that the same node state and input always produce the same effects.

- Inputs (`Input.kt`): `Tick`, `Receive(msg)`, `Propose(requestId, command)`, `ReadIndex(requestId)`, `ChangeMembership(requestId, add, remove)` and `TransferLeadership(requestId, target)`.
- Effects (`Effect.kt`): `Send(msg)`, `Applied(requestId, index, result)`, `Rejected(requestId, reason)` and `ReadReady(requestId, readIndex)`.

The driver must carry out the effects in the order returned. A `Send` effect names its destination in `msg.to`.

## Time, transport, storage, application, randomness

- **Time** is an input. The node counts `Input.Tick`s; `RaftConfig.electionTimeoutTicks` and `heartbeatTicks` are measured in ticks (see [leader-election.md](leader-election.md)). The node never reads a clock.
- **Transport** is an output. The node never writes to a connection; it returns `Effect.Send`, and the driver delivers the message back as `Input.Receive`.
- **Storage** is behind `RaftStorage`. Its contract says every write is durable when the call returns. `InMemoryStorage` is in `raftlab-core`; `FileStorage` is in `raftlab-storage-file`. See [persistence-and-snapshots.md](persistence-and-snapshots.md).
- **Application** is behind `StateMachine` (`apply`, `snapshot`, `restore`), and its `apply` must be deterministic. The key-value state machine is `KvStateMachine` in `raftlab-kv`.
- **Randomness** is behind `RandomSource`. `SplitMix64(seed)` implements it and yields the same sequence for the same seed. The node draws its election timeout from this source on every timer reset.

## Hosts

- **Simulator.** `HostedNode` in `raftlab-sim` wraps a `RaftNode` built from a seeded random source, and `Simulation` drives ticks and deliveries for the hosted nodes through its event queue.
- **TCP runtime.** `NodeRuntime` in `raftlab-net` builds a `RaftNode` with `SplitMix64(seed())` and a `RaftConfig()` with default values. Its ticker drives `Input.Tick`, and its loop routes `Effect.Send` over TCP.

The simulator and the runtime host the same class, but they are not interchangeable at the network level: the simulator delivers messages on its own virtual time, and the runtime uses real sockets and real ticks.

## Determinism and how it is tested

- Core level: `RaftNodeDeterminismTest` (raftlab-core) checks that identical inputs give identical outputs, and `SplitMix64Test` covers the generator.
- Simulator level: `DeterminismTest` (raftlab-sim) checks that the same seed gives the same trace bytes for a normal election, for a run with all faults enabled and for a crash and restart. It also checks that network faults do not change the random draws of the nodes.
- Golden traces: `TraceGoldenTest` checks that the traces of the scenario library are unchanged by comparing SHA-256 digests.
- Storage level: `FileStorageDeterminismTest` (raftlab-storage-file) covers `FileStorage`, whose KDoc says the same operations on an empty directory always produce the same bytes.

These tests cover the simulator and the storage code. They do not make a claim about the TCP runtime, where ticks and message timing come from the real system.

## Where to see it

- Step function and types: [`RaftNode.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/RaftNode.kt), [`Input.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/Input.kt), [`Effect.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/Effect.kt)
- Randomness: [`SplitMix64.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/SplitMix64.kt)
- Simulator host: [`Simulation.kt`](../../raftlab-sim/src/main/kotlin/io/raftlab/sim/Simulation.kt), [`HostedNode.kt`](../../raftlab-sim/src/main/kotlin/io/raftlab/sim/HostedNode.kt)
- TCP host: [`NodeRuntime.kt`](../../raftlab-net/src/main/kotlin/io/raftlab/net/NodeRuntime.kt)
- Determinism tests: [`DeterminismTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/DeterminismTest.kt), [`TraceGoldenTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/TraceGoldenTest.kt)
- Generated module and architecture diagrams: [architecture](../generated/architecture.md), [modules](../generated/modules.md)

## Paper

Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm (Extended Version)", Figure 2 and §5.1.
