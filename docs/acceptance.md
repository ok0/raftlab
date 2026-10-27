# Acceptance evidence

This page records the observed result of each acceptance criterion (PAC1 to PAC12) for M24 release readiness. Run date: 2026-10-27. All values are copied from a real run; nothing is estimated.

Source logs are named by file (for example `a1-build.log`). They are kept in the job scratch directory `/Users/yoonjonghun/.claude/jobs/e846cdab/tmp/m24/logs`, outside the repository.

## Environment

- Commit: `395efc0024e19af6ab4ed2cfdd591ec44d4ab346` (`a0-sha.log`). The working tree was clean before the run (`a0-status.log`).
- Clone: `git clone <repo> <tmpdir>`, where `<tmpdir>` is a new empty scratch directory outside the working tree. The clone was removed after the run.
- JDK: `openjdk version "21.0.7" 2025-04-15 LTS` (Corretto 21.0.7.6.1) (`a0-env.log`).
- Gradle: 9.8.2 via the wrapper, Kotlin 2.4.10, Groovy 4.0.33 (`a0-env.log`).
- OS: macOS 15.7.7, Darwin 24.6.0, arm64 (`a0-env.log`, `a0-host.log`).
- CPU: Apple M4 Pro; 14 logical CPUs (`hw.ncpu`); 24 GiB RAM (`hw.memsize` 25769803776) (`a0-host.log`).
- Docker: daemon not running (`docker info` exit 1, `a6-smoke.log`). Docker steps were not run locally.

## PAC1 fresh-clone build

Sources: `a0-env.log`, `a0-sha.log`, `a1-build.log`.

```text
openjdk version "21.0.7" 2025-04-15 LTS
395efc0024e19af6ab4ed2cfdd591ec44d4ab346
BUILD SUCCESSFUL in 54s
real 54.54
exit=0
```

## Results

