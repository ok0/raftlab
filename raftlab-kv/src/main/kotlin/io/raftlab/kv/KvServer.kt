package io.raftlab.kv

import io.raftlab.core.Effect
import io.raftlab.core.Input
import io.raftlab.core.RaftNode
import io.raftlab.core.Rejection
import java.util.TreeMap

/**
 * Driver-agnostic KV server in front of a [RaftNode]. It has no threads, clocks or I/O.
 *
 * `Put`, `Delete` and `Cas` are proposed through the log; `Get` uses `Input.ReadIndex` and is answered from
 * [stateMachine] when the node reports `Effect.ReadReady`, so reads append no log entry and touch no session.
 * A pending `Get` answers `NotLeader` when the node steps down.
 * Internal proposal ids are 1, 2, 3, … in request order. The server relies on the node reporting
 * `Rejection.Unknown` for every pending proposal when it steps down. Each client request gets at most one reply.
 * `Busy` and `Unknown` map to `Timeout`.
 *
 * @param node the Raft node this server proposes to and reads effects from.
 * @param stateMachine the state machine applied by [node]; `Get` is evaluated against it after `Effect.ReadReady`.
 */
public class KvServer(
    private val node: RaftNode,
    private val stateMachine: KvStateMachine,
) {
    private var nextInternalId: Long = 1
    private val pending: MutableMap<Long, Long> = TreeMap()
    private val reads: MutableMap<Long, PendingGet> = TreeMap()

    /** Proposes the command of [request] to the node and returns the resulting outputs. */
    public fun onClientRequest(request: ClientRequest): List<KvServerOutput> {
        val internalId = nextInternalId++
        val op = request.command.op
        val input: Input
        if (op is KvOp.Get) {
            reads[internalId] = PendingGet(request.requestId, op.key)
            input = Input.ReadIndex(internalId)
        } else {
            pending[internalId] = request.requestId
            input = Input.Propose(internalId, KvCodec.encodeCommand(request.command))
        }
        val effects = node.step(input)
        val out = ArrayList<KvServerOutput>()
        for (effect in effects) {
            out += KvServerOutput.NodeEffect(effect)
            reply(effect)?.let { out += it }
        }
        return out
    }

    /**
     * Handles effects returned by the driver's own `RaftNode.step` calls and returns only [KvServerOutput.Reply]
     * outputs.
     */
    public fun onEffects(effects: List<Effect>): List<KvServerOutput> = effects.mapNotNull { reply(it) }

    private fun reply(effect: Effect): KvServerOutput.Reply? =
        when (effect) {
            is Effect.Applied -> {
                val id = effect.requestId ?: return null
                val clientId = pending.remove(id) ?: return null
                val result =
                    try {
                        KvCodec.decodeResult(effect.result)
                    } catch (e: IllegalArgumentException) {
                        KvResult.Timeout
                    }
                KvServerOutput.Reply(ClientResponse(clientId, result))
            }

            is Effect.ReadReady -> {
                val get = reads.remove(effect.requestId) ?: return null
                KvServerOutput.Reply(ClientResponse(get.requestId, KvResult.Ok(stateMachine.read(get.key))))
            }

            is Effect.Rejected -> {
                val clientId =
                    pending.remove(effect.requestId)
                        ?: reads.remove(effect.requestId)?.requestId
                        ?: return null
                KvServerOutput.Reply(ClientResponse(clientId, toResult(effect.reason)))
            }

            is Effect.Send -> {
                null
            }
        }

    private fun toResult(reason: Rejection): KvResult =
        when (reason) {
            is Rejection.NotLeader -> KvResult.NotLeader(reason.hint)
            Rejection.Busy -> KvResult.Timeout
            Rejection.Unknown -> KvResult.Timeout
        }

    private class PendingGet(
        val requestId: Long,
        val key: String,
    )
}
