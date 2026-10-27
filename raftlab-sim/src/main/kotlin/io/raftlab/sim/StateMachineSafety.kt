package io.raftlab.sim

import io.raftlab.core.LogEntry
import io.raftlab.core.Snapshot
import io.raftlab.trace.NodeId
import java.util.TreeMap
import java.util.TreeSet

/**
 * Checks the State Machine Safety property: no two nodes apply different entries at the same index.
 *
 * The first entry applied at each index is remembered, so a divergence is still detected after the node that
 * applied it has crashed. Each index is reported at most once.
 *
 * A snapshot counts as having applied everything through its last index; snapshots must agree with the applied
 * entry at their last index and with each other.
 */
public class StateMachineSafety : Invariant {
    /** Stable name of this invariant. */
    public override val name: String = "state-machine-safety"

    private val applied = TreeMap<Long, LogEntry>()
    private val appliedBy = TreeMap<Long, NodeId>()
    private val seenApplied = TreeMap<NodeId, Long>()
    private val seenIncarnation = TreeMap<NodeId, Int>()
    private val reported = TreeSet<Long>()
    private val snapshots = TreeMap<Long, Snapshot>()
    private val snapshotBy = TreeMap<Long, NodeId>()
    private val seenSnapshot = TreeMap<NodeId, Long>()
    private val reportedSnapshot = TreeSet<String>()

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
            if (seenSnapshot[id] != node.snapshotIndex) {
                recordSnapshot(node, out)
                seenSnapshot[id] = node.snapshotIndex
            }
            val seen = seenApplied.getValue(id)
            if (node.lastApplied < seen) {
                seenApplied[id] = node.lastApplied
                continue
            }
            for (idx in (maxOf(seen, node.snapshotIndex) + 1)..node.lastApplied) {
                val entry = node.entryAt(idx) ?: break
                val prev = applied[idx]
                if (prev == null) {
                    applied[idx] = entry
                    appliedBy[idx] = id
                    checkSnapshotTerm(idx, entry, out)
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

    private fun recordSnapshot(
        node: NodeState,
        out: MutableList<String>,
    ) {
        val snap = node.snapshot ?: return
        val idx = snap.meta.lastIndex
        val first = snapshots[idx]
        if (first == null) {
            snapshots[idx] = snap
            snapshotBy[idx] = node.id
        } else if ((first.meta.lastTerm != snap.meta.lastTerm || !first.data.contentEquals(snap.data)) &&
            reportedSnapshot.add("data|$idx")
        ) {
            out.add(
                "${node.id.value} snapshot at index $idx differs from the snapshot of " +
                    "${snapshotBy.getValue(idx).value} at the same index",
            )
        }
        val entry = applied[idx]
        if (entry != null && entry.term != snap.meta.lastTerm && reportedSnapshot.add("term|$idx|${node.id.value}")) {
            out.add(
                "${node.id.value} snapshot at index $idx has term ${snap.meta.lastTerm} but the entry " +
                    "applied at index $idx has term ${entry.term}",
            )
        }
    }

    private fun checkSnapshotTerm(
        idx: Long,
        entry: LogEntry,
        out: MutableList<String>,
    ) {
        val s = snapshots[idx] ?: return
        val by = snapshotBy.getValue(idx)
        if (s.meta.lastTerm != entry.term && reportedSnapshot.add("term|$idx|${by.value}")) {
            out.add(
                "${by.value} snapshot at index $idx has term ${s.meta.lastTerm} but the entry " +
                    "applied at index $idx has term ${entry.term}",
            )
        }
    }
}
