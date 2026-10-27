package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.core.RaftConfig
import io.raftlab.trace.DropReason
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageDropped
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceEvent
import io.raftlab.trace.VoteCast
import org.junit.jupiter.api.Test

fun Simulation.leader(): NodeState? = nodes().firstOrNull { it.up && it.role == Role.LEADER }

class NodeControlTest {
    @Test
    fun partitionDropsCrossGroupIncludingInFlight() {
        val sim = Simulation(21, SimOptions(faults = NetworkFaults(5, 5)))
        sim.runUntil(5000) { it.leader() != null }
        sim.runFor(50)

        val inFlight = sim.send(inertMsg(n1, n5))
        val within = sim.send(inertMsg(n1, n2))
        sim.partition(setOf(n1, n2), setOf(n3, n4, n5))
        sim.runFor(20)

        val drops = sim.trace.filterIsInstance<MessageDropped>()
        val delivered = sim.trace.filterIsInstance<MessageDelivered>()
        drops.any { it.msgId == inFlight && it.reason == DropReason.PARTITION } shouldBe true
        delivered.any { it.msgId == within } shouldBe true

        val cross = sim.send(inertMsg(n1, n5))
        sim.runFor(20)
        val drops2 = sim.trace.filterIsInstance<MessageDropped>()
        drops2.any { it.msgId == cross && it.reason == DropReason.PARTITION } shouldBe true

        sim.heal()
        val after = sim.send(inertMsg(n1, n5))
        sim.runFor(20)
        val delivered2 = sim.trace.filterIsInstance<MessageDelivered>()
        delivered2.any { it.msgId == after } shouldBe true

        val partitions = sim.trace.filterIsInstance<PartitionChanged>()
        partitions.size shouldBe 2
        partitions[0].groups shouldBe listOf(listOf(n1, n2), listOf(n3, n4, n5))
        partitions[1].groups shouldBe listOf(listOf(n1, n2, n3, n4, n5))
    }

    @Test
    fun isolatedMinorityNeverLeads() {
        val sim = Simulation(22)
        sim.partition(setOf(n1))
        sim.runUntil(3000)

        val roles = sim.trace.filterIsInstance<RoleChanged>()
        roles.any { it.node == n1 && it.to == Role.LEADER } shouldBe false
        sim.node(n1).currentTerm shouldBe 0L
        sim.node(n1).role shouldBe Role.PRE_CANDIDATE
        val others = sim.nodes().filter { it.id != n1 }
        others.any { it.role == Role.LEADER } shouldBe true
        sim.violations.isEmpty() shouldBe true
    }

    @Test
    fun isolatedMinorityRaisesItsTermWithoutPreVote() {
        val sim = Simulation(22, SimOptions(raftConfig = RaftConfig(preVote = false)))
        sim.partition(setOf(n1))
        sim.runUntil(3000)

        val roles = sim.trace.filterIsInstance<RoleChanged>()
        roles.any { it.node == n1 && it.to == Role.LEADER } shouldBe false
        (sim.node(n1).currentTerm >= 5) shouldBe true
        val others = sim.nodes().filter { it.id != n1 }
        others.any { it.role == Role.LEADER } shouldBe true
        sim.violations.isEmpty() shouldBe true
    }

    @Test
    fun crashedFollowerIsSilentAndRestartsWithPersistedState() {
        val sim = Simulation(23)
        sim.runUntil(5000) { it.leader() != null }
        val f = sim.nodes().first { it.up && it.role != Role.LEADER }.id
        val before = sim.node(f)
        val tc = sim.now
        sim.crash(f)
        sim.runFor(500)
        val tr = sim.now

        val window = sim.trace.filter { it.time > tc && it.time <= tr }
        val sentByF = window.filterIsInstance<MessageSent>().filter { it.from == f }
        sentByF.isEmpty() shouldBe true
        val activeOfF = window.filter { isActivityOf(it, f) }
        activeOfF.isEmpty() shouldBe true
        val drops = window.filterIsInstance<MessageDropped>()
        val droppedOfF = drops.filter { it.node == f && it.reason == DropReason.CRASHED }
        droppedOfF.isNotEmpty() shouldBe true

        sim.restart(f)
        val restarted = sim.node(f)
        restarted.up shouldBe true
        restarted.role shouldBe Role.FOLLOWER
        restarted.currentTerm shouldBe before.currentTerm
        restarted.votedFor shouldBe before.votedFor

        sim.runFor(2000)
        sim.violations.isEmpty() shouldBe true
    }

    @Test
    fun leaderCrashElectsNewLeaderAtHigherTerm() {
        val sim = Simulation(24)
        sim.runUntil(5000) { it.leader() != null }
        val l = checkNotNull(sim.leader())
        val term = l.currentTerm
        sim.crash(l.id)

        val elected = sim.runUntil(sim.now + 5000) { s -> isNewLeader(s, l.id) }
        elected shouldBe true
        val newLeader = sim.nodes().first { isNewLeader(it, l.id) }
        (newLeader.currentTerm > term) shouldBe true
        sim.violations.isEmpty() shouldBe true
    }

    @Test
    fun invalidCrashRestartRejected() {
        val sim = Simulation(25)
        sim.crash(n3)
        shouldThrow<IllegalStateException> { sim.crash(n3) }
        shouldThrow<IllegalStateException> { sim.restart(n1) }
        shouldThrow<IllegalArgumentException> { sim.crash(NodeId("n9")) }
        sim.runUntil(10)
        shouldThrow<IllegalArgumentException> { sim.at(-1) {} }
    }

    private fun isActivityOf(
        event: TraceEvent,
        id: NodeId,
    ): Boolean = (event is RoleChanged || event is VoteCast || event is MessageDelivered) && event.node == id

    private fun isNewLeader(
        s: Simulation,
        oldLeader: NodeId,
    ): Boolean = s.nodes().any { isNewLeader(it, oldLeader) }

    private fun isNewLeader(
        state: NodeState,
        oldLeader: NodeId,
    ): Boolean = state.up && state.role == Role.LEADER && state.id != oldLeader
}
