package io.raftlab.sim

/**
 * A named, deterministic script of timed actions, run steps and expectations on a simulated cluster.
 *
 * The `body` is re-evaluated for every run, so local `var`s declared in the body give each run its own state that
 * actions and expectations may share. Construction validates the body once.
 *
 * @property name Name of the scenario.
 */
public class Scenario internal constructor(
    /** Name of the scenario. */
    public val name: String,
    private val body: ScenarioBuilder.() -> Unit,
) {
    init {
        spec()
    }

    internal fun spec(): ScenarioSpec = ScenarioBuilder(name).apply(body).build()

    /**
     * Runs the scenario once for [seed] and returns its result.
     *
     * [faults], when given, overrides the network faults declared by the scenario; use it for stress runs.
     *
     * @param seed Seed from which all random streams of the run are derived.
     * @param faults Network faults that replace the scenario's own faults, or `null` to keep them.
     * @return The result of the run.
     */
    public fun run(
        seed: Long,
        faults: NetworkFaults? = null,
    ): ScenarioResult = prepare(seed, faults).execute()

    internal fun prepare(
        seed: Long,
        faults: NetworkFaults? = null,
        checker: InvariantChecker = InvariantChecker.standard(),
    ): ScenarioRun = ScenarioRun(spec(), seed, faults, checker)
}
