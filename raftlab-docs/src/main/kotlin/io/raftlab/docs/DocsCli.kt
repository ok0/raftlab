package io.raftlab.docs

import io.raftlab.viz.ModuleGraph
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Command-line handling for the docs generator: parses arguments and maps results to exit codes. */
internal object DocsCli {
    /** One-line usage text printed on usage errors. */
    const val USAGE: String = "usage: raftlab-docs --out <dir> --module-graph <file> --readme <file> [--check]"

    private const val OUT = "--out"
    private const val MODULE_GRAPH = "--module-graph"
    private const val README = "--readme"

    /** Parses [args] and runs write or check mode; returns the process exit code. */
    fun run(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        val values = mutableMapOf<String, String>()
        var checkOnly = false
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == OUT || arg == MODULE_GRAPH || arg == README -> {
                    val next = args.getOrNull(i + 1)
                    if (arg in values) return usage(err, "$arg given more than once")
                    if (next == null || next.startsWith("--")) return usage(err, "$arg requires a ${valueName(arg)}")
                    values[arg] = next
                    i += 2
                    continue
                }

                arg == "--check" -> {
                    if (checkOnly) return usage(err, "--check given more than once")
                    checkOnly = true
                }

                else -> {
                    return usage(err, "unknown argument: $arg")
                }
            }
            i++
        }
        val dir = Path.of(values[OUT] ?: return usage(err, "missing required --out <dir>"))
        val graphPath = Path.of(values[MODULE_GRAPH] ?: return usage(err, "missing required --module-graph <file>"))
        val readme = Path.of(values[README] ?: return usage(err, "missing required --readme <file>"))
        val graph = loadGraph(graphPath, err) ?: return 1
        if (checkOnly) {
            val problems =
                try {
                    DocsGenerator.check(dir, graph, readme)
                } catch (e: IllegalStateException) {
                    err.println(e.message)
                    return 1
                }
            if (problems.isEmpty()) {
                out.println("Generated docs are up to date.")
                return 0
            }
            for (problem in problems) {
                err.println(problem)
                err.println()
            }
            err.println("Generated docs are stale. Run ./gradlew generateDocs and commit the result.")
            return 1
        }
        val files =
            try {
                DocsGenerator.write(dir, graph, readme)
            } catch (e: IllegalStateException) {
                err.println(e.message)
                return 1
            }
        for (f in files) {
            out.println("Wrote ${f.path}")
        }
        out.println("Wrote ${readme.fileName} (generated region)")
        return 0
    }

    private fun valueName(option: String): String = if (option == OUT) "directory" else "file"

    private fun loadGraph(
        path: Path,
        err: PrintStream,
    ): ModuleGraph? {
        if (!Files.isRegularFile(path)) {
            err.println("module graph not found: $path (run ./gradlew writeModuleGraph)")
            return null
        }
        return try {
            ModuleGraph.fromJson(Files.readString(path))
        } catch (e: IllegalArgumentException) {
            err.println("invalid module graph $path: ${e.message}")
            null
        }
    }

    private fun usage(
        err: PrintStream,
        reason: String,
    ): Int {
        err.println("error: $reason")
        err.println(USAGE)
        return 2
    }
}
