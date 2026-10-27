package io.raftlab.net

import io.raftlab.core.RaftMessage
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.ClientResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One transport message; the `type` discriminator selects the variant. */
@Serializable
public sealed interface Frame {
    /** A Raft message exchanged between peers. */
    @Serializable
    @SerialName("peer")
    public data class Peer(
        /** The Raft message carried by this frame. */
        public val message: RaftMessage,
    ) : Frame

    /** A client command sent to a node. */
    @Serializable
    @SerialName("request")
    public data class Request(
        /** The client request carried by this frame. */
        public val request: ClientRequest,
    ) : Frame

    /** A node's answer to a [Request]. */
    @Serializable
    @SerialName("response")
    public data class Response(
        /** The client response carried by this frame. */
        public val response: ClientResponse,
    ) : Frame
}
