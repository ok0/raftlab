package io.raftlab.sim

import io.raftlab.kv.ClientResponse

/** A simulated client endpoint that receives replies routed by the `Simulation`. */
internal interface ClientEndpoint {
    /**
     * Draws the virtual delays (ms) of the copies of the next server-to-client reply from the client's own stream.
     *
     * An empty list means the reply is dropped and two delays mean it is duplicated.
     */
    fun replyDelays(): List<Long>

    /** Receives a reply carrying the client's own request id. */
    fun receive(response: ClientResponse)
}
