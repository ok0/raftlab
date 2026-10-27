package io.raftlab.sim

import io.raftlab.core.LogEntry
import io.raftlab.trace.NodeId
import java.util.TreeMap
import java.util.TreeSet

/**
 * Checks the State Machine Safety property: no two nodes apply different entries at the same index.
 *
 * The first entry applied at each index is remembered, so a divergence is still detected after the node that
 * applied it has crashed. Each index is reported at most once.
 */
public class StateMachineSafety : Invariant {
    /** Stable name of this invariant. */
    public override val name: String = "state-machine-safety"

    private val applied = TreeMap<Long, LogEntry>()
    private val appliedBy = TreeMap<Long, NodeId>()
    private val seenApplied = TreeMap<NodeId, Long>()
    private val seenIncarnation = TreeMap<NodeId, Int>()
    private val reported = TreeSet<Long>()

    /**
     * Records entries applied by live nodes and checks them against earlier applications.
     *
     * @param nodes Snapshots of all nodes in index order.
     * @return Details of indexes where two nodes applied different entries.
     */
    public override fun check(nodes: List<NodeState>): List<String> {
        val out = mutableListOf<String>()
        for (node in nodes) {
            if (!node.up) continue
            val id = node.id
            if (seenIncarnation[id] != node.incarnation) {
                seenIncarnation[id] = node.incarnation
                seenApplied[id] = 0
            }
            val seen = seenApplied.getValue(id)
            if (node.lastApplied < seen) {
                seenApplied[id] = node.lastApplied
                continue
            }
            for (idx in (seen + 1)..node.lastApplied) {
                val entry = node.log.getOrNull((idx - 1).toInt()) ?: break
                val prev = applied[idx]
                if (prev == null) {
                    applied[idx] = entry
                    appliedBy[idx] = id
                } else if (prev != entry && reported.add(idx)) {
                    out.add(
                        "${id.value} applied index $idx (term ${entry.term}) but " +
                            "${appliedBy.getValue(idx).value} applied a different entry (term ${prev.term})",
                    )
                }
            }
            seenApplied[id] = node.lastApplied
        }
        return out
    }
}
