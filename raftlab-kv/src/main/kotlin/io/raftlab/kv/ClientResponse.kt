package io.raftlab.kv

import kotlinx.serialization.Serializable

/** A server's answer to a [ClientRequest]. */
@Serializable
public data class ClientResponse(
    /** The [ClientRequest.requestId] this response answers. */
    public val requestId: Long,
    /** The outcome of the request. */
    public val result: KvResult,
)
