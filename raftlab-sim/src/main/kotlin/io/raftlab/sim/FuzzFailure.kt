package io.raftlab.sim

import io.raftlab.core.Mutation

/**
 * One failing seed of a fuzz run, with the injected bugs that were active for it.
 *
 * @property scenario Name of the scenario that failed.
 * @property seed Seed of the failing run.
 * @property kind Classification of the failure.
 * @property detail Human-readable description of the failure.
 * @property mutations Injected bugs that were applied to the failing run.
 */
public data class FuzzFailure(
    /** Name of the scenario that failed. */
    public val scenario: String,
    /** Seed of the failing run. */
    public val seed: Long,
    /** Classification of the failure. */
    public val kind: FuzzFailureKind,
    /** Human-readable description of the failure. */
    public val detail: String,
    /** Injected bugs that were applied to the failing run. */
    public val mutations: Set<Mutation>,
) {
    /** Command line that reproduces this failure, as given by [FuzzRunner.reproduction]. */
    public val reproduction: String
        get() = FuzzRunner.reproduction(scenario, seed, mutations)
}
