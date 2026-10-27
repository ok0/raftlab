# Persistence and snapshots

This note describes what a Raft node must store durably, how the file-based storage lays that data out on disk, when snapshots are taken, and how a lagging follower receives one. The storage interface is `RaftStorage` in `raftlab-core`; its file implementation is `FileStorage` in `raftlab-storage-file`.

## What must be durable, and before what

`RaftStorage` says every write is durable when the call returns. The node relies on this ordering:

- Term and vote are saved before the node answers a vote request or starts sending one. `handleRequestVote` calls `persist()` before it returns the vote response, and `startElection` persists the new term before it returns the `RequestVote` messages.
- A term adopted from a higher-term message is saved in `stepDownToHigherTerm`.
- Entries are appended before the node replies to `AppendEntries` (`appendToLog` runs before `replyAppend`).

The storage keeps indexes starting at 1, with index 0 as the empty prefix. `compactTo(index)` requires a saved snapshot whose last index is at least `index`, and it drops entries at or below `index`.

## FileStorage layout

`FileStorage` keeps one node's data in a directory (see its KDoc in `FileStorage.kt`):

- `state.json`: the term and `votedFor`, replaced atomically on every save.
- `log.dat`: a 12-byte header with a magic value and the compacted index, followed by records. Each record is a 4-byte length, a CRC32 of the payload and the UTF-8 JSON of one entry (`LogCodec.kt`). Integers are big-endian.
- `snapshot.bin` and `snapshot.meta.json`: the raw snapshot bytes and the metadata with the data length and CRC32, both replaced atomically (`AtomicFiles.kt`, `SnapshotFile.kt`).

Every write is fsynced before the call returns.

## Reopening a directory

On open, `FileStorage` deletes leftover `*.tmp` files from interrupted replacements. A torn or damaged log tail is discarded from the first invalid record and truncated away. If the snapshot's last index is above the log's compacted index, which happens when a crash falls between saving the snapshot and compacting the log, the open completes the compaction. A snapshot whose data does not match its metadata makes the open fail.

When the node starts with a snapshot, `RaftNode` compacts the log through it, restores the state machine from the snapshot bytes, and takes the membership from the snapshot metadata.

## Snapshots and log compaction

`RaftNode.maybeTakeSnapshot` takes a snapshot once `lastApplied - snapshotIndex` exceeds `RaftConfig.snapshotThreshold`, which defaults to 1000 applied entries (`RaftConfig.DEFAULT_SNAPSHOT_THRESHOLD`). It saves the state machine bytes with the last index, last term and membership at that index, then compacts the log through that index.

The snapshot metadata carries the membership, so a node restored from a snapshot knows its configuration without the compacted `Config` entries. The membership rules are in [membership.md](membership.md).

## InstallSnapshot to lagging followers

When a follower's `nextIndex` is at or below the leader's snapshot index, the leader cannot send the entries it needs and sends an InstallSnapshot instead (`sendInstallSnapshot`). After the follower installs it, normal `AppendEntries` continues from the snapshot. The replication side of this is in [log-replication.md](log-replication.md).

## Limitations

- `FileStorage` assumes one instance owns a directory at a time; opening a second instance on a directory that is in use is not supported (its KDoc).
- I/O errors propagate to the caller of `FileStorage`.
- In the TCP runtime, a storage error is fail-stop: the node loop records the failure and the node stops itself (`NodeRuntime` KDoc).

This project does not document or test recovery beyond the cases listed above and in the tests below.

## Where to see it

- Storage contract: [`RaftStorage.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/RaftStorage.kt)
- File storage: [`FileStorage.kt`](../../raftlab-storage-file/src/main/kotlin/io/raftlab/storage/FileStorage.kt), [`LogCodec.kt`](../../raftlab-storage-file/src/main/kotlin/io/raftlab/storage/LogCodec.kt), [`AtomicFiles.kt`](../../raftlab-storage-file/src/main/kotlin/io/raftlab/storage/AtomicFiles.kt), [`SnapshotFile.kt`](../../raftlab-storage-file/src/main/kotlin/io/raftlab/storage/SnapshotFile.kt)
- Storage tests: [`FileStorageContractTest.kt`](../../raftlab-storage-file/src/test/kotlin/io/raftlab/storage/FileStorageContractTest.kt), [`TornLogTest.kt`](../../raftlab-storage-file/src/test/kotlin/io/raftlab/storage/TornLogTest.kt), [`FileStorageSnapshotTest.kt`](../../raftlab-storage-file/src/test/kotlin/io/raftlab/storage/FileStorageSnapshotTest.kt)
- Node snapshot tests: [`RaftNodeSnapshotTest.kt`](../../raftlab-core/src/test/kotlin/io/raftlab/core/RaftNodeSnapshotTest.kt)
- Simulated snapshot catch-up: [`LaggingFollowerSnapshotScenarioTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/LaggingFollowerSnapshotScenarioTest.kt)
- Simulated restart with durable storage: [`RestartAllScenarioTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/RestartAllScenarioTest.kt)
- Generated diagram: [replication to a lagging follower](../generated/replication.md)

## Paper

Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm (Extended Version)", Figure 2 (persistent state) and §7.
