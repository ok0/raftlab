package io.raftlab.core

import io.raftlab.trace.NodeId

/**
 * A deterministic test driver: one tick per node per round, then FIFO delivery of the sent messages until the network
 * is quiet. Messages for which [blocked] returns true are dropped at the sender.
 */
class MiniCluster(
    val nodes: Map<NodeId, RaftNode>,
    private val blocked: (RaftMessage) -> Boolean = { false },
) {
    fun round() {
        val queue = ArrayDeque<RaftMessage>()
        for (id in nodes.keys.sortedBy { it.value }) {
            enqueue(queue, nodes.getValue(id).tick())
        }
        while (queue.isNotEmpty()) {
            val m = queue.removeFirst()
            val target = nodes[m.to] ?: continue
            enqueue(queue, target.step(Input.Receive(m)))
        }
    }

    private fun enqueue(
        queue: ArrayDeque<RaftMessage>,
        effects: List<Effect>,
    ) {
        for (m in effects.sent()) {
            if (!blocked(m)) queue.addLast(m)
        }
    }
}
