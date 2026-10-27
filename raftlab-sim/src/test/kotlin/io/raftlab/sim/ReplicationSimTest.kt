package io.raftlab.sim

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

class ReplicationSimTest {
    @Test
    fun proposeWithoutLeaderReturnsNull() {
        Simulation(1).propose("x".encodeToByteArray()) shouldBe null
    }

    @Test
    fun allNodesApplyTheSameCommandSequence() {
        for (seed in 1L..3L) {
            val sim = replicationRun(seed)
            sim.violations.shouldBeEmpty()
            val lists = sim.nodeIds.map { sim.stateMachine(it).applied }
            val longest = lists.maxBy { it.size }
            (longest.size >= 20) shouldBe true
            for (list in lists) {
                longest.take(list.size) shouldBe list
            }
            longest.map { it.bytes.decodeToString() }.toSet().size shouldBe longest.size
            sim.nodes().all { it.up } shouldBe true
        }
    }

    @Test
    fun checkerDoesNotAlterTraceOrDraws() {
        val fdA = FirstDraws()
        val a = replicationRun(5, checker = InvariantChecker.standard(), factory = fdA.factory())
        val fdB = FirstDraws()
        val b = replicationRun(5, checker = InvariantChecker.none(), factory = fdB.factory())
        TraceCodec.encode(a.trace) shouldBe TraceCodec.encode(b.trace)
        for (id in a.nodeIds) {
            fdA.byNode[id]!!.draws shouldBe fdB.byNode[id]!!.draws
        }
    }

    @Test
    fun sameSeedSameBytesWithProposals() {
        TraceCodec.encode(replicationRun(11).trace) shouldBe TraceCodec.encode(replicationRun(11).trace)
        TraceCodec.encode(replicationRun(12).trace) shouldNotBe TraceCodec.encode(replicationRun(11).trace)
        (replicationRun(11).trace.any { it is CommitAdvanced }) shouldBe true
    }
}
