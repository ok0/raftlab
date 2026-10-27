package io.raftlab.kv

import io.raftlab.trace.NodeId

/** What the driver must do after an input to [KvClientCore]. */
public sealed interface KvClientAction {
    /** Send [request] to [target]. */
    public data class Send(
        /** The node the request must be sent to. */
        public val target: NodeId,
        /** The request to send. */
        public val request: ClientRequest,
    ) : KvClientAction

    /** The final result of the operation: [KvResult.Ok] or [KvResult.CasFailed]. */
    public data class Done(
        /** The final result. */
        public val result: KvResult,
    ) : KvClientAction

    /** A stale or unexpected input; nothing to do. */
    public data object Ignore : KvClientAction
}
