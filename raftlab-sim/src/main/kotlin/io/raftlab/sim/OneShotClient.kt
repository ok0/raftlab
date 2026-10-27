package io.raftlab.sim

import io.raftlab.core.RandomSource
import io.raftlab.kv.ClientRequest
import io.raftlab.kv.ClientResponse
import io.raftlab.kv.KvCommand
import io.raftlab.kv.KvOp
import io.raftlab.kv.KvResult
import io.raftlab.trace.NodeId

internal class OneShotClient(
    val clientId: String,
    private val target: NodeId,
    private val op: KvOp,
    private val sim: Simulation,
    private val random: RandomSource,
    private val history: HistoryRecorder,
) : ClientEndpoint {
    private var returned = false

    fun start() {
        history.invoked(clientId, 1, op)
        sim.clientSend(target, ClientRequest(1, KvCommand(clientId, 1, op)), delay(), this)
    }

    override fun replyDelays(): List<Long> = listOf(delay())

    override fun receive(response: ClientResponse) {
        val r = response.result
        if (returned || (r !is KvResult.Ok && r !is KvResult.CasFailed)) return
        returned = true
        history.returned(clientId, 1, r)
    }

    private fun delay(): Long = 1 + random.nextLong(5)
}
