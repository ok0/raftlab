package io.raftlab.sim

import io.raftlab.core.Mutation

/**
 * A named, deterministic script of timed actions, run steps and expectations on a simulated cluster.
 *
 * The `body` is re-evaluated for every run with the run's seed (`ScenarioBuilder.seed`), so local `var`s declared in
 * the body give each run its own state that actions and expectations may share. Construction validates the body
 * once, with seed 0.
 *
 * @property name Name of the scenario.
 */
public class Scenario internal constructor(
    /** Name of the scenario. */
    public val name: String,
    private val body: ScenarioBuilder.() -> Unit,
) {
    init {
        spec(0L)
    }

    internal fun spec(seed: Long): ScenarioSpec = ScenarioBuilder(name, seed).apply(body).build()

    /**
     * Runs the scenario once for [seed] and returns its result.
     *
     * [faults], when given, overrides the network faults declared by the scenario; use it for stress runs.
     *
     * @param seed Seed from which all random streams of the run are derived.
     * @param faults Network faults that replace the scenario's own faults, or `null` to keep them.
     * @param mutations Injected bugs applied to every node; recorded in the result; empty for normal runs.
     * @return The result of the run.
     */
    public fun run(
        seed: Long,
        faults: NetworkFaults? = null,
        mutations: Set<Mutation> = emptySet(),
    ): ScenarioResult = prepare(seed, faults, mutations = mutations).execute()

    internal fun prepare(
        seed: Long,
        faults: NetworkFaults? = null,
        checker: InvariantChecker = InvariantChecker.standard(),
        mutations: Set<Mutation> = emptySet(),
        requestHook: ClientRequestHook? = null,
    ): ScenarioRun = ScenarioRun(spec(seed), seed, faults, checker, mutations, requestHook)
}
