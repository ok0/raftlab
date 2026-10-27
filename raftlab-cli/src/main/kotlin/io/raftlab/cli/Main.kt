package io.raftlab.cli

import kotlin.system.exitProcess

/**
 * Entry point of the `raftlab` command.
 *
 * Subcommands: `scenario` (list and run simulator scenarios), `export` (Mermaid state and
 * sequence diagrams and log tables), `docs` (generate or check docs/generated and the README
 * region), `fuzz` (seed sweep of the fuzz-random scenario), `node` (one real TCP Raft node
 * with file storage) and `kv` (key-value client over TCP).
 *
 * Exit codes: 0 success, 1 check failed (stale docs, failing run, failed KV operation),
 * 2 usage error.
 *
 * @param args command-line arguments, without the program name.
 */
public fun main(args: Array<String>) {
    exitProcess(RaftlabCli.run(args.toList(), CliEnvironment.system()))
}