| PAC | Description | Kind | Command or procedure | Observed result | Charter R# |
|-----|-------------|------|----------------------|-----------------|------------|
| PAC1 | Fresh clone builds with the full Gradle build on JDK 21 | AUTOMATED | `git clone`, then `./gradlew build --console=plain` (`a1-build.log`) | Exit 0; `BUILD SUCCESSFUL in 54s`; real 54.54 s | R22, R23, R26 |
| PAC2 | Same seed gives an identical trace; a different seed gives a different trace | AUTOMATED | `raftlab scenario run normal-election` with `--seed 1` (twice, `--trace` to separate files) and `--seed 2`, compared with `cmp`; test classes NormalElectionTest, DeterminismTest, TraceGoldenTest (`a3-pac2.log`) | `same=0`, `differ=1`. SHA-256 of both seed-1 traces: `714596c587e25d617c865c4a0d9cc9061826e1bb45c19368d4ba3f516f0663d2`; seed 2: `fe68483d3448f516c3e07a959232ab6bf8246d7dbef5a213fbd79748b334f41c`. NormalElectionTest 2 tests, 0 failures; DeterminismTest 4 tests, 0 failures; TraceGoldenTest 1 test, 0 failures | R1, R9, R15 |
| PAC3 | Fuzzing over 1000 seeds finds no failures; an injected mutation is detected and reproducible from its seed | AUTOMATED | `raftlab fuzz` with 1000 seeds (`a4-fuzz.log`); `./gradlew :raftlab-sim:fuzzTest` (`a2-tests.log`); `raftlab fuzz` with 200 seeds and `--mutation SKIP_LOG_UP_TO_DATE_CHECK` (`a4-mutation.log`); `raftlab scenario run fuzz-random --seed 0` with the same mutation (`a4-repro.log`) | Seeds 0 to 999: 1000 run, 1000 passed, 0 failed, exit 0. Mutation run over seeds 0 to 199: 160 passed, 40 failed, exit 1; first failing seed S = 0. Repro for seed 0: exit 1, `result: FAILED (INVARIANT)`, 12 violation lines, linearizability LINEARIZABLE (56 states explored). FuzzScenarioTest 1 test, 0 failures; FuzzSuiteTest 1 test, 0 failures; MutationDetectionTest 4 tests, 0 failures | R2, R9, R11, R12, R13 |
| PAC4 | Scenario tests for leader crash, minority partition, partition heal, lagging follower snapshot and membership add/remove pass | AUTOMATED | JUnit XML counts from the test task (`a5-scenarios.log`) | LeaderCrashScenarioTest 1 test, 0 failures, 0 errors; MinorityPartitionScenarioTest 1, 0, 0; PartitionHealScenarioTest 1, 0, 0; LaggingFollowerSnapshotScenarioTest 2, 0, 0; MembershipAddRemoveScenarioTest 3, 0, 0 | R2, R4, R5, R10 |
| PAC5 | Restart-all scenario, restart CLI and file storage reopen pass | AUTOMATED | JUnit XML counts from the test task (`a5-scenarios.log`) | RestartAllScenarioTest 7 tests, 0 failures, 0 errors; RestartAllCliTest 2, 0, 0; FileStorageReopenTest 6, 0, 0 | R3, R8 |
| PAC6 | Three-node cluster smoke test and restart; docker compose demo | AUTOMATED + MANUAL | `RAFTLAB_SMOKE_TIMEOUT=120 scripts/cluster-smoke.sh` (`a6-smoke.log`); RestartTest XML counts (`a6-smoke.log`); docker compose demo is MANUAL, not run locally (Docker daemon unavailable). See [checklist item](#post-push-checklist) and [docker/README.md](../docker/README.md#pac6-demo) | Smoke: `PASS: cluster smoke`, exit 0, real 5.32 s. RestartTest 3 tests, 0 failures, 0 errors. No leftover io.raftlab processes. Docker demo: MANUAL, not run locally | R6, R8, R14, R21, R27 |
| PAC7 | Generated docs are reproducible and checkDocs detects a hand edit | AUTOMATED | `./gradlew generateDocs` then `git status --porcelain` (`a7-docs.log`); hand edit to `docs/generated/election.md`, then `./gradlew checkDocs`, then `git checkout` of that file (`a7-docs.log`) | generateDocs exit 0 and empty `git status`. checkDocs after hand edit: exit 1, message `differs: election.md`. After restore: empty `git status` | R16, R17, R18, R19 |
| PAC8 | Six Mermaid diagrams render as diagrams on GitHub | MANUAL | Six diagrams listed in the [post-push checklist](#post-push-checklist), item 12 | not run (GitHub render) | R16, R17, R18 |
| PAC9 | raftlab-core line coverage is at least 85 % | AUTOMATED + MANUAL | `./gradlew :raftlab-core:koverVerify -Praftlab.coverage.coreMinLine=100` probe (`a8-kover-probe.log`); `./gradlew :raftlab-core:koverVerify` (`a8-kover.log`); `./gradlew :koverXmlReport` with `scripts/coverage-summary.py` (`a8-coverage.log`). CI report part: [checklist item](#post-push-checklist) | Probe exit 1 (expected): measured raftlab-core line coverage 98.218300 %. koverVerify exit 0. Coverage summary: raftlab-core 882 of 898 lines, 98.22 %; overall 4857 of 4970 lines, 97.73 % | R23 |
| PAC10 | Repository docs, release configuration and distribution checks pass | AUTOMATED + MANUAL | `./gradlew checkRepoDocs` and badge and marker counts (`a8-repodocs.log`); `scripts/check-release-config.sh` (`a8-release-config.log`); `./gradlew :raftlab-cli:distZip -Praftlab.version=1.2.3` with `scripts/check-dist.sh` (`a8-dist.log`). CI green and tag run: [checklist item](#post-push-checklist), [release.md §7](release.md#7-post-push-manual-checklist), [§5](release.md#5-verifying-a-release), [§6](release.md#6-first-run-caveats) | checkRepoDocs: 10 required files present, 26 Markdown files, 146 relative links resolved, exit 0. Badge lines 6 to 7: 5 badges. `BEGIN GENERATED:architecture` markers: 1. check-release-config exit 0 (`PASS: release config OK`; actionlint not found, skipped). check-dist exit 0 (`PASS`, zip 1.2.3) | R24, R25 |
| PAC11 | Lines of code count per module | AUTOMATED | `./gradlew countLoc` (`a8-loc.log`) | Total (main + test + other): 24537 lines (22122 non-blank). TOTAL row: main 11815 (10669 non-blank), test 12480 (11233 non-blank), other 242 (220 non-blank) | P6 |
| PAC12 | Cold build and test time are within the local runtime budget | AUTOMATED + MANUAL | Cold `./gradlew build` (`a1-build.log`); `./gradlew test fuzzTest --rerun-tasks` (`a2-tests.log`); JUnit XML time sums (`a2-junit.log`). CI time: [checklist item](#post-push-checklist) | Build real 54.54 s. Test real 48.50 s. JUnit sum 77.4 s over 111 XML files, 615 tests (`a2-junit.log`). CI time not run locally | R9, R23 |

Any failed step would be listed here as `FAIL:` with a link to [Defects](#defects). No step failed unexpectedly; see Defects for the expected non-zero probes.

## Runtime budget (PAC12)

- Build: `./gradlew build` cold run, 54.54 s against the 300 s budget. Margin 245.46 s (81.8 % of the budget). Verdict: PASS.
- Test: `./gradlew test fuzzTest --rerun-tasks`, 48.50 s against the 300 s budget. Margin 251.50 s (83.8 % of the budget). Verdict: PASS. This wall-clock time includes recompilation, so it is an upper bound.

CI does not use the configuration cache. The CI `build` step reports its own time, which is recorded by the post-push checklist (item 6).

## Defects

None found.

The three non-zero exits in the sweep were deliberate probes and are expected results: the mutation run (exit 1), the koverVerify probe at a 100 % threshold (exit 1), and the checkDocs run after a hand edit (exit 1).

## Post-push checklist

- [ ] 1. Replace the repository placeholder in `README.md` (badge comment, badge URLs, clone URL) — the search command in release.md §8 prints nothing for `README.md` — [release.md §8](release.md#8-before-the-first-push-placeholders-and-repository-settings)
- [ ] 2. Fill the contact placeholder in `CODE_OF_CONDUCT.md` — the search command in release.md §8 prints nothing for `CODE_OF_CONDUCT.md` — [release.md §8](release.md#8-before-the-first-push-placeholders-and-repository-settings)
- [ ] 3. Enable private vulnerability reporting — Settings shows it enabled, and the `SECURITY.md` reporting route works — [release.md §8](release.md#8-before-the-first-push-placeholders-and-repository-settings)
- [ ] 4. Push; CI workflow green on the default branch, including the JDK 25 matrix leg — all jobs green — [ci.yml](../.github/workflows/ci.yml)
- [ ] 5. `mermaid-validate` job green, including its self-test on `.github/mermaid/broken-sample.md` (mmdc runs in the CI sandbox) — job green — [ci.yml](../.github/workflows/ci.yml)
- [ ] 6. CI total time under 5 minutes, and the `build` step summary shows `./gradlew build` under 300 s — record the number — [ci.yml](../.github/workflows/ci.yml)
- [ ] 7. Coverage summary in the CI job summary shows raftlab-core at 85 % or more, and the coverage artifact is uploaded (PAC9 CI part) — [ci.yml](../.github/workflows/ci.yml)
- [ ] 8. Release dry run (`workflow_dispatch`) green — [release.md §7](release.md#7-post-push-manual-checklist) item 1 and [§3](release.md#3-dry-run)
- [ ] 9. First `vX.Y.Z` tag run: Release with zip, tar and SHA256SUMS; GHCR push; package made public; `docker build`, `docker run` and `scripts/check-image.sh` pass — [release.md §7](release.md#7-post-push-manual-checklist) items 2-3, [§5](release.md#5-verifying-a-release), [§6](release.md#6-first-run-caveats), [§4](release.md#4-local-equivalent)
- [ ] 10. `docker compose up` PAC6 demo: put and get work, kill n2, reads succeed, restart, data readable — [docker/README.md](../docker/README.md#pac6-demo) ([release.md §7](release.md#7-post-push-manual-checklist) item 4)
- [ ] 11. HTML viewer offline: run `raftlab export --scenario leader-crash --format html --out build/leader-crash.html`, open it with the network off, and check that Prev, Next and Play/Pause step through events
- [ ] 12. PAC8 GitHub rendering: each of the six diagrams renders as a diagram, not a code block, on github.com: election sequence [generated/election.md](generated/election.md); replication sequence with catch-up [generated/replication.md](generated/replication.md); role states over leader crash [generated/roles-leader-crash.md](generated/roles-leader-crash.md); partition-heal log table [generated/partition-heal-log.md](generated/partition-heal-log.md); architecture flowchart [generated/architecture.md](generated/architecture.md) (also in the README); module dependency flowchart [generated/modules.md](generated/modules.md)

## Placeholder scan

The search for the two repository placeholder tokens was run on the committed tree with `git grep -n`. Hits, by file and line:

- `README.md`: lines 5, 6, 56 (badge comment, badge URLs, clone URL; replaced in checklist item 1)
- `CODE_OF_CONDUCT.md`: lines 63, 69 (contact placeholder; replaced in checklist item 2)
- `docs/release.md`: lines 84, 85, 88 (§8 instructions only; these name the tokens as text to search for and are not placeholders)

`docs/acceptance.md` has no hits.
