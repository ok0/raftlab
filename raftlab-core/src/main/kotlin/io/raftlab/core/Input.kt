package io.raftlab.core

/** An input fed to [RaftNode.step] by a driver. */
public sealed interface Input {
    /** One unit of logical time. */
    public data object Tick : Input

    /** Delivery of [msg] to the node. */
    public data class Receive(
        /** The delivered message. */
        public val msg: RaftMessage,
    ) : Input
}
