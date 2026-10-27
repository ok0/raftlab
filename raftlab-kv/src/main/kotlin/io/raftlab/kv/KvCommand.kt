package io.raftlab.kv

import kotlinx.serialization.Serializable

/**
 * One client operation. The pair `(clientId, seq)` identifies it for duplicate suppression.
 */
@Serializable
public data class KvCommand(
    /** Identifier of the submitting client. */
    public val clientId: String,
    /** Per-client sequence number, starting at 1. */
    public val seq: Long,
    /** The operation to perform. */
    public val op: KvOp,
) {
    init {
        require(seq >= 1) { "seq must be >= 1: $seq" }
    }
}
