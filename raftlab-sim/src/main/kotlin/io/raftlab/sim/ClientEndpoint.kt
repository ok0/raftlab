package io.raftlab.sim

import io.raftlab.kv.ClientResponse

/** A simulated client endpoint that receives replies routed by the `Simulation`. */
internal interface ClientEndpoint {
    /** Draws the virtual delay (ms) of the next server-to-client reply from the client's own stream. */
    fun replyDelay(): Long

    /** Receives a reply carrying the client's own request id. */
    fun receive(response: ClientResponse)
}
