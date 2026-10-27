package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.raftlab.core.RaftConfig
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import org.junit.jupiter.api.Test

internal class PreVoteSimTest {
    companion object {
        const val SEED = 31L
    }

    @Test
    fun isolatedFollowerDoesNotRaiseTermsWithPreVote() {
        val sim = Simulation(SEED)
        sim.runUntil(5_000) { it.leader() != null }
        sim.runFor(200)
        val l = sim.leader()!!
        val term = l.currentTerm
        val victim = sim.nodeIds.first { it != l.id }
        sim.partition(setOf(victim))
        repeat(200) {
            sim.runFor(10)
            sim.node(victim).currentTerm shouldBe term
        }
        sim.heal()
        sim.runFor(1_000)
        sim.leader()!!.id shouldBe l.id
        sim.nodes().all { it.currentTerm == term } shouldBe true
        sim.node(victim).role shouldBe Role.FOLLOWER
        sim.trace.any { it is RoleChanged && it.node == victim && it.to == Role.PRE_CANDIDATE } shouldBe true
        sim.violations shouldBe emptyList()
    }

    @Test
    fun isolatedFollowerRaisesClusterTermWithoutPreVote() {
        val sim = Simulation(SEED, SimOptions(raftConfig = RaftConfig(preVote = false)))
        sim.runUntil(5_000) { it.leader() != null }
        sim.runFor(200)
        val l = sim.leader()!!
        val term = l.currentTerm
        val victim = sim.nodeIds.first { it != l.id }
        sim.partition(setOf(victim))
        sim.runFor(2_000)
        sim.heal()
        sim.runFor(1_000)
        (sim.leader()!!.currentTerm > term) shouldBe true
        sim.violations shouldBe emptyList()
    }

    @Test
    fun dslKnobRunsPreVoteOff() {
        val result =
            scenario("pre-vote-off") {
                nodes(5)
                preVote(false)
                at(500) { partition(setOf(NodeId("n1"))) }
                at(2_500) { heal() }
                runFor(4_000)
            }.run(SEED)
        result.passed shouldBe true
        result.trace.filterIsInstance<MessageSent>().any { it.summary.contains("preVote=true") } shouldBe false
    }
}
