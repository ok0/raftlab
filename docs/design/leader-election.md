# Leader election

This note describes how `RaftNode` elects a leader: terms, the randomized election timer, the optional pre-vote round, vote granting with the up-to-date check, and stepping down. Pre-vote and leadership transfer are described in [pre-vote-and-transfer.md](pre-vote-and-transfer.md) and are not repeated here.

## Terms and roles

`currentTerm` is the latest term the node has seen. A node that starts an election increments it, votes for itself and persists both values before it sends any request. A node that receives a message with a higher term calls `stepDownToHigherTerm`: it adopts the term, clears `votedFor`, persists the state and becomes a follower. A leader that steps down abandons its pending requests; the rejections it reports are described in the KDoc of `Input` and `Rejection`.

## Randomized election timeout

Each follower or candidate has an election timer, measured in ticks. Every time the timer is reset, the node draws its timeout uniformly from `RaftConfig.electionTimeoutTicks`, whose default is `10..20`, using its `RandomSource`. The timer is reset when the node hears from a current leader, grants a vote, or starts a round.

`RaftConfig.heartbeatTicks` defaults to 3 and must be below the minimum election timeout; `RaftConfig`'s `init` block enforces this. A leader sends `AppendEntries` as heartbeats every `heartbeatTicks` ticks.

A single-member configuration starts an election directly, without a pre-vote round.

## Pre-vote

With `RaftConfig.preVote` enabled (the default), a node whose timer expires first becomes `PRE_CANDIDATE` and asks each peer whether it would vote for it in the next term. The term and the vote are not changed by the pre-vote round. Only after a majority would vote for it does the node start a real election. `preVote = false` restores the behaviour without a pre-vote round.

A peer refuses a pre-vote when it is the leader or when it heard from a leader within the minimum election timeout (leader stickiness, `handlePreVoteRequest`).

## Starting an election and granting votes

`startElection` increments `currentTerm`, sets `votedFor` to the node itself, calls `persist()` and only then returns the `RequestVote` messages. The node becomes leader at once when it already has a majority of the current configuration.

`handleRequestVote` grants a vote only when `votedFor` is null or already names the candidate, and when the candidate's log is at least as up to date as the local log (Raft §5.4.1): a higher last term wins, and for equal last terms the longer or equal index wins (`logUpToDate`). When it grants a vote, the node sets `votedFor`, calls `persist()` and resets its election timer before it returns the `RequestVoteResponse` effect. A vote is therefore durable before the reply is sent.

The mutation `SKIP_LOG_UP_TO_DATE_CHECK` makes `logUpToDate` return true, so votes are granted without the §5.4.1 comparison. See [mutations.md](mutations.md) for the expected symptom and the detection range.

## Split votes

When a candidate's timer expires again without a majority, the timer's expiry is handled like any follower's: with `preVote` enabled the node runs a new pre-vote round first, and otherwise it calls `startElection` directly. Either way a new election term is used only after the round succeeds (pre-vote) or immediately (no pre-vote), and the timer is redrawn.

## What is not in this note

- Pre-vote and leader stickiness details, and leadership transfer (`TimeoutNow`): [pre-vote-and-transfer.md](pre-vote-and-transfer.md).
- How the new leader brings followers up to date: [log-replication.md](log-replication.md).

## Where to see it

- Election logic: [`RaftNode.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/RaftNode.kt) (`startElection`, `handleRequestVote`, `logUpToDate`, `stepDownToHigherTerm`)
- Timing defaults and validation: [`RaftConfig.kt`](../../raftlab-core/src/main/kotlin/io/raftlab/core/RaftConfig.kt)
- Unit tests: [`RaftNodeElectionTest.kt`](../../raftlab-core/src/test/kotlin/io/raftlab/core/RaftNodeElectionTest.kt), [`RaftNodeVotingTest.kt`](../../raftlab-core/src/test/kotlin/io/raftlab/core/RaftNodeVotingTest.kt)
- Simulated election: [`NormalElectionTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/NormalElectionTest.kt)
- Generated diagrams: [election](../generated/election.md), [node roles during election](../generated/roles-election.md), [node roles across a leader crash](../generated/roles-leader-crash.md)

## Paper

Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm (Extended Version)", §5.2 and §5.4.1.
