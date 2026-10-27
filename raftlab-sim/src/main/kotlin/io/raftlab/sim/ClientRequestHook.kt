package io.raftlab.sim

import io.raftlab.kv.ClientRequest
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeId

/** Test seam: answers a client request instead of the target's `KvServer`; returning null lets the server handle it. */
internal fun interface ClientRequestHook {
    fun answer(
        sim: Simulation,
        target: NodeId,
        request: ClientRequest,
    ): KvResult?
}
