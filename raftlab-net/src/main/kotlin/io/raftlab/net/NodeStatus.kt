package io.raftlab.net

import io.raftlab.trace.NodeId
import io.raftlab.trace.Role

/** A point-in-time view of a running node, taken on its loop thread. */
public data class NodeStatus(
    /** The node this status describes. */
    public val id: NodeId,
    /** Current Raft role of the node. */
    public val role: Role,
    /** Latest term the node has seen. */
    public val term: Long,
    /** Id of the last known leader, or null when unknown. */
    public val leaderHint: NodeId?,
    /** Highest log index known to be committed. */
    public val commitIndex: Long,
    /** Highest log index applied to the state machine. */
    public val lastApplied: Long,
)
