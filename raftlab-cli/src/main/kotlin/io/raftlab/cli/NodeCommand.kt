package io.raftlab.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import io.raftlab.net.ClusterConfig
import io.raftlab.net.NodeRuntime
import io.raftlab.trace.NodeId

internal class NodeCommand(
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "node") {
    override fun help(context: Context): String =
        "Run one Raft node with a TCP transport and file storage until SIGINT/SIGTERM."

    private val id by option("--id").required()
    private val cluster by option("--cluster", help = "id=host:port,... of all members")
        .convert { text ->
            try {
                ClusterConfig.parse(text)
            } catch (e: IllegalArgumentException) {
                fail("invalid --cluster: ${e.message}")
            }
        }.required()
    private val dataDir by option("--data-dir").path(canBeFile = false).required()
    private val trace by option("--trace", help = "Append trace events as JSONL to this file").path(canBeDir = false)
    private val tickMillis by option("--tick-millis", help = "Tick interval (default 50)")
        .long()
        .default(NodeRuntime.DEFAULT_TICK_MILLIS)
        .check("must be at least 1") { it >= 1L }

    override fun run() {
        val nodeId = NodeId(id)
        if (nodeId !in cluster.ids) usageError("--id $id is not a member of --cluster $cluster")
        val signal = env.stopSignal()
        val runtime = NodeRuntime(nodeId, cluster, dataDir, tickMillis, trace)
        try {
            runtime.start()
        } catch (e: Exception) {
            env.err.println("error: node $id failed to start: ${e.message ?: e}")
            checkFailed()
        }
        val addr = cluster.address(nodeId)
        env.err.println("raftlab node $id started on ${addr.hostString}:${addr.port} (data dir $dataDir)")
        try {
            while (!signal.awaitStop(POLL_MILLIS)) {
                if (runtime.failure != null || !runtime.isRunning) break
            }
        } finally {
            runtime.stop()
        }
        runtime.failure?.let {
            env.err.println("error: node $id stopped after a fatal error: $it")
            checkFailed()
        }
        env.err.println("raftlab node $id stopped")
    }

    companion object {
        const val POLL_MILLIS: Long = 100
    }
}
