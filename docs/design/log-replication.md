# Log replication

This note describes how the leader replicates its log with `AppendEntries`, how a follower resolves conflicts, and when an entry becomes committed and applied. The election that precedes replication is in [leader-election.md](leader-election.md); snapshot transfer is in [persistence-and-snapshots.md](persistence-and-snapshots.md).

## AppendEntries and the consistency check

For each peer the leader keeps `nextIndex`, which starts at the leader's last index plus one. `sendAppendEntries` sends the entries from `nextIndex` onwards, at most `RaftConfig.maxEntriesPerAppend` (default 64), together with `prevLogIndex = nextIndex - 1` and the term of that entry. If `nextIndex` is at or below the leader's snapshot index, the leader sends an InstallSnapshot instead.

A follower accepts an `AppendEntries` only when its own entry at `prevLogIndex` has the same term (`handleAppendEntries`). Otherwise it replies with a failure that carries a conflict hint:

- If the follower's log is shorter than `prevLogIndex`, the hint is its last index plus one and no conflicting term is reported.
- If the entry at `prevLogIndex` has a different term, the hint is the first index of that conflicting term in the follower's log, and the conflicting term is reported.

Entries at or below the follower's snapshot index are skipped. For the remaining entries, the follower truncates its log from the first entry whose term differs from the one it holds, and appends the rest.

## Backtracking

On a failure, the leader sets `nextIndex` (`applyAppendResult`) to the smaller of two values:

- If the conflicting term is reported and the leader has entries of that term, the position just after the leader's last entry of that term; otherwise the follower's hint.
- The current `nextIndex` minus one.

The result is never below the peer's `matchIndex + 1` or below index 1. So the leader moves back by at least one entry per failure, and it jumps directly to the conflict term when the hint is lower. This is term-based backtracking, not a search by index.

## Commit rule

On each successful `AppendEntries` response the leader raises `matchIndex` for that peer and calls `advanceLeaderCommit`. The leader's own last index counts as a match. The new commit index is the entry at position `members.size / 2` of the match indexes sorted in descending order, which is stored on a majority of the current configuration.

That candidate index is committed only when `isCommittable` holds: the entry at that index has the leader's current term (Raft §5.4.2). An entry from an earlier term is committed indirectly, when an entry of the current term above it reaches a majority. To make this possible right after an election, `becomeLeader` appends a no-op entry in the new term.

A follower sets its commit index to the smaller of the leader's commit index and the index of the last entry it received. Committed entries are applied in order by `applyCommitted`, which emits `Effect.Applied`. The `requestId` is set only for proposals made by this node during its current leadership.

## Mutations

Both mutations are test-only, enabled through `RaftConfig.mutations`:

- `COMMIT_WITHOUT_MAJORITY` moves the commit position one replica lower, so an entry stored on one replica fewer than a majority can be committed. [mutations.md](mutations.md) lists the detection range.
- `COMMIT_PREVIOUS_TERM_ENTRIES` removes the current-term check in `isCommittable`, so an earlier-term entry can be committed by count. In seeds 0..99 the fuzz scenario does not detect this mutation; the targeted unit test `RaftNodeMutationTest.commitPreviousTermEntriesCommitsOldEntryByCount` covers it. See [mutations.md](mutations.md).

## Catching up a lagging follower

A follower whose `nextIndex` falls to or below the leader's snapshot index receives an InstallSnapshot, and then `AppendEntries` for the entries after the snapshot. The generated replication diagram shows this for the `lagging-follower-snapshot` scenario. The snapshot format and its storage are described in [persistence-and-snapshots.md](persistence-and-snapshots.md).

## Where to see it

- Replication and commit logic: [`RaftNode.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/RaftNode.kt) (`sendAppendEntries`, `handleAppendEntries`, `applyAppendResult`, `advanceLeaderCommit`, `isCommittable`)
- Rejection values: [`Rejection.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/Rejection.kt)
- Unit tests: [`RaftNodeCommitTest.kt`](../../raftlab-core/src/test/kotlin/io/raftlab/core/RaftNodeCommitTest.kt), [`RaftNodeMutationTest.kt`](../../raftlab-core/src/test/kotlin/io/raftlab/core/RaftNodeMutationTest.kt)
- Simulated replication: [`ReplicationSimTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/ReplicationSimTest.kt)
- Generated diagrams: [replication to a lagging follower](../generated/replication.md), [logs across a partition and heal](../generated/partition-heal-log.md)

## Paper

Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm (Extended Version)", §5.3, §5.4.2 and Figure 8.
