package io.raftlab.docs

import java.io.PrintStream
import java.nio.file.Path

/** Command-line handling for the docs generator: parses arguments and maps results to exit codes. */
internal object DocsCli {
    /** One-line usage text printed on usage errors. */
    const val USAGE: String = "usage: raftlab-docs --out <dir> [--check]"

    /** Parses [args] and runs write or check mode; returns the process exit code. */
    fun run(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
    ): Int {
        var outArg: String? = null
        var checkOnly = false
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--out" -> {
                    val next = args.getOrNull(i + 1)
                    if (outArg != null) return usage(err, "--out given more than once")
                    if (next == null || next.startsWith("--")) return usage(err, "--out requires a directory")
                    outArg = next
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
        val outPath = outArg ?: return usage(err, "missing required --out <dir>")
        val dir = Path.of(outPath)
        if (checkOnly) {
            val problems =
                try {
                    DocsGenerator.check(dir)
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
                DocsGenerator.write(dir)
            } catch (e: IllegalStateException) {
                err.println(e.message)
                return 1
            }
        for (f in files) {
            out.println("Wrote ${f.path}")
        }
        return 0
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
