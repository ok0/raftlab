package io.raftlab.sim

import io.raftlab.core.EntryPayload
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import java.util.TreeMap
import java.util.TreeSet

/**
 * Checks membership-change safety over the configurations the nodes hold.
 *
 * Committed `Config` entries (and snapshot memberships) must agree across nodes at every index, and the committed
 * configuration may change by at most one server per step. A leader holding more than one uncommitted `Config`
 * entry is reported as well. The initial configuration is the set of node ids seen in the first check.
 * The invariant is read-only: it does not touch the trace or draw random numbers.
 */
public class MembershipSafety : Invariant {
    /** Stable name of this invariant. */
    public override val name: String = "membership-safety"

    private var initial: Set<NodeId>? = null
    private val committed = TreeMap<Long, Set<NodeId>>()
    private var globalCommit = 0L
    private val reported = TreeSet<String>()

    /**
     * Records the committed configurations seen in the given snapshots and reports divergence, uncommitted
     * configuration pile-ups on leaders, and multi-server jumps of the committed configuration.
     *
     * @param nodes Snapshots of all nodes in index order.
     * @return Details of newly found violations.
     */
    public override fun check(nodes: List<NodeState>): List<String> {
        val out = mutableListOf<String>()
        val base: Set<NodeId> = initial ?: nodes.map { it.id }.toSortedSet().also { initial = it }
        var changed = false
        for (node in nodes) {
            node.snapshot?.let { snap ->
                if (record(snap.meta.lastIndex, snap.meta.members, node.id, out)) changed = true
            }
            if (!node.up) continue
            for (entry in node.log) {
                val payload = entry.payload
                if (payload is EntryPayload.Config && entry.index <= node.commitIndex) {
                    if (record(entry.index, payload.members, node.id, out)) changed = true
                }
            }
        }
        globalCommit = maxOf(globalCommit, nodes.filter { it.up }.maxOfOrNull { it.commitIndex } ?: 0L)
        for (node in nodes) {
            if (!node.up || node.role != Role.LEADER) continue
            val bound = maxOf(globalCommit, node.commitIndex)
            val pendingIdx = node.log.filter { it.payload is EntryPayload.Config && it.index > bound }.map { it.index }
            if (pendingIdx.size > 1 && reported.add("pending|${node.id.value}|${node.currentTerm}")) {
                out.add(
                    "leader ${node.id.value} (term ${node.currentTerm}) holds ${pendingIdx.size} " +
                        "uncommitted config entries at indexes $pendingIdx",
                )
            }
        }
        if (changed) {
            var prev = base
            for ((index, members) in committed) {
                val k = ((prev - members) + (members - prev)).size
                if (k > 1 && reported.add("jump|$index")) {
                    out.add(
                        "committed configuration changed by $k servers at index $index: " +
                            "${fmt(prev)} -> ${fmt(members)}",
                    )
                }
                prev = members
            }
        }
        return out
    }

    private fun record(
        index: Long,
        members: Set<NodeId>,
        node: NodeId,
        out: MutableList<String>,
    ): Boolean {
        val existing = committed[index]
        if (existing == null) {
            committed[index] = members.toSortedSet()
            return true
        }
        if (existing != members && reported.add("diverge|$index")) {
            out.add(
                "committed config entries diverge at index $index: ${fmt(existing)} vs ${fmt(members)} " +
                    "(seen on ${node.value})",
            )
        }
        return false
    }

    private fun fmt(members: Set<NodeId>): String = members.sorted().joinToString(",", "{", "}") { it.value }
}
