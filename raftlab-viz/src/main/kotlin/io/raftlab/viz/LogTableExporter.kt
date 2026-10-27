package io.raftlab.viz

import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.LogAppended
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.TraceEvent
import java.util.TreeMap

/**
 * Exports the logs of all nodes at given points in time as Markdown tables.
 *
 * Each time point becomes a section headed `**t = <t> ms**`. Its table has one row per node that appears in the trace,
 * sorted by name, with the columns `Node`, `Status` (`up` or `down`) and one column per log index. Only the last
 * `maxColumns` indexes up to the highest index known at that time are shown; when earlier indexes are hidden the
 * section notes `Indexes 1-<n> not shown.`. A cell holds the term of the entry at that index. A bold cell is committed,
 * which means its index is at most the node's commit index. The letter `S` marks an index that is covered by an
 * installed snapshot and no longer present in the log. An empty cell means the node has no entry at that index.
 *
 * The state at a time point is rebuilt by replaying every event with `time <= t` in `seq` order. Restarts reset the
 * commit index to the snapshot index, truncations and snapshots remove entries, and other events are ignored. The
 * output is deterministic and the input list is never modified.
 */
public class LogTableExporter {
    /**
     * Renders the log tables for [atTimes] and returns the text, which ends with a newline.
     *
     * @param events the recorded trace events, in any order
     * @param atTimes the time points, in the order they are rendered, must not be empty
     * @param maxColumns the maximum number of index columns per time point, must be positive
     * @return the Markdown text with one section per time point, separated by a blank line
     * @throws IllegalArgumentException if [atTimes] is empty or [maxColumns] is less than 1
     */
    public fun export(
        events: List<TraceEvent>,
        atTimes: List<Long>,
        maxColumns: Int = 20,
    ): String {
        require(atTimes.isNotEmpty()) { "atTimes must not be empty" }
        require(maxColumns >= 1) { "maxColumns must be positive: $maxColumns" }
        val sorted = events.sortedBy { it.seq }
        val rows = sorted.mapNotNull { it.node }.distinct().sortedBy { it.value }
        return atTimes.joinToString("\n") { time -> section(sorted, rows, time, maxColumns) }
    }

    private fun section(
        sorted: List<TraceEvent>,
        rows: List<NodeId>,
        time: Long,
        maxColumns: Int,
    ): String {
        val states = rows.associateWith { NodeState() }
        for (e in sorted.filter { it.time <= time }) {
            val node = e.node ?: continue
            states.getValue(node).apply(e)
        }
        val maxIdx = states.values.fold(0L) { acc, s -> maxOf(acc, s.log.lastEntry()?.key ?: 0L, s.snapshotIndex) }
        val from = maxOf(1L, maxIdx - maxColumns + 1)
        val columns = from..maxIdx

        val out = StringBuilder("**t = $time ms**\n\n")
        if (from > 1) out.append("Indexes 1-${from - 1} not shown.\n\n")
        val header = listOf("Node", "Status") + columns.map { it.toString() }
        out.append(row(header)).append('\n')
        out.append(row(header.map { "---" })).append('\n')
        for (node in rows) {
            val s = states.getValue(node)
            val status = if (s.up) "up" else "down"
            val cells = listOf(MermaidText.name(node.value), status) + columns.map { cell(s, it) }
            out.append(row(cells)).append('\n')
        }
        return out.toString()
    }

    private fun cell(
        s: NodeState,
        index: Long,
    ): String {
        val text = s.log[index]?.toString() ?: if (index <= s.snapshotIndex) "S" else ""
        return if (text.isNotEmpty() && index <= s.commit) "**$text**" else text
    }

    private fun row(cells: List<String>): String = "| " + cells.joinToString(" | ") + " |"

    private class NodeState {
        val log = TreeMap<Long, Long>()
        var commit = 0L
        var snapshotIndex = 0L
        var up = true

        fun apply(e: TraceEvent) {
            when (e) {
                is NodeStarted -> {
                    up = true
                }

                is NodeCrashed -> {
                    up = false
                }

                is NodeRestarted -> {
                    up = true
                    commit = snapshotIndex
                }

                is LogAppended -> {
                    log[e.index] = e.term
                }

                is LogTruncated -> {
                    log.tailMap(e.fromIndex, true).clear()
                }

                is CommitAdvanced -> {
                    commit = e.commitIndex
                }

                is SnapshotInstalled -> {
                    log.headMap(e.lastIndex, true).clear()
                    snapshotIndex = maxOf(snapshotIndex, e.lastIndex)
                    commit = maxOf(commit, e.lastIndex)
                }

                else -> {}
            }
        }
    }
}
