# Contributing to raftlab

raftlab is an educational project that implements the Raft consensus algorithm with a deterministic simulator. Before proposing changes, read the [Status and limitations](README.md#status-and-limitations) section of the README so that contributions fit the project's scope.

## Prerequisites

- JDK 21 or newer. The Gradle toolchain targets Java 21. CI also builds the project with Gradle running on JDK 25.
- The Gradle wrapper (`./gradlew`). Do not install a separate Gradle.
- Node 22, only for the optional Mermaid validation step.
- Docker, optional. It is only needed for the container image.

## Workflow

1. Fork the repository on GitHub and clone your fork.
2. Create a branch from `main`.
3. Keep each pull request small and focused on one change.
4. Open the pull request against `main`.

## Checks a pull request must pass

The CI workflow in `.github/workflows/ci.yml` runs these jobs:

| CI job | What it runs | Local equivalent |
| --- | --- | --- |
| `Build (JDK 21/25)` | `./gradlew build`, which includes `ktlintCheck`, the tests, `verifyModuleBoundaries`, `checkDocs`, `checkRepoDocs`, `fuzzTest` and `koverVerify` through `check` | `./gradlew build` |
| `Build (JDK 21/25)` | Dokka documentation build | `./gradlew dokkaGenerate` |
| `Mermaid validate` | Validates the committed Markdown diagrams | `(cd .github/mermaid && npm ci) && scripts/validate-mermaid.sh` |
| `Cluster smoke (JDK 21)` | Starts a local cluster through the CLI | `./gradlew :raftlab-cli:installDist && scripts/cluster-smoke.sh` |
| `Release config check` | Checks the release workflow, Dependabot, release notes and compose configuration | `scripts/check-release-config.sh` |

`koverVerify` runs as part of `check` because `raftlab-core` sets `onCheck` for the Kover verification.

## Code style and API docs

- Format Kotlin with `./gradlew ktlintFormat` and check it with `./gradlew ktlintCheck`.
- Public API uses explicit API mode (`explicitApi()`). Every public declaration needs KDoc. `./gradlew dokkaGenerate` fails on undocumented public declarations.

## Generated docs

- Do not edit `docs/generated/` or the README region between the `BEGIN GENERATED:architecture` and `END GENERATED:architecture` markers. These files are produced by the generator.
- After changing a diagram or a scenario that appears in the docs, run `./gradlew generateDocs` and commit the result.
- `./gradlew checkDocs` fails when the committed generated docs are stale. It is part of `check`.

## Adding a scenario

1. Declare the scenario with the `scenario("<name>") { ... }` DSL in `raftlab-sim/src/main/kotlin/io/raftlab/sim/`. See `NormalElectionScenario.kt` for an example. The DSL is defined in `ScenarioFunction.kt` and `ScenarioDsl.kt`.
2. Register it in `ScenarioLibrary` in `raftlab-sim/src/main/kotlin/io/raftlab/sim/ScenarioLibrary.kt`. The library checks its own size in an `init` block, so update that count together with the list.
3. Add a test next to the existing ones, for example `raftlab-sim/src/test/kotlin/io/raftlab/sim/NormalElectionTest.kt`.

The CLI lists scenario names from `ScenarioLibrary`, so a registered scenario can be run with `raftlab scenario run <name> --seed <n>`.

## Adding a mutation

A mutation is a deliberately injected safety bug used for testing. Adding one touches three places:

1. Add the enum entry with its KDoc in `raftlab-core/src/main/kotlin/io/raftlab/core/Mutation.kt`.
2. Add a section in the existing format to `docs/design/mutations.md`. The `fuzzTest` task reads that file.
3. Add the detection expectations to `raftlab-sim/src/test/kotlin/io/raftlab/sim/MutationDetectionTest.kt`.

## Adding a diagram

Generated diagrams are declared in `raftlab-docs/src/main/kotlin/io/raftlab/docs/DocEntries.kt`. Add a `DocEntry` there, run `./gradlew generateDocs`, and commit the generated file. The index in `docs/generated/index.md` is updated by the same run.

## Measuring code size

Run `./gradlew countLoc` to print line counts for the main, test and other source sets.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/).

- Types: `feat`, `fix`, `docs`, `test`, `build`, `ci`, `refactor`, `chore`.
- Scope: the module name without the `raftlab-` prefix (`core`, `kv`, `sim`, `net`, `storage-file`, `trace`, `viz`, `docs`, `cli`), or one of `build`, `ci`, `release`.
- Subject: imperative, lowercase, no trailing period, 72 characters or fewer.
- Add `!` after the type or scope for breaking changes.
- Do not add attribution trailers such as `Co-authored-by` or generated-by lines.

Examples from the project history:

- `feat(viz): add offline HTML trace viewer and html export`
- `feat(cli): add raftlab command-line interface and cluster smoke script`
- `build(release): add tag-triggered release workflow, Docker image and compose example`
- `ci: harden CI with JDK matrix, coverage gate, Mermaid validation and smoke job`

## Before publishing a fork

Replace the placeholders listed in [docs/release.md](docs/release.md), section 8, before publishing a fork or a release.

## Code of conduct

Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).

## Security

Report security issues privately as described in [SECURITY.md](SECURITY.md). Do not open a public issue for them.
