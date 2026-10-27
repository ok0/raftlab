package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.raftlab.core.RaftConfig
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.TraceCodec
import io.raftlab.trace.TraceEvent
import org.junit.jupiter.api.Test

class MembershipSimTest {
    private val four = setOf(n1, n2, n3, n4)

    private fun run(seed: Long): List<TraceEvent> {
        val sim = Simulation(seed, SimOptions(nodeCount = 3))
        sim.runUntil(1000)
        sim.addNode() shouldBe n4
        sim.runUntil(3000)
        sim.nodes().all { it.up && it.members == four } shouldBe true
        val l = sim.leader()!!.id
        sim.removeNode(l)
        sim.runUntil(6000)
        (l !in sim.nodeIds) shouldBe true
        val leaders = sim.nodes().filter { it.up && it.role == Role.LEADER }
        leaders.size shouldBe 1
        leaders.single().id shouldNotBe l
        sim.nodes().all { it.members == four - l } shouldBe true
        sim.trace.any { it is RoleChanged && it.node == l && it.reason == "removed from configuration" } shouldBe true
        sim.trace.filterIsInstance<NodeCrashed>().count { it.node == l } shouldBe 1
        sim.violations shouldBe emptyList()
        return sim.trace
    }

    @Test
    fun addThenRemoveLeaderIsDeterministic() {
        for (seed in listOf(1L, 2L)) {
            TraceCodec.encode(run(seed)) shouldBe TraceCodec.encode(run(seed))
        }
        TraceCodec.encode(run(1L)) shouldNotBe TraceCodec.encode(run(2L))
    }

    @Test
    fun retriesSurviveNoLeaderAndQueueInOrder() {
        val sim = Simulation(7, SimOptions(nodeCount = 3))
        sim.runUntil(1000)
        val crashed = sim.leader()!!.id
        sim.crash(crashed)
        sim.addNode() shouldBe n4
        val live = sim.nodes().filter { it.up && it.role != Role.LEADER }
        val removed = live.minBy { it.id.value }.id
        sim.removeNode(removed)
        sim.at(2000) { it.restart(crashed) }
        sim.runUntil(8000)

        val appended = sim.trace.filterIsInstance<LogAppended>()
        val configs = appended.filter { it.entryKind == EntryKind.CONFIG }
        configs.first().summary.contains(n4.value) shouldBe true
        configs.drop(1).any { !it.summary.contains(removed.value) } shouldBe true
        val expected = four - removed
        sim.nodes().all { it.members == expected } shouldBe true
        sim.violations shouldBe emptyList()
    }

    @Test
    fun removedNodeIsStoppedAndIgnoresControl() {
        val sim = Simulation(4, SimOptions(nodeCount = 3))
        sim.runUntil(1000)
        val f = sim.nodes().first { it.role == Role.FOLLOWER }.id
        sim.removeNode(f)
        sim.runUntil(3000)
        (f !in sim.nodeIds) shouldBe true
        sim.nodes().none { it.id == f } shouldBe true
        sim.node(f).up shouldBe false
        val before = sim.trace.size
        sim.crash(f)
        sim.restart(f)
        sim.partition(setOf(f))
        sim.trace.size shouldBe before
    }

    @Test
    fun joiningNodeInstallsSnapshotWhenBehind() {
        val sim = Simulation(5, SimOptions(nodeCount = 3, raftConfig = RaftConfig(snapshotThreshold = 5)))
        sim.runUntil(1000)
        repeat(20) { i ->
            sim.propose("c$i".encodeToByteArray())
            sim.runFor(10)
        }
        sim.addNode() shouldBe n4
        sim.runUntil(4000)
        sim.trace.any { it is SnapshotInstalled && it.node == n4 } shouldBe true
        sim.node(n4).members shouldBe four
    }

    @Test
    fun limitsAndValidation() {
        val nine = Simulation(6, SimOptions(nodeCount = 9))
        shouldThrow<IllegalStateException> { nine.addNode() }
        val one = Simulation(6, SimOptions(nodeCount = 1))
        shouldThrow<IllegalArgumentException> { one.removeNode(n1) }
        val three = Simulation(6, SimOptions(nodeCount = 3))
        shouldThrow<IllegalArgumentException> { three.removeNode(NodeId("n7")) }
    }

    @Test
    fun membershipCallsDoNotChangeEarlierEvents() {
        val a = Simulation(8, SimOptions(nodeCount = 3))
        a.runUntil(3000)
        val b = Simulation(8, SimOptions(nodeCount = 3))
        b.runUntil(2000)
        b.addNode()
        b.runUntil(3000)
        val earlierA = a.trace.filter { it.time < 2000 }
        val earlierB = b.trace.filter { it.time < 2000 }
        TraceCodec.encode(earlierA) shouldBe TraceCodec.encode(earlierB)
    }
}
