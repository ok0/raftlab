# Injected mutations

Mutations are for testing only. Each mutation is a deliberately injected safety bug. It is never enabled by default.
A mutation is enabled through `RaftConfig.mutations`, `Scenario.run(seed, mutations = ...)`, or `FuzzRunner(seeds, mutations)`.
The fuzz scenario `fuzz-random` is the detector used for every mutation below.

## SKIP_LOG_UP_TO_DATE_CHECK

- Raft rule broken: §5.4.1 election restriction. Votes are granted without the log up-to-date check.
- Config name: `Mutation.SKIP_LOG_UP_TO_DATE_CHECK`.
- Expected symptom: a candidate with a stale log wins an election and overwrites committed entries.
- Catching invariant or checker: LeaderCompleteness, StateMachineSafety, linearizability.
- Tested seed range: seeds 0..9 (`MutationDetectionTest.SKIP_RANGE`).
- Status: is detected within seeds 0..9.
- Reproduction: `raftlab scenario run fuzz-random --seed N --mutation SKIP_LOG_UP_TO_DATE_CHECK` (CLI arrives in M19).

## COMMIT_WITHOUT_MAJORITY

- Raft rule broken: majority commit. The leader advances commit once one replica fewer than a majority (counting itself) stores the entry.
- Config name: `Mutation.COMMIT_WITHOUT_MAJORITY`.
- Expected symptom: an entry is reported committed without a majority and can be lost after a leader change.
- Catching invariant or checker: LeaderCompleteness, StateMachineSafety, linearizability.
- Tested seed range: seeds 0..9 (`MutationDetectionTest.CWM_RANGE`).
- Status: is detected within seeds 0..9.
- Reproduction: `raftlab scenario run fuzz-random --seed N --mutation COMMIT_WITHOUT_MAJORITY` (CLI arrives in M19).

## COMMIT_PREVIOUS_TERM_ENTRIES

- Raft rule broken: §5.4.2 (Figure 8). The leader commits earlier-term entries by replica count, removing the current-term rule.
- Config name: `Mutation.COMMIT_PREVIOUS_TERM_ENTRIES`.
- Expected symptom: an earlier-term entry is committed and later overwritten.
- Catching invariant or checker: none observed in seeds 0..99; the fuzz scenario does not catch this mutation in that range.
- Tested seed range: seeds 0..99 (`MutationDetectionTest.CPTE_RANGE`).
- Status: is not detected within seeds 0..99.
- Targeted test: `RaftNodeMutationTest.commitPreviousTermEntriesCommitsOldEntryByCount`.
- Reproduction: `raftlab scenario run fuzz-random --seed N --mutation COMMIT_PREVIOUS_TERM_ENTRIES` (CLI arrives in M19).
