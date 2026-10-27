package io.raftlab.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.raftlab.docs.DocsRunner

internal class DocsCommand(
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "docs") {
    override fun help(context: Context): String =
        "Generate docs/generated and the README architecture region, or check them with --check " +
            "(same as ./gradlew generateDocs / checkDocs; run from the repository root after ./gradlew writeModuleGraph)."

    private val check by option("--check", help = "Compare instead of write; exit 1 when stale.").flag()

    private val out by option("--out", help = "Output directory (default: docs/generated)")
        .default("docs/generated")

    private val moduleGraph by option(
        "--module-graph",
        help = "Module graph JSON (default: build/raftlab/module-graph.json)",
    ).default("build/raftlab/module-graph.json")

    private val readme by option("--readme", help = "README with the generated region (default: README.md)")
        .default("README.md")

    override fun run() {
        val args = listOf("--out", out, "--module-graph", moduleGraph, "--readme", readme) + checkArgs()
        val code = DocsRunner.run(args, env.out, env.err)
        if (code != 0) throw ProgramResult(code)
    }

    private fun checkArgs(): List<String> = if (check) listOf("--check") else emptyList()
}
