package io.raftlab.sim

import io.raftlab.core.Mutation

/**
 * Runs a scenario over a range of seeds with injected bugs and classifies every failing seed.
 *
 * Seeds run one after another in ascending order. A run that throws a [RuntimeException] is recorded as a
 * [FuzzFailureKind.RUN_ERROR] failure and the sweep continues with the next seed.
 *
 * @property seeds Seeds to run, inclusive; must not be empty.
 * @property mutations Injected bugs applied to every run, sorted by ordinal.
 */
public class FuzzRunner internal constructor(
    /** Seeds to run, inclusive; must not be empty. */
    public val seeds: LongRange,
    mutations: Set<Mutation>,
    private val scenario: Scenario,
    private val observer: (ScenarioResult) -> Unit,
) {
    /**
     * Creates a runner for the `fuzz-random` scenario.
     *
     * @param seeds Seeds to run, inclusive; must not be empty.
     * @param mutations Injected bugs applied to every run; empty for a clean sweep.
     */
    public constructor(seeds: LongRange, mutations: Set<Mutation> = emptySet()) :
        this(seeds, mutations, FuzzScenario.scenario, {})

    init {
        require(!seeds.isEmpty()) { "fuzz seed range must not be empty: $seeds" }
        require(seeds.last - seeds.first < Int.MAX_VALUE) { "fuzz seed range is too large: $seeds" }
    }

    /** Injected bugs applied to every run, sorted by ordinal. */
    public val mutations: Set<Mutation> = mutations.toSortedSet()

    /**
     * Runs every seed in [seeds] and collects the failing ones.
     *
     * @return The report of the sweep, with failures in ascending seed order.
     */
    public fun run(): FuzzReport {
        val failures = mutableListOf<FuzzFailure>()
        for (seed in seeds) {
            try {
                val result = scenario.run(seed, mutations = mutations)
                observer(result)
                evaluate(result)?.let { failures += it }
            } catch (e: RuntimeException) {
                val detail = e.message ?: e.toString()
                failures += FuzzFailure(scenario.name, seed, FuzzFailureKind.RUN_ERROR, detail, mutations)
            }
        }
        return FuzzReport(scenario.name, seeds, mutations, failures.sortedBy { it.seed })
    }

    /** Classification of a fuzz run and the command line that reproduces it. */
    public companion object {
        /**
         * Classifies one scenario result. The first matching check decides: invariant violations, then
         * linearizability, then run error, then failed expectations.
         *
         * @param result The scenario run to classify.
         * @return The failure, or null when the run passed every check.
         */
        public fun evaluate(result: ScenarioResult): FuzzFailure? {
            val mutations = result.mutations
            val scenario = result.scenario
            val seed = result.seed
            if (result.violations.isNotEmpty()) {
                val v = result.violations.first()
                val more = result.violations.size - 1
                val suffix = if (more > 0) " (+$more more)" else ""
                val detail = "${v.invariant} at t=${v.time}: ${v.detail}$suffix"
                return FuzzFailure(scenario, seed, FuzzFailureKind.INVARIANT, detail, mutations)
            }
            val lin = LinearizabilityChecker.check(result)
            if (lin is LinResult.NotLinearizable) {
                return FuzzFailure(
                    scenario,
                    seed,
                    FuzzFailureKind.LINEARIZABILITY,
                    LinearizabilityChecker.render(lin),
                    mutations,
                )
            }
            if (lin is LinResult.Unknown) {
                val detail = LinearizabilityChecker.render(lin)
                return FuzzFailure(scenario, seed, FuzzFailureKind.UNKNOWN, detail, mutations)
            }
            result.error?.let { return FuzzFailure(scenario, seed, FuzzFailureKind.RUN_ERROR, it, mutations) }
            val failed = result.expectations.filter { !it.passed }
            if (failed.isNotEmpty()) {
                val detail = "expectation failed: " + failed.joinToString(", ") { it.name }
                return FuzzFailure(scenario, seed, FuzzFailureKind.EXPECTATION, detail, mutations)
            }
            return null
        }

        /**
         * The command line that reproduces one run of a fuzz scenario.
         *
         * @param scenario Name of the scenario.
         * @param seed Seed of the run.
         * @param mutations Injected bugs of the run; listed in ordinal order.
         * @return The reproduction command text.
         */
        public fun reproduction(
            scenario: String,
            seed: Long,
            mutations: Set<Mutation>,
        ): String {
            val flags = mutations.sortedBy { it.ordinal }.joinToString("") { " --mutation ${it.name}" }
            return "raftlab scenario run $scenario --seed $seed$flags"
        }
    }
}
