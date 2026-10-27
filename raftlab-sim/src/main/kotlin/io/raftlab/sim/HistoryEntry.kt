package io.raftlab.sim

import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult

/**
 * One client operation as recorded in a [History]; each operation has exactly one entry.
 *
 * [result] is the final `Ok` or `CasFailed` and is null while the operation is pending. A pending operation is
 * possibly applied: it may or may not have taken effect.
 */
public data class HistoryEntry(
    /** Identifier of the client that issued the operation. */
    public val clientId: String,
    /** Sequence number of the operation within its client. */
    public val opId: Long,
    /** The operation issued. */
    public val op: KvOp,
    /** Virtual time at which the operation was invoked. */
    public val invokedAt: Long,
    /** Virtual time at which the operation returned, or null while pending. */
    public val returnedAt: Long?,
    /** Final result, or null while pending. */
    public val result: KvResult?,
) {
    /** True while the operation has not returned; such an operation is possibly applied. */
    public val pending: Boolean get() = returnedAt == null
}
