package io.raftlab.viz

import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.LeadershipTransfer
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageDropped
import io.raftlab.trace.MessageDuplicated
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceEvent
import java.util.TreeSet

/**
 * Exports a trace as a Mermaid `sequenceDiagram`.
 *
 * Each [io.raftlab.trace.NodeId] that is rendered becomes a participant `P<i>` (by sorted position) with its
 * sanitized name as the alias. A message is rendered as `P<from>->>P<to>: <kind> T<term> <summary>`; a message
 * that was dropped uses the arrow `-x` and is tagged `(dropped <reason>)`; a message without an outcome is tagged
 * `(in flight)`; a duplicate copy is rendered as a second arrow tagged `(duplicate)`. Role changes, commit
 * advances, crashes and restarts are rendered as notes over the node.
 *
 * Filters: `window` keeps events whose `time` lies in the inclusive range (a duplicate copy uses the time of its
 * [MessageDuplicated] event); `nodes` keeps messages whose sender and recipient are both listed and notes of listed
 * nodes; `kinds` keeps only messages of the listed kinds (notes are not affected). A non-null `nodes` also forces
 * those nodes to be participants. Messages beyond `maxMessages` are replaced by one note listing how many were
 * omitted; notes after that cut are dropped.
 *
 * The output is deterministic: the input list is never modified and its order is ignored because events are
 * processed by `seq`.
 */
public class SequenceDiagramExporter {
    /**
     * Renders [events] as a Mermaid `sequenceDiagram` and returns the text, with every line ending in `\n`.
     *
     * @param events the recorded trace events, in any order
     * @param window inclusive range of event `time` values to keep, or null to keep all times
     * @param nodes nodes to keep, or null to keep all nodes
     * @param kinds message kinds to keep, or null to keep all kinds
     * @param maxMessages maximum number of messages rendered before the rest are omitted, must be at least 1
     * @return the Mermaid diagram text
     * @throws IllegalArgumentException if [maxMessages] is less than 1
     */
    public fun export(
        events: List<TraceEvent>,
        window: LongRange? = null,
        nodes: Set<NodeId>? = null,
        kinds: Set<MessageKind>? = null,
        maxMessages: Int = 200,
    ): String {
        require(maxMessages >= 1) { "maxMessages must be >= 1: $maxMessages" }
        val sorted = events.sortedBy { it.seq }

        val outcomes = mutableMapOf<Long, TraceEvent>()
        for (e in sorted) {
            when (e) {
                is MessageDelivered -> outcomes.putIfAbsent(e.msgId, e)
                is MessageDropped -> outcomes.putIfAbsent(e.msgId, e)
                else -> Unit
            }
        }

        val sends = mutableMapOf<Long, MessageSent>()
        val items = mutableListOf<Item>()
        for (e in sorted) {
            when (e) {
                is MessageSent -> {
                    sends[e.msgId] = e
                    val item = messageItem(e.msgId, e, false, e.time, outcomes, window, nodes, kinds)
                    if (item != null) items += item
                }

                is MessageDuplicated -> {
                    val orig = sends[e.msgId]
                    if (orig != null) {
                        sends[e.copyId] = orig
                        val item = messageItem(e.copyId, orig, true, e.time, outcomes, window, nodes, kinds)
                        if (item != null) items += item
                    }
                }

                else -> {
                    val item = noteItem(e, window, nodes)
                    if (item != null) items += item
                }
            }
        }

        val rendered = mutableListOf<Item>()
        var count = 0
        var omitted = 0
        for (item in items) {
            if (omitted > 0) {
                if (item.arrow != null) omitted++
            } else if (item.arrow != null && count == maxMessages) {
                omitted++
            } else {
                rendered += item
                if (item.arrow != null) count++
            }
        }

        val participants = TreeSet<NodeId>()
        if (nodes != null) participants.addAll(nodes)
        for (item in rendered) {
            participants.add(item.from)
            item.to?.let { participants.add(it) }
        }
        val pids = participants.withIndex().associate { (i, node) -> node to "P$i" }

        val out = StringBuilder("sequenceDiagram\n")
        for (node in participants) {
            out.append("    participant ${pids.getValue(node)} as ${MermaidText.name(node.value)}\n")
        }
        for (item in rendered) {
            val target = item.to
            if (item.arrow != null && target != null) {
                out.append("    ${pids.getValue(item.from)}${item.arrow}${pids.getValue(target)}: ${item.text}\n")
            } else {
                out.append("    Note over ${pids.getValue(item.from)}: ${item.text}\n")
            }
        }
        if (omitted > 0) {
            val size = participants.size
            val over = if (size == 1) "P0" else "P0,P${size - 1}"
            out.append("    Note over $over: $omitted more messages omitted (limit $maxMessages)\n")
        }
        return out.toString()
    }

    private fun messageItem(
        id: Long,
        send: MessageSent,
        duplicate: Boolean,
        time: Long,
        outcomes: Map<Long, TraceEvent>,
        window: LongRange?,
        nodes: Set<NodeId>?,
        kinds: Set<MessageKind>?,
    ): Item? {
        if (window != null && time !in window) return null
        if (nodes != null && (send.from !in nodes || send.to !in nodes)) return null
        if (kinds != null && send.kind !in kinds) return null
        val outcome = outcomes[id]
        val tags = mutableListOf<String>()
        if (duplicate) tags += "duplicate"
        if (outcome is MessageDropped) {
            tags += "dropped ${outcome.reason.name.lowercase()}"
        } else if (outcome == null) {
            tags += "in flight"
        }
        val base =
            listOf(MermaidText.kind(send.kind), "T${send.term}", MermaidText.label(send.summary))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        val text = if (tags.isEmpty()) base else base + " (" + tags.joinToString(", ") + ")"
        val arrow = if (outcome is MessageDropped) "-x" else "->>"
        return Item(send.from, send.to, arrow, text)
    }

    private fun noteItem(
        event: TraceEvent,
        window: LongRange?,
        nodes: Set<NodeId>?,
    ): Item? {
        val node = event.node ?: return null
        if (window != null && event.time !in window) return null
        if (nodes != null && node !in nodes) return null
        val text = noteText(event) ?: return null
        return Item(node, null, null, text)
    }

    private fun noteText(event: TraceEvent): String? =
        when (event) {
            is RoleChanged -> {
                val base = "${MermaidText.role(event.from)} to ${MermaidText.role(event.to)} T${event.term}"
                val reason = MermaidText.label(event.reason)
                if (reason.isEmpty()) base else "$base ($reason)"
            }

            is CommitAdvanced -> {
                "commit index ${event.commitIndex}"
            }

            is NodeCrashed -> {
                "crashed"
            }

            is NodeRestarted -> {
                "restarted"
            }

            is LeadershipTransfer -> {
                "leadership transfer to ${MermaidText.label(event.target.value)}"
            }

            else -> {
                null
            }
        }

    private class Item(
        val from: NodeId,
        val to: NodeId?,
        val arrow: String?,
        val text: String,
    )
}
