package io.raftlab.cli

import io.raftlab.core.Mutation
import io.raftlab.sim.FuzzFailure
import io.raftlab.sim.LinResult
import io.raftlab.sim.LinearizabilityChecker
import io.raftlab.sim.ScenarioResult

internal object ScenarioSummary {
    fun render(
        result: ScenarioResult,
        lin: LinResult,
        failure: FuzzFailure?,
    ): String {
        val out = StringBuilder()
        out.append("scenario: ${result.scenario}\n")
        out.append("seed: ${result.seed}\n")
        out.append("mutations: ${mutationsText(result.mutations)}\n")
        for (e in result.expectations) {
            if (e.passed) {
                out.append("expectation ${e.name}: passed\n")
            } else {
                val message = e.message?.let { " ($it)" } ?: ""
                out.append("expectation ${e.name}: FAILED$message\n")
            }
        }
        for (v in result.violations) {
            out.append("violation: ${v.description}\n")
        }
        result.error?.let { out.append("error: $it\n") }
        val lines = LinearizabilityChecker.render(lin).split("\n")
        out.append("linearizability: ${lines.first()}\n")
        for (line in lines.drop(1)) {
            out.append("    $line\n")
        }
        val verdict = if (failure == null) "PASSED" else "FAILED (${failure.kind})"
        out.append("result: $verdict\n")
        return out.toString()
    }

    fun runError(
        name: String,
        seed: Long,
        mutations: Set<Mutation>,
        message: String,
    ): String {
        val out = StringBuilder()
        out.append("scenario: $name\n")
        out.append("seed: $seed\n")
        out.append("mutations: ${mutationsText(mutations)}\n")
        out.append("error: $message\n")
        out.append("result: FAILED (RUN_ERROR)\n")
        return out.toString()
    }

    private fun mutationsText(mutations: Set<Mutation>): String =
        if (mutations.isEmpty()) "none" else mutations.sortedBy { it.ordinal }.joinToString(", ") { it.name }
}
