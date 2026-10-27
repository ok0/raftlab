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

    /** Client request to append [command] as a new log entry; only a leader accepts it. */
    public class Propose(
        /** Caller-chosen id echoed in the eventual [Effect.Applied] or [Effect.Rejected]. */
        public val requestId: Long,
        /** Raw command bytes to append. */
        public val command: ByteArray,
    ) : Input {
        /** Compares proposals by [requestId] and by [command] content. */
        override fun equals(other: Any?): Boolean =
            other is Propose && requestId == other.requestId && command.contentEquals(other.command)

        /** Hashes proposals by [requestId] and by [command] content. */
        override fun hashCode(): Int = 31 * requestId.hashCode() + command.contentHashCode()

        /** Renders the request id and the [command] content. */
        override fun toString(): String = "Propose(requestId=$requestId, command=${command.contentToString()})"
    }
}
