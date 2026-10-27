package io.raftlab.docs

import java.io.PrintStream

/**
 * Programmatic entry point of the docs generator, used by the `raftlab docs` command.
 *
 * Accepts the same arguments and produces the same output and exit codes as [main]: `--out <dir>`, `--module-graph <file>`,
 * `--readme <file>` (all required) and the optional `--check` flag. Unlike [main], it never calls `exitProcess`.
 */
public object DocsRunner {
    /**
     * Runs the docs generator with [args], writing messages to [out] and [err].
     *
     * @param args Command-line arguments, as accepted by [main].
     * @param out Stream receiving normal output.
     * @param err Stream receiving diagnostics and usage errors.
     * @return Exit code: 0 when the docs are written or up to date, 1 when the docs are stale, a diagram fails lint, the module
     * graph is missing or invalid, or a README marker is missing, 2 on a usage error.
     */
    public fun run(
        args: List<String>,
        out: PrintStream,
        err: PrintStream,
    ): Int = DocsCli.run(args, out, err)
}
