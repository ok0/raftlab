package io.raftlab.sim

import io.kotest.matchers.shouldBe
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageDropped
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.TraceCodec
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SimKernelTest {
    @Test
    fun equalTimesPopInInsertionOrder() {
        val queue = EventQueue<String>()
        queue.push(5, "a")
        queue.push(3, "b")
        queue.push(5, "c")
        queue.push(3, "d")
        val popped = List(4) { queue.pop() }
        popped.map { it.payload } shouldBe listOf("b", "d", "a", "c")
        popped.map { it.time } shouldBe listOf(3L, 3L, 5L, 5L)
        queue.peekTime() shouldBe null
    }

    @Test
    fun tickIntervalHonoredAndRestartResumesOnGrid() {
        lateinit var sim: Simulation
        val log = TickLog { sim.now }
        sim = Simulation(1, SimOptions(nodeCount = 2, tickMillis = 10), InvariantChecker.none(), log.factory())
        sim.at(33) { it.crash(n1) }
        sim.at(51) { it.restart(n1) }
        sim.runUntil(100)
        log.ticks[n1] shouldBe listOf(10L, 20L, 30L, 60L, 70L, 80L, 90L, 100L)
        log.ticks[n2] shouldBe (10L..100L step 10L).toList()
    }

    @Test
    fun runUntilStopsAndResumesIdentically() {
        val a = Simulation(3)
        a.runUntil(1000)

        val b = Simulation(3)
        b.runUntil(250)
        b.now shouldBe 250L
        b.trace.all { it.time <= 250L } shouldBe true
        b.runFor(400)
        b.now shouldBe 650L
        b.runUntil(1000)

        TraceCodec.encode(a.trace) shouldBe TraceCodec.encode(b.trace)
        assertThrows(IllegalArgumentException::class.java) { a.runUntil(100) }
    }

    @Test
    fun runStepsProcessesExactCount() {
        Simulation(3).runSteps(7) shouldBe 7
    }

    @Test
    fun timeNeverMovesBackwardAndSeqStrictlyIncreases() {
        val sim = Simulation(5)
        sim.runUntil(2000)
        val events = sim.trace
        events.forEachIndexed { i, e -> e.seq shouldBe i.toLong() }
        events.zipWithNext().all { (x, y) -> x.time <= y.time } shouldBe true
    }

    @Test
    fun scriptedRunHasExpectedEvents() {
        val sim = Simulation(11, SimOptions(nodeCount = 3))
        sim.at(150) { it.crash(n2) }
        sim.at(400) { it.restart(n2) }
        sim.at(500) { it.partition(setOf(n1)) }
        sim.at(700) { it.heal() }
        sim.runUntil(1000)
        val trace = sim.trace

        val started = trace.take(3)
        started.map { it.node } shouldBe listOf(n1, n2, n3)
        started.all { it is NodeStarted && it.time == 0L } shouldBe true

        val crashes = trace.filterIsInstance<NodeCrashed>()
        crashes.size shouldBe 1
        crashes[0].node shouldBe n2
        crashes[0].time shouldBe 150L

        val restarts = trace.filterIsInstance<NodeRestarted>()
        restarts.size shouldBe 1
        restarts[0].node shouldBe n2
        restarts[0].time shouldBe 400L

        val partitions = trace.filterIsInstance<PartitionChanged>()
        partitions.size shouldBe 2
        partitions[0].time shouldBe 500L
        partitions[0].groups shouldBe listOf(listOf(n1), listOf(n2, n3))
        partitions[1].time shouldBe 700L
        partitions[1].groups shouldBe listOf(listOf(n1, n2, n3))

        val sends = trace.filterIsInstance<MessageSent>().filter { it.time + 5 <= 1000 }
        for (s in sends) {
            val outcomes =
                trace.filter { e ->
                    (e is MessageDelivered && e.msgId == s.msgId) || (e is MessageDropped && e.msgId == s.msgId)
                }
            outcomes.size shouldBe 1
            (outcomes[0].seq > s.seq) shouldBe true
        }

        TraceCodec.decode(TraceCodec.encode(trace)) shouldBe trace
    }
}
