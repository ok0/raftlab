package io.raftlab.sim

import io.raftlab.core.Mutation

/**
 * The outcome of a fuzz run over a range of seeds.
 *
 * @property scenario Name of the scenario that was run.
 * @property seeds Seeds that were run.
 * @property mutations Injected bugs that were applied to every run.
 * @property failures Failing seeds, in ascending seed order.
 */
public data class FuzzReport(
    /** Name of the scenario that was run. */
    public val scenario: String,
    /** Seeds that were run. */
    public val seeds: LongRange,
    /** Injected bugs that were applied to every run. */
    public val mutations: Set<Mutation>,
    /** Failing seeds, in ascending seed order. */
    public val failures: List<FuzzFailure>,
) {
    /** Number of seeds that were run. */
    public val seedsRun: Int get() = (seeds.last - seeds.first + 1).toInt()

    /** Number of failing seeds. */
    public val failed: Int get() = failures.size

    /** Number of seeds that passed. */
    public val passed: Int get() = seedsRun - failed

    /** The lowest failing seed, or null when no seed failed. */
    public val firstFailingSeed: Long? get() = failures.firstOrNull()?.seed

    /**
     * Renders the report as deterministic text: a header, one `seed` line and one `repro` line per failure, and a
     * summary. Lines are joined with newlines and there is no trailing newline.
     *
     * @return The rendered report.
     */
    public fun render(): String {
        val names =
            if (mutations.isEmpty()) {
                "none"
            } else {
                mutations.sortedBy { it.ordinal }.joinToString(", ") { it.name }
            }
        val lines = mutableListOf("fuzz '$scenario' seeds ${seeds.first}..${seeds.last}, mutations: $names")
        for (f in failures) {
            lines.add("seed ${f.seed}: ${f.kind}: ${f.detail.replace("\n", "\n    ")}")
            lines.add("  repro: ${f.reproduction}")
        }
        val suffix = if (failed == 0) " (no failures)" else ", first failing seed $firstFailingSeed"
        lines.add("summary: $seedsRun seeds run, $passed passed, $failed failed$suffix")
        return lines.joinToString("\n")
    }
}
