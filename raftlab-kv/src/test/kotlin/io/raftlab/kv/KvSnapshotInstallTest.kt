package io.raftlab.kv

import io.kotest.matchers.shouldBe
import io.raftlab.core.RaftConfig
import org.junit.jupiter.api.Test

class KvSnapshotInstallTest {
    @Test
    fun installRestoresDataAndSessionsAndDedupsRetry() {
        val c = KvCluster(config = RaftConfig(snapshotThreshold = 3))
        c.electLeader(n1)
        c.settle(10)
        c.isolated += n3

        val a = KvClientCore("a", setOf(n1, n2, n3))
        for (i in 1..6) {
            c.run(a, a.submit(KvOp.Put("k$i", "v$i"))) shouldBe KvResult.Ok(null)
        }

        c.dropReplies = true
        val casX = a.submit(KvOp.Cas("x", null, "1"))
        c.request(n1, casX.request)
        c.dropReplies = false
        c.dropped.last().result shouldBe KvResult.Ok("1")
        repeat(30) { c.tick(listOf(n1, n2)) }

        c.isolated.clear()
        repeat(30) { c.tick(listOf(n1, n2, n3)) }

        (c.nodes.getValue(n3).snapshotIndex > 0L) shouldBe true
        val m1 = c.machines.getValue(n1)
        val m3 = c.machines.getValue(n3)
        m3.dataForChecks() shouldBe m1.dataForChecks()
        m3.sessionForChecks("a") shouldBe m1.sessionForChecks("a")
        m3.snapshot().contentEquals(m1.snapshot()) shouldBe true

        c.isolated += n1
        c.electLeader(n3)
        val lastSeqBefore = m3.sessionForChecks("a")?.lastSeq
        val replies = c.request(n3, casX.request)
        replies.single().second.result shouldBe KvResult.Ok("1")
        m3.dataForChecks()["x"] shouldBe "1"
        m3.sessionForChecks("a")?.lastSeq shouldBe lastSeqBefore
    }

    @Test
    fun equalStateSnapshotsAreByteEqual() {
        val commands =
            listOf(
                KvCommand("a", 1, KvOp.Put("k", "v1")),
                KvCommand("a", 2, KvOp.Cas("k", "v1", "v2")),
                KvCommand("b", 1, KvOp.Put("z", "b")),
                KvCommand("b", 2, KvOp.Delete("z")),
            )
        val first = KvStateMachine()
        val second = KvStateMachine()
        commands.forEachIndexed { i, cmd ->
            first.apply(i + 1L, KvCodec.encodeCommand(cmd))
            second.apply(i + 1L, KvCodec.encodeCommand(cmd))
        }
        first.snapshot().contentEquals(second.snapshot()) shouldBe true

        val third = KvStateMachine()
        third.restore(first.snapshot())
        third.snapshot().contentEquals(first.snapshot()) shouldBe true
    }
}
