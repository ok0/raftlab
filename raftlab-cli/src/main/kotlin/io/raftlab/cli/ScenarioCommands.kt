package io.raftlab.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import io.raftlab.core.Mutation
import io.raftlab.sim.FuzzRunner
import io.raftlab.sim.LinearizabilityChecker
import io.raftlab.sim.ScenarioLibrary
import io.raftlab.trace.TraceCodec
import java.nio.file.Files

internal class ScenarioCommand(
    env: CliEnvironment,
) : CoreCliktCommand(name = "scenario") {
    override fun help(context: Context): String = "List and run simulator scenarios."

    init {
        subcommands(ScenarioListCommand(env), ScenarioRunCommand(env))
    }

    override fun run() = Unit
}

internal class ScenarioListCommand(
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "list") {
    override fun help(context: Context): String = "List the scenario names."

    override fun run() {
        for (name in ScenarioLibrary.all.keys) {
            env.out.println(name)
        }
    }
}

internal class ScenarioRunCommand(
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "run") {
    override fun help(context: Context): String = "Run one scenario for one seed and report the result."

    private val name by argument("NAME", help = "Scenario name; see 'raftlab scenario list'.")
        .choice(*ScenarioLibrary.all.keys.toTypedArray())

    private val seed by option("--seed", help = "Seed of the run (default ${CliDefaults.SEED}).")
        .long()
        .default(CliDefaults.SEED)

    private val mutations by option(
        "--mutation",
        help = "Injected bug, repeatable: ${Mutation.entries.joinToString(", ") { it.name }}.",
    ).enum<Mutation>(key = { it.name }).multiple()

    private val trace by option("--trace", help = "Write the run's trace as JSONL to this file.")
        .path(canBeDir = false)

    override fun run() {
        val set = mutations.toSet()
        val result =
            try {
                ScenarioLibrary.all.getValue(name).run(seed, mutations = set)
            } catch (e: RuntimeException) {
                env.out.print(ScenarioSummary.runError(name, seed, set, e.message ?: e.toString()))
                checkFailed()
            }
        trace?.let { Files.writeString(it, TraceCodec.encode(result.trace)) }
        val failure = FuzzRunner.evaluate(result)
        env.out.print(ScenarioSummary.render(result, LinearizabilityChecker.check(result), failure))
        if (failure != null) checkFailed()
    }
}
