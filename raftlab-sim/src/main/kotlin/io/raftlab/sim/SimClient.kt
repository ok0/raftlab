package io.raftlab.sim

import io.raftlab.core.RandomSource
import io.raftlab.kv.ClientResponse
import io.raftlab.kv.KvClientAction
import io.raftlab.kv.KvClientCore
import io.raftlab.kv.KvOp
import io.raftlab.trace.NodeId

internal class SimClient(
    val clientId: String,
    val ops: List<KvOp>,
    private val load: ClientLoad,
    members: Set<NodeId>,
    private val sim: Simulation,
    private val random: RandomSource,
    private val history: HistoryRecorder,
) : ClientEndpoint {
    val core = KvClientCore(clientId, members)
    val attempts = ArrayList<KvClientAction.Send>()
    val responses = ArrayList<ClientResponse>()
    private var next = 0
    private var token = 0L

    fun start() {
        sim.post(load.startAt) { issue() }
    }

    private fun issue() {
        if (next >= ops.size) return
        history.invoked(clientId, next + 1L, ops[next])
        send(core.submit(ops[next]))
    }

    private fun send(a: KvClientAction.Send) {
        attempts += a
        val t = ++token
        sim.clientSend(a.target, a.request, delay(), this)
        sim.post(sim.now + load.timeout) { if (t == token) onTimer() }
    }

    private fun onTimer() {
        val a = core.onTimeout()
        if (a is KvClientAction.Send) send(a)
    }

    override fun replyDelay(): Long = delay()

    override fun receive(response: ClientResponse) {
        responses += response
        when (val a = core.onResponse(response)) {
            is KvClientAction.Send -> {
                send(a)
            }

            is KvClientAction.Done -> {
                history.returned(clientId, next + 1L, a.result)
                next++
                if (next < ops.size) sim.post(sim.now + load.thinkTime) { issue() }
            }

            KvClientAction.Ignore -> {}
        }
    }

    private fun delay(): Long = load.minDelay + random.nextLong(load.maxDelay - load.minDelay + 1)
}
