package io.raftlab.kv

import io.raftlab.core.Effect

/** An output of [KvServer] that the driver must act on. */
public sealed interface KvServerOutput {
    /** Delivers [response] to the client that sent `response.requestId`; routing is the driver's job. */
    public data class Reply(
        /** The response to deliver. */
        public val response: ClientResponse,
    ) : KvServerOutput

    /**
     * An effect of a `RaftNode.step` made by the server itself. The driver must carry out `Effect.Send` and must
     * not pass this effect back to [KvServer.onEffects].
     */
    public data class NodeEffect(
        /** The node effect to carry out. */
        public val effect: Effect,
    ) : KvServerOutput
}
