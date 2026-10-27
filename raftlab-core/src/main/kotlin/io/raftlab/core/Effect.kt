package io.raftlab.core

/** An action requested by [RaftNode.step] that the driver must carry out. */
public sealed interface Effect {
    /** Send [msg] to `msg.to`. */
    public data class Send(
        /** The message to send. */
        public val msg: RaftMessage,
    ) : Effect

    /**
     * The entry at [index] was applied; [requestId] is set only for a proposal made by this node in its current
     * leadership, otherwise null.
     */
    public class Applied(
        /** Id of the proposal that produced this entry, or null. */
        public val requestId: Long?,
        /** Log index of the applied entry. */
        public val index: Long,
        /** Result bytes returned by the state machine. */
        public val result: ByteArray,
    ) : Effect {
        /** Compares applied effects by [requestId], [index] and [result] content. */
        override fun equals(other: Any?): Boolean =
            other is Applied && requestId == other.requestId && index == other.index &&
                result.contentEquals(other.result)

        /** Hashes applied effects by [requestId], [index] and [result] content. */
        override fun hashCode(): Int =
            31 * (31 * (requestId?.hashCode() ?: 0) + index.hashCode()) + result.contentHashCode()

        /** Renders the request id, index and [result] content. */
        override fun toString(): String =
            "Applied(requestId=$requestId, index=$index, result=${result.contentToString()})"
    }

    /** The request [requestId] was not, or may not have been, carried out. */
    public data class Rejected(
        /** Id of the rejected request. */
        public val requestId: Long,
        /** Why the request was rejected. */
        public val reason: Rejection,
    ) : Effect
}
