package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.raftlab.trace.LeadershipTransfer
import io.raftlab.trace.Role
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Test

internal class TransferSimTest {
    @Test
    fun noLeaderIsNoOpAndCallsDrawNothing() {
        val a = Simulation(5)
        a.transferLeadership(n2)
        a.runUntil(2_000)
        val b = Simulation(5)
        b.runUntil(2_000)
        TraceCodec.encode(a.trace) shouldBe TraceCodec.encode(b.trace)
        a.trace.none { it is LeadershipTransfer } shouldBe true
    }

    @Test
    fun transferMovesLeadershipToTarget() {
        val sim = Simulation(7)
        sim.runUntil(5_000) { it.leader() != null }
        sim.runFor(200)
        val old = sim.leader()!!
        val t = sim.nodeIds[(sim.nodeIds.indexOf(old.id) + 1) % sim.nodeIds.size]
        sim.transferLeadership(t)
        sim.runUntil(sim.now + 400) { it.leader()?.id == t } shouldBe true
        sim.leader()!!.currentTerm shouldBe old.currentTerm + 1
        sim.trace.any { it is LeadershipTransfer && it.node == old.id && it.target == t } shouldBe true
        sim.violations shouldBe emptyList()
    }

    @Test
    fun transferToCrashedTargetIsAbandoned() {
        val sim = Simulation(8)
        sim.runUntil(5_000) { it.leader() != null }
        sim.runFor(200)
        val old = sim.leader()!!
        val t = sim.nodes().first { it.role == Role.FOLLOWER }.id
        sim.crash(t)
        sim.transferLeadership(t)
        sim.runFor(1_000)
        sim.leader()!!.id shouldBe old.id
        sim.leader()!!.currentTerm shouldBe old.currentTerm
        sim.trace.any { it is LeadershipTransfer } shouldBe true
        sim.violations shouldBe emptyList()
        val c = sim.leader()!!.commitIndex
        sim.propose("x".encodeToByteArray()) shouldNotBe null
        sim.runFor(200)
        (sim.leader()!!.commitIndex > c) shouldBe true
    }

    @Test
    fun transferToPartitionedTargetIsAbandoned() {
        val sim = Simulation(8)
        sim.runUntil(5_000) { it.leader() != null }
        sim.runFor(200)
        val old = sim.leader()!!
        val t = sim.nodes().first { it.role == Role.FOLLOWER }.id
        sim.partition(setOf(t))
        sim.transferLeadership(t)
        sim.runFor(1_000)
        sim.leader()!!.id shouldBe old.id
        sim.leader()!!.currentTerm shouldBe old.currentTerm
        sim.trace.any { it is LeadershipTransfer } shouldBe true
        sim.violations shouldBe emptyList()
        val c = sim.leader()!!.commitIndex
        sim.propose("x".encodeToByteArray()) shouldNotBe null
        sim.runFor(200)
        (sim.leader()!!.commitIndex > c) shouldBe true
    }
}
