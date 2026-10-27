package io.raftlab.sim

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.core.RequestVote
import io.raftlab.core.SplitMix64
import io.raftlab.trace.DropReason
import io.raftlab.trace.MessageDropped
import io.raftlab.trace.MessageDuplicated
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.TraceRecorder
import org.junit.jupiter.api.Test

class SimNetworkTest {
    private fun net(
        faults: NetworkFaults,
        seed: Long = 1L,
        rec: TraceRecorder = recorder(),
    ): SimNetwork = SimNetwork(faults, SplitMix64(seed), rec, listOf(n1, n2, n3))

    private fun SimNetwork.arrival(now: Long): Long {
        val sent = send(inertMsg(n1, n2), now)
        val delivery = sent.deliveries.single()
        return delivery.at
    }

    @Test
    fun delayWithinBounds() {
        for (reorder in listOf(true, false)) {
            val network = net(NetworkFaults(3, 9, reorder = reorder))
            for (i in 0 until 1000) {
                val now = i / 4L
                val delivery = network.send(inertMsg(n1, n2), now).deliveries.single()
                val gap = delivery.at - now
                (gap >= 3L && gap <= 9L) shouldBe true
            }
        }
    }

    @Test
    fun dropAllAndDropNone() {
        val dropAllRec = recorder()
        val dropAll = net(NetworkFaults(dropRate = 1000), rec = dropAllRec)
        repeat(100) {
            dropAll.send(inertMsg(n1, n2), 0L).deliveries.isEmpty() shouldBe true
        }
        val drops = dropAllRec.events.filterIsInstance<MessageDropped>()
        drops.size shouldBe 100
        drops.all { it.reason == DropReason.RANDOM } shouldBe true

        val dropNoneRec = recorder()
        val dropNone = net(NetworkFaults(dropRate = 0), rec = dropNoneRec)
        repeat(100) {
            dropNone.send(inertMsg(n1, n2), 0L).deliveries.size shouldBe 1
        }
        dropNoneRec.events.filterIsInstance<MessageDropped>().isEmpty() shouldBe true
    }

    @Test
    fun duplicateAll() {
        val rec = recorder()
        val network = net(NetworkFaults(duplicateRate = 1000), rec = rec)
        val sends = List(100) { network.send(inertMsg(n1, n2), 0L) }
        sends.forEach { sent ->
            sent.deliveries.size shouldBe 2
            sent.deliveries[0].msgId shouldBe sent.msgId
            (sent.deliveries[1].msgId != sent.deliveries[0].msgId) shouldBe true
        }
        val duplicated = rec.events.filterIsInstance<MessageDuplicated>()
        duplicated.size shouldBe 100
        val expectedPairs = sends.map { sent -> Pair(sent.msgId, sent.deliveries[1].msgId) }
        val actualPairs = duplicated.map { event -> Pair(event.msgId, event.copyId) }
        actualPairs shouldBe expectedPairs
    }

    @Test
    fun fifoPreservedWithoutReorder() {
        for (seed in 1L..10L) {
            val network = net(NetworkFaults(0, 50, reorder = false), seed = seed)
            val ats = List(500) { i -> network.arrival(i / 3L) }
            ats.zipWithNext().any { (a, b) -> b < a } shouldBe false
        }
    }

    @Test
    fun overtakingWithReorder() {
        val overtook =
            (1L..10L).any { seed ->
                val network = net(NetworkFaults(0, 50, reorder = true), seed = seed)
                val ats = List(500) { i -> network.arrival(i / 3L) }
                ats.zipWithNext().any { (a, b) -> b < a }
            }
        overtook shouldBe true
    }

    @Test
    fun invalidFaultsRejected() {
        shouldThrow<IllegalArgumentException> { NetworkFaults(minDelay = -1) }
        shouldThrow<IllegalArgumentException> { NetworkFaults(minDelay = 5, maxDelay = 4) }
        shouldThrow<IllegalArgumentException> { NetworkFaults(dropRate = 1001) }
        shouldThrow<IllegalArgumentException> { NetworkFaults(dropRate = -1) }
        shouldThrow<IllegalArgumentException> { NetworkFaults(duplicateRate = 1001) }
    }

    @Test
    fun messageSentDescribesMessage() {
        val rec = recorder()
        val network = net(NetworkFaults(), rec = rec)
        network.send(RequestVote(3, n1, n2, 4, 2, false), 0L)
        val sent = rec.events.filterIsInstance<MessageSent>().single()
        sent.kind shouldBe MessageKind.REQUEST_VOTE
        sent.term shouldBe 3L
        sent.summary shouldBe "lastLogIndex=4 lastLogTerm=2 preVote=false"
        sent.msgId shouldBe 1L
    }

    @Test
    fun partitionNormalizesAndHeals() {
        val rec = recorder()
        val network = net(NetworkFaults(), rec = rec)
        network.partition(listOf(setOf(n2, n1))) shouldBe listOf(listOf(n1, n2), listOf(n3))
        network.connected(n1, n2) shouldBe true
        network.connected(n1, n3) shouldBe false
        network.heal()
        network.connected(n1, n3) shouldBe true
        rec.events.filterIsInstance<PartitionChanged>().size shouldBe 2
        shouldThrow<IllegalArgumentException> { network.partition(listOf(setOf(n1), setOf(n1, n2))) }
        shouldThrow<IllegalArgumentException> { network.partition(listOf(setOf(NodeId("x")))) }
    }
}
