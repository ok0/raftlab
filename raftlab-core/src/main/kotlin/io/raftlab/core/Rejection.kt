package io.raftlab.core

import io.raftlab.trace.NodeId

/** Why a request given to [RaftNode.step] was not carried out. */
public sealed interface Rejection {
    /** This node is not the leader. */
    public data class NotLeader(
        /** Believed leader, or null when unknown. */
        public val hint: NodeId?,
    ) : Rejection

    /** The leader cannot start a membership change now: a `Config` entry is uncommitted, a catch-up is in progress, or it has not yet committed an entry of its term. Changes no state; retry later. */
    public data object Busy : Rejection

    /** The outcome cannot be known: the entry may still be committed by a later leader. */
    public data object Unknown : Rejection
}
