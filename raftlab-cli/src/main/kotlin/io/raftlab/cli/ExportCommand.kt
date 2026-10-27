package io.raftlab.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.raftlab.sim.ScenarioLibrary
import io.raftlab.trace.NodeId
import io.raftlab.trace.TraceCodec
import io.raftlab.viz.HtmlViewer
import io.raftlab.viz.LogTableExporter
import io.raftlab.viz.SequenceDiagramExporter
import io.raftlab.viz.StateDiagramExporter
import java.io.IOException
import java.nio.file.Files

internal class ExportCommand(
    private val env: CliEnvironment,
) : CoreCliktCommand(name = "export") {
    override fun help(context: Context): String =
        "Render Mermaid diagrams (state, sequence), log tables (logtable) or a self-contained " +
            "HTML trace viewer (html) from a trace file or a scenario run."

    private val trace by option("--trace", help = "Trace file (JSON lines) to render.")
        .path(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val scenario by option("--scenario", help = "Scenario name to run and render.")
        .choice(*ScenarioLibrary.all.keys.toTypedArray())
    private val seed by option("--seed", help = "Seed for --scenario (default 42).").long()
    private val format by option("--format", help = "state, sequence, logtable or html.")
        .choice("state", "sequence", "logtable", "html")
        .required()
    private val from by option(
        "--from",
        help = "Only events at or after this time (ms); filters events (logtable: limits time points).",
    ).long().restrictTo(min = 0L)
    private val to by option(
        "--to",
        help = "Only events at or before this time (ms); filters events (logtable: limits time points).",
    ).long().restrictTo(min = 0L)
    private val nodes by option(
        "--nodes",
        help = "Comma-separated node ids: state/sequence show only these nodes; logtable/html keep their events.",
    ).split(",")
    private val at by option(
        "--at",
        help = "logtable only: comma-separated time points in ms (default: --to or the end of the trace).",
    ).long().split(",")
    private val out by option("--out", help = "Write the output to this file instead of stdout.")
        .path(canBeDir = false)

    override fun run() {
        val tracePath = trace
        val scenarioName = scenario
        val scenarioSeed = seed
        val events =
            when {
                tracePath != null && scenarioName != null -> {
                    usageError("give either --trace FILE or --scenario NAME, not both")
                }

                tracePath != null -> {
                    if (scenarioSeed != null) usageError("--seed requires --scenario")
                    try {
                        TraceCodec.decode(Files.readString(tracePath))
                    } catch (e: IllegalArgumentException) {
                        usageError("malformed trace file $tracePath: ${e.message}")
                    } catch (e: IOException) {
                        usageError("cannot read $tracePath: ${e.message}")
                    }
                }

                scenarioName != null -> {
                    ScenarioLibrary.all
                        .getValue(scenarioName)
                        .run(scenarioSeed ?: CliDefaults.SEED)
                        .trace
                }

                else -> {
                    usageError("one of --trace FILE or --scenario NAME is required")
                }
            }

        val fromTime = from
        val toTime = to
        val atTimes = at
        val nodeIds = nodes
        val fmt = format
        if (fromTime != null && toTime != null && fromTime > toTime) usageError("--from must not be after --to")
        if (atTimes != null && fmt != "logtable") usageError("--at is only valid with --format logtable")
        if (nodeIds != null && nodeIds.any { it.isBlank() }) usageError("--nodes must not contain empty ids")

        val window: LongRange? =
            if (fromTime == null && toTime == null) null else (fromTime ?: 0L)..(toTime ?: Long.MAX_VALUE)
        val nodeSet: Set<NodeId>? = nodeIds?.map { NodeId(it) }?.toSet()

        val text =
            when (fmt) {
                "state" -> {
                    StateDiagramExporter().export(
                        window?.let { w -> events.filter { it.time in w } } ?: events,
                        nodeSet,
                    )
                }

                "sequence" -> {
                    SequenceDiagramExporter().export(events, window = window, nodes = nodeSet)
                }

                "html" -> {
                    HtmlViewer.render(
                        events.filter { e ->
                            val node = e.node
                            (window == null || e.time in window) && (nodeSet == null || node == null || node in nodeSet)
                        },
                    )
                }

                else -> {
                    val times = atTimes ?: listOf(toTime ?: (events.maxOfOrNull { it.time } ?: 0L))
                    if (window != null) {
                        times.firstOrNull { it !in window }?.let { usageError("--at $it is outside --from/--to") }
                    }
                    val filtered =
                        nodeSet?.let { s ->
                            events.filter { e ->
                                val node = e.node
                                node == null || node in s
                            }
                        } ?: events
                    LogTableExporter().export(filtered, times)
                }
            }

        val outPath = out
        if (outPath != null) {
            Files.writeString(outPath, text)
        } else {
            env.out.print(text)
        }
    }
}
