package io.raftlab.docs

import kotlin.system.exitProcess

/**
 * Entry point of the docs generator.
 *
 * Arguments: `--out <dir>` (required) is the directory holding the generated files; `--check` compares the directory with freshly generated docs without writing.
 * Exit codes: 0 when the docs are written or up to date, 1 when the docs are stale or a diagram fails lint, 2 on a usage error.
 *
 * @param args Command-line arguments.
 */
public fun main(args: Array<String>) {
    exitProcess(DocsCli.run(args.toList(), System.out, System.err))
}
