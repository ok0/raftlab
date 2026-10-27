package io.raftlab.sim

import io.raftlab.core.LogEntry
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role

/**
 * Observable state of one simulated node at a point in time.
 *
 * A crashed node has `role = null`, keeps its persisted term and log, has `commitIndex` and `lastApplied` of 0,
 * and an empty `kvData`.
 */
public data class NodeView(
    /** Identifier of the node. */
    public val id: NodeId,
    /** Whether the node is running (not crashed). */
    public val up: Boolean,
    /** Current role, or null when the node is crashed. */
    public val role: Role?,
    /** Current term, or the persisted term when the node is crashed. */
    public val term: Long,
    /** Believed leader, or null when unknown. */
    public val leaderHint: NodeId?,
    /** Highest log index known to be committed, or 0 when crashed. */
    public val commitIndex: Long,
    /** Highest log index applied to the state machine, or 0 when crashed. */
    public val lastApplied: Long,
    /** The node's retained entries after [snapshotIndex] (persisted, also while crashed). */
    public val log: List<LogEntry>,
    /** The key-value data of the node, sorted by key; empty when crashed. */
    public val kvData: Map<String, String>,
    /** Last log index covered by the node's snapshot, or 0 without one. */
    public val snapshotIndex: Long = 0,
) {
    /** Last log index: of the last retained entry, or [snapshotIndex] when the retained log is empty. */
    public val lastIndex: Long get() = log.lastOrNull()?.index ?: snapshotIndex
}
