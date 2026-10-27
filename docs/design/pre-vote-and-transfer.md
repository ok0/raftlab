# Pre-vote and leadership transfer

## Pre-vote

- The `RaftConfig.preVote` default is on.
- On a timeout, a non-leader in its own configuration becomes PRE_CANDIDATE. This applies to a FOLLOWER, a PRE_CANDIDATE or a CANDIDATE. It sends `RequestVote(preVote = true, term = currentTerm + 1)`. It does not change its term or vote and writes nothing to storage.
- A single-member cluster elects itself directly.
- Grant rules: the requester is a member, the proposed term is greater than the responder's term, the requester's log is up to date, and the responder has not heard a leader within `electionTimeoutTicks.first` ticks. A leader always denies.
- Granting changes nothing on the responder and is not traced as `VoteCast`.
- A granted response carries the proposed term. A denial carries the responder's term, and a higher term is adopted.
- A majority of pre-votes starts the real election with the reason "pre-vote granted".
- Pre-vote traffic is ordinary `REQUEST_VOTE` / `REQUEST_VOTE_RESPONSE` messages with `preVote=true` in the summary.
- A repeated round emits no `RoleChanged`.
- With `preVote = false`, pre-vote messages are ignored and traces are byte-identical to the behavior before M16.

## Leader stickiness and removed servers

An isolated node or a removed server that never learned of its removal cannot raise any member's term while the members keep hearing the leader. This closes the M14 limitation. Messages other than AppendEntries/InstallSnapshot from non-members were already dropped. The remaining risk was members with a lagging configuration. There is no CheckQuorum or lease.

## Leadership transfer

- `Input.TransferLeadership(requestId, target)` and the order of its rejections: NotLeader, then Unknown (self, non-voting target, leader outside its configuration), then Busy (transfer, membership change or catch-up in progress).
- On acceptance the leader traces `LeadershipTransfer(target)`. It answers `Propose`/`ChangeMembership` with Busy, and `KvServer` maps Busy to `Timeout`. ReadIndex is still served.
- The leader brings the target up to date, using InstallSnapshot when needed, and sends `TimeoutNow` once when `matchIndex == lastIndex`.
- The target starts a real election at once with the reason "leadership transfer" and no pre-vote.
- The transfer ends on step-down (`NotLeader(hint)`) or after `electionTimeoutTicks.last` ticks (`Unknown`, after which proposals resume). Success is observed through roles.
- Not exposed through KV, net or the CLI.
