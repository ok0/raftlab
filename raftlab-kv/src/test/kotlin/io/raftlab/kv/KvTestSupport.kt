package io.raftlab.kv

import io.raftlab.core.Effect
import io.raftlab.core.InMemoryStorage
import io.raftlab.core.Input
import io.raftlab.core.RaftConfig
import io.raftlab.core.RaftMessage
import io.raftlab.core.RaftNode
import io.raftlab.core.SplitMix64
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.TraceSink

val n1 = NodeId("n1")
val n2 = NodeId("n2")
val n3 = NodeId("n3")

fun kvNode(
    id: NodeId,
    members: Set<NodeId>,
    machine: KvStateMachine,
    seed: Long = 1,
    config: RaftConfig = RaftConfig(),
): RaftNode = RaftNode(id, members, config, InMemoryStorage(), machine, SplitMix64(seed), TraceSink { })

fun List<KvServerOutput>.replies(): List<ClientResponse> = filterIsInstance<KvServerOutput.Reply>().map { it.response }

class KvCluster(
    seed: Long = 7,
    config: RaftConfig = RaftConfig(),
) {
    private val ids = listOf(n1, n2, n3)
    val machines: Map<NodeId, KvStateMachine> = ids.associateWith { KvStateMachine() }
    val nodes: Map<NodeId, RaftNode> =
        ids.withIndex().associate { (i, id) ->
            id to kvNode(id, ids.toSet(), machines.getValue(id), seed * 31 + i, config)
        }
    val servers: Map<NodeId, KvServer> = nodes.mapValues { KvServer(it.value) }
    val isolated: MutableSet<NodeId> = mutableSetOf()
    var dropReplies = false
    val dropped: MutableList<ClientResponse> = mutableListOf()
    val stray: MutableList<Pair<NodeId, ClientResponse>> = mutableListOf()
    private val queue = ArrayDeque<RaftMessage>()

    private fun enqueue(effects: List<Effect>) {
        for (effect in effects) {
            if (effect is Effect.Send && effect.msg.from !in isolated && effect.msg.to !in isolated) {
                queue.addLast(effect.msg)
            }
        }
    }

    private fun drain(sink: MutableList<Pair<NodeId, ClientResponse>>) {
        while (queue.isNotEmpty()) {
            val msg = queue.removeFirst()
            val effects = nodes.getValue(msg.to).step(Input.Receive(msg))
            enqueue(effects)
            servers
                .getValue(msg.to)
                .onEffects(effects)
                .replies()
                .forEach { sink += msg.to to it }
        }
    }

    fun request(
        target: NodeId,
        request: ClientRequest,
    ): List<Pair<NodeId, ClientResponse>> {
        val collected = mutableListOf<Pair<NodeId, ClientResponse>>()
        for (output in servers.getValue(target).onClientRequest(request)) {
            when (output) {
                is KvServerOutput.NodeEffect -> enqueue(listOf(output.effect))
                is KvServerOutput.Reply -> collected += target to output.response
            }
        }
        drain(collected)
        if (dropReplies) {
            dropped += collected.map { it.second }
            return emptyList()
        }
        return collected
    }

    fun tick(only: Collection<NodeId> = nodes.keys) {
        for (id in only.sorted()) {
            val effects = nodes.getValue(id).step(Input.Tick)
            enqueue(effects)
            servers
                .getValue(id)
                .onEffects(effects)
                .replies()
                .forEach { stray += id to it }
        }
        drain(stray)
    }

    fun electLeader(id: NodeId) {
        repeat(100) {
            if (nodes.getValue(id).role == Role.LEADER) return
            tick(listOf(id))
        }
        check(nodes.getValue(id).role == Role.LEADER) { "$id did not become leader" }
    }

    fun settle(rounds: Int) {
        repeat(rounds) { tick() }
    }
}

fun KvCluster.run(
    client: KvClientCore,
    first: KvClientAction.Send,
    maxAttempts: Int = 10,
    targets: MutableList<NodeId>? = null,
): KvResult {
    var send = first
    repeat(maxAttempts) {
        targets?.add(send.target)
        val replies = request(send.target, send.request)
        val reply = replies.map { it.second }.singleOrNull { it.requestId == send.request.requestId }
        val action = if (reply == null) client.onTimeout() else client.onResponse(reply)
        when (action) {
            is KvClientAction.Done -> {
                return action.result
            }

            is KvClientAction.Send -> {
                send = action
            }

            KvClientAction.Ignore -> {
                error("unexpected Ignore for request ${send.request.requestId}")
            }
        }
    }
    error("no result")
}
