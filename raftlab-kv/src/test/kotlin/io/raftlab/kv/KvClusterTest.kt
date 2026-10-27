package io.raftlab.kv

import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

class KvClusterTest {
    @Test
    fun redirectRetryAndLeaderChangeApplyOnce() {
        val c = KvCluster()
        c.electLeader(n2)
        c.settle(10)
        c.nodes.filterValues { it.role == Role.LEADER }.keys shouldBe setOf(n2)

        val a = KvClientCore("a", setOf(n1, n2, n3))
        val targets = mutableListOf<NodeId>()
        c.run(a, a.submit(KvOp.Put("k", "v1")), targets = targets) shouldBe KvResult.Ok(null)
        targets shouldBe listOf(n1, n2)
        a.leaderGuess shouldBe n2
        c.run(a, a.submit(KvOp.Get("k"))) shouldBe KvResult.Ok("v1")

        c.dropReplies = true
        val casX = a.submit(KvOp.Cas("x", null, "1"))
        c.request(casX.target, casX.request)
        c.dropReplies = false
        c.dropped.last().result shouldBe KvResult.Ok("1")
        c.run(a, a.onTimeout() as KvClientAction.Send) shouldBe KvResult.Ok("1")

        c.dropReplies = true
        val casZ = a.submit(KvOp.Cas("z", null, "a"))
        c.request(n2, casZ.request)
        c.dropReplies = false
        c.dropped.last().result shouldBe KvResult.Ok("a")
        c.isolated += n2
        c.electLeader(n3)
        val b = KvClientCore("b", setOf(n1, n2, n3))
        c.run(b, b.submit(KvOp.Put("z", "b"))) shouldBe KvResult.Ok(null)
        c.run(a, a.onTimeout() as KvClientAction.Send) shouldBe KvResult.Ok("a")

        c.isolated.clear()
        c.settle(60)
        c.nodes.filterValues { it.role == Role.LEADER }.keys shouldBe setOf(n3)
        val commit = c.nodes.getValue(n3).commitIndex
        c.nodes.values.forEach { it.lastApplied shouldBe commit }
        c.machines.values.forEach { it.dataForChecks() shouldBe mapOf("k" to "v1", "x" to "1", "z" to "b") }
        val snapshots = c.machines.values.map { it.snapshot() }
        snapshots.forEach { it.contentEquals(snapshots[0]) shouldBe true }
        c.stray shouldBe emptyList()
    }
}
