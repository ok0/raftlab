package io.raftlab.kv

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KvStateMachineTest {
    private fun KvStateMachine.run(
        clientId: String,
        seq: Long,
        op: KvOp,
    ): KvResult = KvCodec.decodeResult(apply(0, KvCodec.encodeCommand(KvCommand(clientId, seq, op))))

    @Test
    fun putThenGetReturnsValue() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "v")) shouldBe KvResult.Ok(null)
        m.run("c1", 2, KvOp.Get("k")) shouldBe KvResult.Ok("v")
    }

    @Test
    fun getAbsentReturnsNull() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Get("x")) shouldBe KvResult.Ok(null)
    }

    @Test
    fun getDoesNotChangeData() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "v"))
        val before = m.dataForChecks()
        m.run("c1", 2, KvOp.Get("k"))
        m.dataForChecks() shouldBe before
    }

    @Test
    fun deletePresentReturnsPrevious() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "v"))
        m.run("c1", 2, KvOp.Delete("k")) shouldBe KvResult.Ok("v")
        m.dataForChecks().containsKey("k") shouldBe false
    }

    @Test
    fun deleteAbsentReturnsNull() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Delete("k")) shouldBe KvResult.Ok(null)
    }

    @Test
    fun casSucceedsWhenExpectedMatches() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "v"))
        m.run("c1", 2, KvOp.Cas("k", "v", "w")) shouldBe KvResult.Ok("w")
        m.dataForChecks()["k"] shouldBe "w"
    }

    @Test
    fun casFailureReportsActualAndChangesNothing() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "v"))
        m.run("c1", 2, KvOp.Cas("k", "z", "w")) shouldBe KvResult.CasFailed("v")
        m.dataForChecks()["k"] shouldBe "v"
    }

    @Test
    fun casWithNullExpectedOnAbsentKeyCreates() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Cas("k", null, "a")) shouldBe KvResult.Ok("a")
        m.run("c1", 2, KvOp.Cas("k", null, "b")) shouldBe KvResult.CasFailed("a")
    }

    @Test
    fun casWithNullNewDeletes() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "v"))
        m.run("c1", 2, KvOp.Cas("k", "v", null)) shouldBe KvResult.Ok(null)
        m.dataForChecks().containsKey("k") shouldBe false
    }

    @Test
    fun duplicateSeqReturnsCachedResultWithoutReapplying() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "a")) shouldBe KvResult.Ok(null)
        m.run("c2", 1, KvOp.Put("k", "b")) shouldBe KvResult.Ok(null)
        m.run("c1", 1, KvOp.Put("k", "a")) shouldBe KvResult.Ok(null)
        m.dataForChecks()["k"] shouldBe "b"
    }

    @Test
    fun duplicateCasReturnsSameResult() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Cas("k", null, "a")) shouldBe KvResult.Ok("a")
        m.run("c1", 1, KvOp.Cas("k", null, "a")) shouldBe KvResult.Ok("a")
        m.dataForChecks()["k"] shouldBe "a"
    }

    @Test
    fun staleSeqIsNotApplied() {
        val m = KvStateMachine()
        m.run("c1", 2, KvOp.Put("k", "new")) shouldBe KvResult.Ok(null)
        m.run("c1", 1, KvOp.Put("k", "old")) shouldBe KvResult.Ok(null)
        m.dataForChecks()["k"] shouldBe "new"
        m.sessionForChecks("c1")?.lastSeq shouldBe 2L
    }

    @Test
    fun higherSeqReplacesSession() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Get("k")) shouldBe KvResult.Ok(null)
        m.run("c1", 2, KvOp.Put("k", "v")) shouldBe KvResult.Ok(null)
        m.sessionForChecks("c1")?.lastSeq shouldBe 2L
        m.sessionForChecks("c1")?.lastResult shouldBe KvResult.Ok(null)
    }

    @Test
    fun sessionsAreIndependentPerClient() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("a", "1")) shouldBe KvResult.Ok(null)
        m.run("c2", 1, KvOp.Put("b", "2")) shouldBe KvResult.Ok(null)
        m.dataForChecks() shouldBe mapOf("a" to "1", "b" to "2")
    }

    @Test
    fun undecodableBytesDoNotThrowAndChangeNothing() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "v"))
        val s0 = m.snapshot()
        repeat(2) { m.apply(5, "garbage".encodeToByteArray()).size shouldBe 0 }
        m.apply(5, byteArrayOf()).size shouldBe 0
        m.apply(5, """{"clientId":"c1","seq":0,"op":{"type":"Get","key":"k"}}""".encodeToByteArray()).size shouldBe 0
        m.snapshot().contentEquals(s0) shouldBe true
    }

    @Test
    fun sameCommandSequenceGivesEqualState() {
        val commands =
            listOf(
                Triple("c1", 1L, KvOp.Put("a", "1")),
                Triple("c2", 1L, KvOp.Put("b", "2")),
                Triple("c1", 2L, KvOp.Cas("a", "1", "3")),
                Triple("c1", 2L, KvOp.Put("a", "x")),
                Triple("c2", 2L, KvOp.Get("b")),
                Triple("c1", 3L, KvOp.Delete("b")),
            )
        val m1 = KvStateMachine()
        val m2 = KvStateMachine()
        val r1 = commands.map { (c, s, op) -> m1.run(c, s, op) }
        val r2 = commands.map { (c, s, op) -> m2.run(c, s, op) }
        r1 shouldBe r2
        m1.snapshot().contentEquals(m2.snapshot()) shouldBe true
    }

    @Test
    fun snapshotRestoreIntoFreshInstance() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("a", "1"))
        m.run("c1", 2, KvOp.Put("b", "2"))
        m.run("c2", 1, KvOp.Put("c", "3"))
        val b = m.snapshot()
        val fresh = KvStateMachine()
        fresh.restore(b)
        fresh.dataForChecks() shouldBe m.dataForChecks()
        fresh.sessionForChecks("c1") shouldBe m.sessionForChecks("c1")
        fresh.sessionForChecks("c2") shouldBe m.sessionForChecks("c2")
        fresh.snapshot().contentEquals(b) shouldBe true
        fresh.run("c1", 2, KvOp.Put("a", "changed")) shouldBe KvResult.Ok(null)
        fresh.dataForChecks()["a"] shouldBe "1"
    }

    @Test
    fun restoreIntoUsedInstanceDiscardsPriorState() {
        val source = KvStateMachine()
        source.run("c1", 1, KvOp.Put("k", "v"))
        val b = source.snapshot()
        val target = KvStateMachine()
        target.run("c9", 1, KvOp.Put("old", "x"))
        target.restore(b)
        target.dataForChecks() shouldBe mapOf("k" to "v")
        target.sessionForChecks("c9") shouldBe null
    }

    @Test
    fun equalStatesGiveIdenticalSnapshotBytes() {
        val m1 = KvStateMachine()
        m1.run("c1", 1, KvOp.Put("a", "1"))
        m1.run("c2", 1, KvOp.Put("b", "2"))
        val m2 = KvStateMachine()
        m2.run("c2", 1, KvOp.Put("b", "2"))
        m2.run("c1", 1, KvOp.Put("a", "1"))
        m1.snapshot().contentEquals(m2.snapshot()) shouldBe true
    }

    @Test
    fun snapshotGolden() {
        val m = KvStateMachine()
        m.run("c1", 1, KvOp.Put("k", "v"))
        m.snapshot().decodeToString() shouldBe
            """{"data":{"k":"v"},"sessions":{"c1":{"lastSeq":1,"lastResult":{"type":"Ok","value":null}}}}"""
    }
}
