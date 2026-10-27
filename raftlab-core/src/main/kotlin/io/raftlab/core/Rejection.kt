package io.raftlab.core

import io.raftlab.trace.NodeId

/** Why a request given to [RaftNode.step] was not carried out. */
public sealed interface Rejection {
    /** This node is not the leader. */
    public data class NotLeader(
        /** Believed leader, or null when unknown. */
        public val hint: NodeId?,
    ) : Rejection

    /** The outcome cannot be known: the entry may still be committed by a later leader. */
    public data object Unknown : Rejection
}
