package io.raftlab.sim

import io.raftlab.core.LogEntry
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import java.util.TreeMap
import java.util.TreeSet

/**
 * Checks the Leader Completeness property: a leader holds every entry that was committed in an earlier term.
 *
 * The term recorded for a committed entry is the term of the observing node at the time it was seen committed.
 * It is an upper bound of the real commit term, so a leader is never wrongly accused. Records survive crashes.
 * Entries covered by a node's snapshot are not recorded from that node, and a leader whose snapshot covers an
 * index is not checked there; snapshot terms are checked by [StateMachineSafety].
 */
public class LeaderCompleteness : Invariant {
    /** Stable name of this invariant. */
    public override val name: String = "leader-completeness"

    private val committed = TreeMap<Long, LogEntry>()
    private val committedInTerm = TreeMap<Long, Long>()
    private val seenCommit = TreeMap<NodeId, Long>()
    private val seenIncarnation = TreeMap<NodeId, Int>()
    private val reported = TreeSet<String>()

    /**
     * Records newly committed entries and checks leaders against them.
     *
     * @param nodes Snapshots of all nodes in index order.
     * @return Details of leaders that newly lack a committed entry.
     */
    public override fun check(nodes: List<NodeState>): List<String> {
        val out = mutableListOf<String>()
        for (node in nodes) {
            if (!node.up) continue
            val id = node.id
            if (seenIncarnation[id] != node.incarnation) {
                seenIncarnation[id] = node.incarnation
                seenCommit[id] = 0
            }
            val upTo = minOf(node.commitIndex, node.lastIndex)
            for (idx in (maxOf(seenCommit.getValue(id), node.snapshotIndex) + 1)..upTo) {
                if (idx !in committed) {
                    committed[idx] = checkNotNull(node.entryAt(idx))
                    committedInTerm[idx] = node.currentTerm
                }
            }
            seenCommit[id] = node.commitIndex
        }
        for (node in nodes) {
            if (!node.up || node.role != Role.LEADER) continue
            for ((idx, entry) in committed) {
                if (node.currentTerm <= committedInTerm.getValue(idx)) continue
                if (idx <= node.snapshotIndex) continue
                val held = node.entryAt(idx)
                if (held != entry && reported.add("${node.id.value}|${node.currentTerm}|$idx")) {
                    out.add(
                        "leader ${node.id.value} (term ${node.currentTerm}) does not hold committed " +
                            "entry at index $idx (term ${entry.term})",
                    )
                }
            }
        }
        return out
    }
}
