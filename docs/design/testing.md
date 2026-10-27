# Testing

This note maps the test layers of raftlab to the code they exercise: unit tests of the consensus core, the seeded scenario library, invariant and linearizability checks, fuzzing, mutation testing, golden documents and traces, the coverage gate, and the end-to-end cluster script. Each layer lists the Gradle task or command that runs it.

## Layers

| Layer | What it checks | How to run |
|---|---|---|
| Core unit tests | `RaftNode` transitions, one behaviour per test class (for example election, commit, snapshot, membership, ReadIndex) | `./gradlew :raftlab-core:test` |
| Scenario library | Named, seeded runs in the simulator with expectations checked at the end | `raftlab scenario list`, `raftlab scenario run NAME --seed N` |
| Invariants | Safety properties checked over a run | run as part of scenarios and fuzz runs |
| Linearizability | Client histories checked against the key-value model | run by the scenario linearizability tests |
| Fuzzing | Random-seed scenarios with invariant and linearizability checks | `./gradlew :raftlab-sim:fuzzTest -Praftlab.fuzz.seeds=200` (default 1000), or `raftlab fuzz --seeds N` |
| Mutation tests | That injected bugs are detected, or are documented as not detected | `./gradlew :raftlab-sim:test` (`MutationDetectionTest`) |
| Golden documents and traces | Generated docs and scenario traces are unchanged | `./gradlew checkDocs` |
| Coverage gate | Line coverage of the consensus core | `./gradlew :raftlab-core:koverVerify` |
| End-to-end | A real three-node cluster over TCP | `scripts/cluster-smoke.sh` |

`./gradlew check` runs the fuzz task, the coverage gate and the docs check. `./gradlew test` excludes tests tagged `fuzz`; they run only through `fuzzTest`.

## Core unit tests

The consensus core is tested per behaviour, in `raftlab-core/src/test`: for example `RaftNodeElectionTest`, `RaftNodeCommitTest`, `RaftNodeSnapshotTest`, `RaftNodeMembershipTest`, `RaftNodeReadIndexTest`, `RaftNodeTransferTest`, `RaftNodePreVoteTest` and `RaftNodeDeterminismTest`. `ThreeNodeClusterTest` runs three nodes together in memory.

## Scenario library

The library has 10 scenarios, listed in `ScenarioLibrary.kt` in this order: `normal-election`, `leader-crash`, `partition-heal`, `minority-partition`, `client-write`, `fuzz-random`, `restart-all`, `lagging-follower-snapshot`, `membership-add-remove` and `leadership-transfer`. `restart-all` uses durable storage and needs a registered `DurableStorageProvider`. The scenarios are exercised by test classes in `raftlab-sim/src/test`, for example `NormalElectionTest`, `LeaderCrashScenarioTest`, `PartitionHealScenarioTest` and `RestartAllScenarioTest`; `ScenarioLibraryTest` checks the library itself.

## Invariants

Invariant checks live in `raftlab-sim/src/main/kotlin/io/raftlab/sim`: `Invariants.kt` combines the individual checkers `LeaderCompleteness.kt`, `LogMatching.kt`, `StateMachineSafety.kt`, `CommitMonotonicity.kt` and `MembershipSafety.kt`. `InvariantTest` and `LogInvariantsTest` exercise them, and `SnapshotInvariantsTest` covers the snapshot cases.

## Linearizability

`LinearizabilityChecker.kt` checks a recorded client history against the key-value model, and `LinSearch.kt` performs the search. `LinearizabilityCheckerTest` tests the checker on fixed histories, and `ScenarioLinearizabilityTest` runs it over scenario histories. The client-side behaviour that these histories exercise is described in [client-semantics.md](client-semantics.md).

## Fuzzing

`FuzzRunner.kt` runs a scenario over a range of seeds with injected bugs and classifies every failing seed; a run that throws is recorded as a run error and the sweep continues. The `fuzzTest` Gradle task runs the `fuzz`-tagged tests over `-Praftlab.fuzz.seeds` seeds (default 1000, from `raftlab-sim/build.gradle.kts`). `raftlab fuzz` runs the same runner from the command line.

## Mutation testing

`Mutation` in `raftlab-core` lists three deliberately injected bugs, which are enabled only through `RaftConfig.mutations`. `MutationDetectionTest` checks each one against its documented seed range, and `docs/design/mutations.md` records the expected symptom and status of each. One of them, `COMMIT_PREVIOUS_TERM_ENTRIES`, is not detected by the fuzz scenario in seeds 0..99; it is covered by the targeted unit test `RaftNodeMutationTest.commitPreviousTermEntriesCommitsOldEntryByCount`. See [mutations.md](mutations.md).

## Golden documents and traces

`./gradlew checkDocs` runs the docs check of `raftlab-docs`. `CommittedDocsTest` in `raftlab-docs` reads the committed generated documents, and `TraceGoldenTest` in `raftlab-sim` checks that library scenario traces are unchanged by comparing SHA-256 digests. Regenerate documents with `./gradlew generateDocs` after an intended change.

## Coverage gate

`raftlab-core/build.gradle.kts` sets the minimum line coverage of the consensus core. The default is 85 percent (`raftlab.coverage.coreMinLine`), and `koverVerify` is attached to `check` through `onCheck`.

## Style and build checks

`./gradlew ktlintCheck` runs the ktlint checks configured in `build-logic`. `./gradlew verifyModuleBoundaries` checks the module dependency rules in [modules](../generated/modules.md).

## End-to-end cluster

`scripts/cluster-smoke.sh` starts three `raftlab node` processes over TCP, runs KV operations, kills one node with SIGKILL, restarts it on the same data directory, and checks that acknowledged writes remain readable after a second node is killed. It runs in the CI `smoke` job. Build the distribution first with `./gradlew :raftlab-cli:installDist`.

## Mermaid validation

CI job `mermaid-validate` runs `scripts/validate-mermaid.sh` on the committed Markdown, after a self-test that checks a broken diagram is rejected. The diagrams in `docs/generated` are produced by `raftlab-docs`, and the `.github/mermaid` directory holds the pinned Mermaid tool.

## Limitations

- Fuzzing covers only the seeds it is run with; a clean run over a seed range does not prove the absence of bugs.
- The scenario library and the fuzz scenario are simulated; the TCP end-to-end check runs three nodes on one machine.
- Mutation status is recorded per seed range and may change when the seed range or the scenario changes.

## Where to see it

- Invariants: [`Invariants.kt`](../../raftlab-sim/src/main/kotlin/io/raftlab/sim/Invariants.kt)
- Linearizability checker: [`LinearizabilityChecker.kt`](../../raftlab-sim/src/main/kotlin/io/raftlab/sim/LinearizabilityChecker.kt)
- Mutation detection: [`MutationDetectionTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/MutationDetectionTest.kt)
- Fuzz scenario tests: [`FuzzScenarioTest.kt`](../../raftlab-sim/src/test/kotlin/io/raftlab/sim/FuzzScenarioTest.kt)
- Committed docs: [`CommittedDocsTest.kt`](../../raftlab-docs/src/test/kotlin/io/raftlab/docs/CommittedDocsTest.kt)
- End-to-end script: [`cluster-smoke.sh`](../../scripts/cluster-smoke.sh)
- Mermaid validation: [`validate-mermaid.sh`](../../scripts/validate-mermaid.sh)
- Mutations: [mutations.md](mutations.md)

## Paper

Ongaro & Ousterhout, "In Search of an Understandable Consensus Algorithm (Extended Version)", §5.4 (safety), whose properties the invariant checks and the fuzz runs test.
