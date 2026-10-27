package io.raftlab.kv

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class KvStateMachineDataTest {
    @Test
    fun dataSnapshotIsSortedCopy() {
        val m = KvStateMachine()
        m.apply(0, KvCodec.encodeCommand(KvCommand("c", 1, KvOp.Put("b", "2"))))
        m.apply(0, KvCodec.encodeCommand(KvCommand("c", 2, KvOp.Put("a", "1"))))
        val snap = m.dataSnapshot()
        snap shouldBe mapOf("a" to "1", "b" to "2")
        snap.keys.toList() shouldBe listOf("a", "b")
        m.apply(0, KvCodec.encodeCommand(KvCommand("c", 3, KvOp.Delete("a"))))
        snap shouldBe mapOf("a" to "1", "b" to "2")
        m.dataSnapshot() shouldBe mapOf("b" to "2")
    }
}
