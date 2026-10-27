package io.raftlab.sim

import io.raftlab.trace.NodeId
import java.util.TreeMap
import java.util.TreeSet

/**
 * Checks that commitIndex and lastApplied never decrease while a node keeps the same incarnation,
 * and that lastApplied never exceeds commitIndex.
 *
 * A change of incarnation resets the baselines, since a restarted node starts with fresh volatile state.
 */
public class CommitMonotonicity : Invariant {
    /** Stable name of this invariant. */
    public override val name: String = "commit-monotonicity"

    private val lastCommit = TreeMap<NodeId, Long>()
    private val lastApplied = TreeMap<NodeId, Long>()
    private val incarnation = TreeMap<NodeId, Int>()
    private val reportedAhead = TreeSet<String>()

    /**
     * Compares each live node with its previous snapshot.
     *
     * @param nodes Snapshots of all nodes in index order.
     * @return Details of decreases and of lastApplied exceeding commitIndex.
     */
    public override fun check(nodes: List<NodeState>): List<String> {
        val out = mutableListOf<String>()
        for (node in nodes) {
            if (!node.up) continue
            val id = node.id
            if (incarnation[id] != node.incarnation) {
                incarnation[id] = node.incarnation
            } else {
                val oldCommit = lastCommit.getValue(id)
                val oldApplied = lastApplied.getValue(id)
                if (node.commitIndex < oldCommit) {
                    out.add("${id.value} commitIndex decreased from $oldCommit to ${node.commitIndex}")
                }
                if (node.lastApplied < oldApplied) {
                    out.add("${id.value} lastApplied decreased from $oldApplied to ${node.lastApplied}")
                }
            }
            lastCommit[id] = node.commitIndex
            lastApplied[id] = node.lastApplied
            if (node.lastApplied > node.commitIndex && reportedAhead.add("${id.value}|${node.incarnation}")) {
                out.add("${id.value} lastApplied ${node.lastApplied} exceeds commitIndex ${node.commitIndex}")
            }
        }
        return out
    }
}
