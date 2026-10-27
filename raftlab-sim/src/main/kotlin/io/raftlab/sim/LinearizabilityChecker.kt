package io.raftlab.sim

import io.raftlab.kv.KvResult
import java.util.TreeMap

/**
 * Checks whether the client operations of a [History] are linearizable against a single-register-per-key model.
 *
 * The model is the KV state machine: each key holds a value that is either present or absent. `Put` stores its
 * value, `Get` reads the value, `Delete` removes the value and returns the previous one, and `Cas` stores `new` only
 * when the current value equals `expected`, otherwise it reports the actual value as `CasFailed`.
 *
 * Operations are partitioned by key, and each key is searched independently. Keys are visited in ascending order, and
 * the operations of each key are sorted by `(invokedAt, clientId, opId)`, so the verdict does not depend on the input
 * order of the entries.
 *
 * An operation is complete when it returned `Ok` or `CasFailed`. A pending operation has no returned time or has a
 * `Timeout` or `NotLeader` result. Such an operation may or may not have taken effect, so the search may place it
 * at any point after its invocation, or leave it out. A pending operation is placed only when it changes the value.
 *
 * Precedence uses virtual time. Operation A precedes operation B when A returned before B was invoked. At an equal
 * millisecond, operations of different clients are treated as concurrent, because the history does not record which
 * of a return and an invoke came first. Operations of the same client keep their program order by `opId`.
 *
 * The budget counts search states explored per key. When a key exhausts its budget, its verdict is `Unknown`, and
 * the check continues with the other keys. A `NotLinearizable` verdict on any key takes precedence over `Unknown`.
 * The search is deterministic, so the same history always gives the same verdict and the same rendered text.
 */
public object LinearizabilityChecker {
    /** The maximum number of search states explored per key. */
    public const val DEFAULT_BUDGET: Long = 200_000L

    /**
     * Checks the linearizability of [history].
     *
     * @param history The operations to check.
     * @param budget The maximum number of search states explored per key; must be positive.
     * @return [LinResult.NotLinearizable] for the first failing key in ascending key order, otherwise
     *   [LinResult.Unknown] for the first key that exhausted [budget], otherwise [LinResult.Linearizable] with the
     *   total number of states explored.
     * @throws IllegalArgumentException when [budget] is not positive.
     */
    public fun check(
        history: History,
        budget: Long = DEFAULT_BUDGET,
    ): LinResult {
        require(budget > 0) { "linearizability budget must be positive: $budget" }
        val groups = TreeMap<String, MutableList<HistoryEntry>>()
        for (e in history.entries) groups.getOrPut(e.op.key) { mutableListOf() }.add(e)
        var firstUnknown: LinResult.Unknown? = null
        var total = 0L
        for ((key, entries) in groups) {
            val sorted = entries.sortedWith(compareBy<HistoryEntry>({ it.invokedAt }, { it.clientId }, { it.opId }))
            when (val r = LinSearch(key, sorted, budget).run()) {
                is LinResult.NotLinearizable -> {
                    return r
                }

                is LinResult.Unknown -> {
                    if (firstUnknown == null) firstUnknown = r
                }

                is LinResult.Linearizable -> {
                    total += r.explored
                }
            }
        }
        return firstUnknown ?: LinResult.Linearizable(total)
    }

    /**
     * Checks the linearizability of the history of [result].
     *
     * @param result The scenario run whose history is checked.
     * @param budget The maximum number of search states explored per key; must be positive.
     * @return The verdict of [check] on the history of [result].
     */
    public fun check(
        result: ScenarioResult,
        budget: Long = DEFAULT_BUDGET,
    ): LinResult = check(result.history, budget)

    /**
     * Renders [result] as deterministic text, one line per fact, joined with newlines.
     *
     * @param result The verdict to render.
     * @param scenario Name of the scenario to show in the header, or null to omit it.
     * @param seed Seed to show in the header, or null to omit it.
     * @return The rendered text.
     */
    public fun render(
        result: LinResult,
        scenario: String? = null,
        seed: Long? = null,
    ): String {
        val label = listOfNotNull(scenario?.let { "scenario '$it'" }, seed?.let { "seed $it" }).joinToString(" ")
        val head = if (label.isEmpty()) "" else "$label: "
        return when (result) {
            is LinResult.Linearizable -> {
                "${head}LINEARIZABLE (${result.explored} states explored)"
            }

            is LinResult.Unknown -> {
                "${head}UNKNOWN on key '${result.key}': search budget of ${result.budget} states exhausted " +
                    "after ${result.explored} states; treated as failure"
            }

            is LinResult.NotLinearizable -> {
                renderNotLinearizable(result, head)
            }
        }
    }

    /**
     * Checks the history of [result] and throws when the verdict is not accepted.
     *
     * @param result The scenario run to check.
     * @param budget The maximum number of search states explored per key; must be positive.
     * @param allowUnknown True to accept an [LinResult.Unknown] verdict.
     * @return The verdict of [check].
     * @throws IllegalStateException when the verdict is not accepted; the message is the rendered verdict.
     */
    public fun requireLinearizable(
        result: ScenarioResult,
        budget: Long = DEFAULT_BUDGET,
        allowUnknown: Boolean = false,
    ): LinResult {
        val lin = check(result.history, budget)
        check(lin.accepted(allowUnknown)) { render(lin, result.scenario, result.seed) }
        return lin
    }

    private fun renderNotLinearizable(
        result: LinResult.NotLinearizable,
        head: String,
    ): String {
        val prefix =
            if (result.prefix.isEmpty()) {
                "none"
            } else {
                result.prefix.joinToString(", ") { "${it.clientId}#${it.opId}" }
            }
        val failed = result.failedAt?.let { line(it) } ?: "unknown"
        val lines =
            mutableListOf(
                "${head}NOT LINEARIZABLE on key '${result.key}' (${result.explored} states explored)",
                "  operations (${result.operations.size}):",
            )
        result.operations.forEach { lines.add("    " + line(it)) }
        lines.add("  longest linearizable prefix (${result.prefix.size}): $prefix")
        lines.add("  search failed at: $failed")
        return lines.joinToString("\n")
    }

    private fun line(e: HistoryEntry): String {
        val timing = if (e.returnedAt == null) "pending" else "returned ${e.returnedAt}"
        val result = e.result?.let { OpText.result(it) } ?: "none"
        val treated =
            if (e.returnedAt != null && e.result !is KvResult.Ok && e.result !is KvResult.CasFailed) {
                " (treated as pending)"
            } else {
                ""
            }
        return "${e.clientId}#${e.opId} ${OpText.op(e.op)} invoked ${e.invokedAt} $timing -> $result$treated"
    }
}
