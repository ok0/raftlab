package io.raftlab.kv

import io.raftlab.trace.NodeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The result of applying a [KvOp], or the outcome reported to a client. */
@Serializable
public sealed interface KvResult {
    /** The operation succeeded; [value] is the read or previous value, or null when absent. */
    @Serializable
    @SerialName("Ok")
    public data class Ok(
        /** The value read, written or removed, or null when absent. */
        public val value: String?,
    ) : KvResult

    /** A compare-and-swap found a different value than expected. */
    @Serializable
    @SerialName("CasFailed")
    public data class CasFailed(
        /** The value actually stored, or null when absent. */
        public val actual: String?,
    ) : KvResult

    /** The contacted node is not the leader. */
    @Serializable
    @SerialName("NotLeader")
    public data class NotLeader(
        /** Believed leader, or null when unknown. */
        public val hint: NodeId?,
    ) : KvResult

    /** The outcome is unknown; it is safe to retry with the same seq. */
    @Serializable
    @SerialName("Timeout")
    public data object Timeout : KvResult
}
