package io.raftlab.viz

import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceEvent
import java.util.TreeMap
import java.util.TreeSet

/**
 * Exports the role changes of a trace as a Mermaid `stateDiagram-v2`.
 *
 * Each shown node becomes a composite state aliased `node<i>` (index by sorted node name), with one state per
 * role it visited (`<alias>_<Role>`), an initial transition to the first role, and one transition per role change
 * labelled `#k T<term> @<time>ms <reason>`. A shown node without role changes is rendered as the state
 * `no role changes`. Output depends only on the content of the events, not on their order in the input list,
 * and the input list is never modified.
 */
public class StateDiagramExporter {
    /**
     * Renders the trace as Mermaid text.
     *
     * @param events the trace events; they are sorted by [TraceEvent.seq] before processing
     * @param nodes when non-null, only these nodes are rendered (and listed even without events); when null,
     * every node that started or changed role is rendered
     * @return the Mermaid text, starting with the `stateDiagram-v2` header and ending with a newline
     */
    public fun export(
        events: List<TraceEvent>,
        nodes: Set<NodeId>? = null,
    ): String {
        val sorted = events.sortedBy { it.seq }
        val changes = TreeMap<NodeId, MutableList<RoleChanged>>()
        val shown = TreeSet<NodeId>()
        if (nodes != null) shown.addAll(nodes)
        for (e in sorted) {
            val node = e.node ?: continue
            if (nodes != null && node !in nodes) continue
            if (e is RoleChanged) {
                changes.getOrPut(node) { mutableListOf() }.add(e)
                shown.add(node)
            } else if (e is NodeStarted) {
                shown.add(node)
            }
        }
        val out = StringBuilder("stateDiagram-v2\n")
        for ((i, node) in shown.withIndex()) {
            appendNode(out, "node$i", node, changes[node])
        }
        return out.toString()
    }

    private fun appendNode(
        out: StringBuilder,
        id: String,
        node: NodeId,
        list: List<RoleChanged>?,
    ) {
        out.append("    state \"${MermaidText.name(node.value)}\" as $id {\n")
        if (list.isNullOrEmpty()) {
            out.append("        state \"no role changes\" as ${id}_none\n")
        } else {
            val roles = (listOf(list[0].from) + list.map { it.to }).distinct()
            for (r in roles) {
                val role = MermaidText.role(r)
                out.append("        state \"$role\" as ${id}_$role\n")
            }
            out.append("        [*] --> ${id}_${MermaidText.role(list[0].from)}\n")
            for ((k, rc) in list.withIndex()) {
                val label =
                    listOf("#${k + 1}", "T${rc.term}", "@${rc.time}ms", MermaidText.label(rc.reason))
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")
                out.append("        ${id}_${MermaidText.role(rc.from)} --> ${id}_${MermaidText.role(rc.to)} : $label\n")
            }
        }
        out.append("    }\n")
    }
}
