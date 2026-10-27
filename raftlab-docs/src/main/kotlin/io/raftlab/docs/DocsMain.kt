package io.raftlab.docs

import kotlin.system.exitProcess

/**
 * Entry point of the docs generator.
 *
 * Arguments:
 * - `--out <dir>` (required) is the directory holding the generated files.
 * - `--module-graph <file>` (required) is the module graph JSON written by `./gradlew writeModuleGraph`.
 * - `--readme <file>` (required) is the README that holds the `GENERATED:architecture` markers; its region is rewritten.
 * - `--check` compares the directory and the README region with freshly generated docs without writing.
 *
 * Exit codes: 0 when the docs are written or up to date, 1 when the docs are stale, a diagram fails lint, the module graph
 * is missing or invalid, or a README marker is missing, 2 on a usage error.
 *
 * @param args Command-line arguments.
 */
public fun main(args: Array<String>) {
    exitProcess(DocsCli.run(args.toList(), System.out, System.err))
}
