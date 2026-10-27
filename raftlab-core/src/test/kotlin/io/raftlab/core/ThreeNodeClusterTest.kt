package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

class ThreeNodeClusterTest {
    private val ids = listOf(n1, n2, n3)

    @Test
    fun electsOneStableLeader() {
        for (seed in 1L..10L) {
            val nodes: Map<NodeId, RaftNode> =
                ids.mapIndexed { i, id -> id to node(id = id, random = SplitMix64(seed * 31 + i)) }.toMap()
            val queue = ArrayDeque<RaftMessage>()
            val leadersByTerm = mutableMapOf<Long, MutableSet<NodeId>>()
            var checkpoint: Pair<NodeId, Long>? = null

            repeat(500) { round ->
                for (id in ids) {
                    queue.addAll(nodes.getValue(id).tick().sent())
                }
                while (queue.isNotEmpty()) {
                    val msg = queue.removeFirst()
                    queue.addAll(nodes.getValue(msg.to).step(Input.Receive(msg)).sent())
                }
                for (node in nodes.values) {
                    if (node.role == Role.LEADER) {
                        leadersByTerm.getOrPut(node.currentTerm) { mutableSetOf() }.add(node.id)
                    }
                }
                if (round + 1 == 100) {
                    val leader = nodes.values.single { it.role == Role.LEADER }
                    checkpoint = leader.id to leader.currentTerm
                }
            }

            leadersByTerm.values.all { it.size == 1 } shouldBe true
            val (leaderId, term) = checkpoint ?: error("no leader after round 100 for seed $seed")
            val leaders = nodes.values.filter { it.role == Role.LEADER }.map { it.id }
            leaders shouldBe listOf(leaderId)
            for (node in nodes.values) {
                node.currentTerm shouldBe term
            }
            for (node in nodes.values) {
                if (node.id != leaderId) {
                    node.leaderHint shouldBe leaderId
                }
            }
        }
    }
}
