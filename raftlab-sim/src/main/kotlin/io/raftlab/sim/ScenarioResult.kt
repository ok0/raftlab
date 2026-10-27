package io.raftlab.sim

import io.raftlab.trace.TraceEvent

/**
 * Outcome of one scenario run for one seed.
 *
 * @property scenario Name of the scenario.
 * @property seed Seed of the run.
 * @property trace Trace events of the run in event order.
 * @property history Client operations of the run.
 * @property violations Invariant violations detected during the run.
 * @property finalState Node states at the end of the run.
 * @property expectations Results of the scenario expectations, in declaration order.
 * @property error Message of the failure that stopped the run, or null when the run completed.
 */
public data class ScenarioResult(
    /** Name of the scenario. */
    public val scenario: String,
    /** Seed of the run. */
    public val seed: Long,
    /** Trace events of the run in event order. */
    public val trace: List<TraceEvent>,
    /** Client operations of the run. */
    public val history: History,
    /** Invariant violations detected during the run. */
    public val violations: List<Violation>,
    /** Node states at the end of the run. */
    public val finalState: List<NodeView>,
    /** Results of the scenario expectations, in declaration order. */
    public val expectations: List<ExpectationResult>,
    /** Message of the failure that stopped the run, or null when the run completed. */
    public val error: String?,
) {
    /** True when the run had no error, no violation and every expectation passed. */
    public val passed: Boolean get() = error == null && violations.isEmpty() && expectations.all { it.passed }

    /** Human-readable summary: a status line, followed by one line per error, violation and failed expectation. */
    public val report: String
        get() {
            val status = if (passed) "PASSED" else "FAILED"
            val text = StringBuilder("scenario '$scenario' seed $seed: $status")
            error?.let { text.append("\n  error: $it") }
            violations.forEach { text.append("\n  violation: $it") }
            expectations.filter { !it.passed }.forEach { e ->
                text.append("\n  expectation failed: ${e.name}")
                e.message?.let { text.append(" ($it)") }
            }
            return text.toString()
        }
}
