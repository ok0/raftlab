package io.raftlab.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.raftlab.core.Mutation
import io.raftlab.sim.FuzzRunner

internal class FuzzCommand(
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "fuzz") {
    override fun help(context: Context): String =
        "Run the fuzz-random scenario over seeds START until START+SEEDS and print failing seeds with reproduction lines."

    private val seeds by option("--seeds", help = "Number of seeds (default 1000)")
        .long()
        .restrictTo(1L, Int.MAX_VALUE.toLong())
        .default(CliDefaults.FUZZ_SEEDS)

    private val start by option("--start", help = "First seed (default 0, as ./gradlew fuzzTest)")
        .long()
        .restrictTo(min = 0L)
        .default(CliDefaults.FUZZ_START)

    private val mutations by option(
        "--mutation",
        help = "Injected bug, repeatable: ${Mutation.entries.joinToString(", ") { it.name }}.",
    ).enum<Mutation>().multiple()

    override fun run() {
        if (start > Long.MAX_VALUE - seeds) usageError("--start + --seeds overflows")
        val report = FuzzRunner(start..(start + seeds - 1), mutations.toSet()).run()
        env.out.printText(report.render())
        if (report.failures.isNotEmpty()) checkFailed()
    }
}
