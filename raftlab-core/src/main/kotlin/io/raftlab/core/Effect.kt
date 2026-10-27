package io.raftlab.core

/** An action requested by [RaftNode.step] that the driver must carry out. */
public sealed interface Effect {
    /** Send [msg] to `msg.to`. */
    public data class Send(
        /** The message to send. */
        public val msg: RaftMessage,
    ) : Effect
}
