# Membership change

This note describes how RaftLab changes the set of voting servers at runtime. The protocol is single-server: each
change adds or removes exactly one server.

## Single-server change

A membership change is one `Config` log entry carrying the full new configuration. The entry takes effect on the
leader and on every follower as soon as it is appended to their log, not when it commits. A leader accepts a new
change only when no other change is pending, so at most one `Config` entry is uncommitted at any time.

## Adding a server

A joining server starts with an initial configuration that may exclude itself. It never campaigns until a log
it receives puts it in the configuration.

When the leader receives an add request it first catches the new server up without counting it in the quorum. The
catch-up target is the leader's commit index at request time. If the new server is behind the leader's snapshot, the
leader sends `InstallSnapshot`. When the new server's match index reaches the target, the leader appends the `Config`
entry with the new server included.

If the catch-up makes no progress for 10 times the maximum election timeout, the leader abandons it and the request
fails with `Unknown`. If the leader loses leadership during catch-up, the request fails with `NotLeader`.

Votes from a server in catch-up do not count, and the catch-up server's replies do not count towards commit.

## Busy conditions

A membership request is rejected with `Busy` when any of the following holds:

- a `Config` entry is uncommitted;
- a catch-up is in progress;
- the leader has not yet committed an entry of its own term.

`Busy` changes no state and the caller may retry later. At the key-value layer, `Busy` and `Unknown` map to
`KvResult.Timeout`, and `NotLeader` maps to `KvResult.NotLeader` with the hint.

## Removing a server

A remove request appends the `Config` entry immediately, with the server excluded. A removed leader keeps replicating
to the rest of the cluster without counting itself towards the quorum. When the `Config` entry commits, the leader
emits `Applied` for the request, steps down, and never campaigns again. A server outside its own configuration never
campaigns.

## Effective configuration and snapshots

The effective configuration of a server is the latest `Config` entry in its retained log. If the log has none, it is
the configuration stored in the snapshot. If there is no snapshot either, it is the initial configuration. A snapshot
records the configuration in effect at the snapshot index.

On restart the server restores the effective configuration with the same rules. A `ConfigChanged` event is emitted
when the effective configuration changes: when a `Config` entry is appended or truncated, when a snapshot is installed,
and on a restart that restores a set different from the initial configuration. A start that restores the initial
configuration emits nothing.

## Known limitation

Messages other than AppendEntries and InstallSnapshot from non-members are dropped. A removed server that keeps running could still disturb members whose configuration lags. Since M16, pre-vote (on by default) stops this while those members keep hearing the leader; see pre-vote-and-transfer.md. With `preVote = false` the limitation remains. There is no lease check on the leader side.

## Simulator

`Simulation.addNode` creates the next node `n(N+1)` with empty storage and starts it. `Simulation.removeNode` queues a
removal. Membership changes run one at a time in call order. A change that finds no live leader is retried every 50 ms
of virtual time. Once a removal commits, the removed node is stopped for good and is no longer listed in `nodeIds`.
Later crash, restart and partition requests for it are ignored.

The standard invariant set includes `MembershipSafety`, which checks that committed configurations do not diverge, that
committed configurations change one server at a time, and that a leader does not hold two uncommitted configuration
entries.
