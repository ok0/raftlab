package io.raftlab.kv

import kotlinx.serialization.Serializable

/** A command sent by a client to a server. */
@Serializable
public data class ClientRequest(
    /** Chosen by the sender and echoed in the [ClientResponse]. */
    public val requestId: Long,
    /** The command to submit. */
    public val command: KvCommand,
)
