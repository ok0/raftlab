package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.SnapshotTaken
import org.junit.jupiter.api.Test

internal class RaftNodeSnapshotTest {
    @Test
    fun noSnapshotAtThresholdSnapshotAboveIt() {
        val rec = recorder()
        val storage = RecordingStorage()
        val node =
            node(
                members = setOf(n1),
                config = RaftConfig(snapshotThreshold = 3),
                storage = storage,
                stateMachine = SnapshotStateMachine(),
                trace = rec,
            )
        node.tick(10)
        node.propose(1, "a")
        node.propose(2, "b")
        rec.events.filterIsInstance<SnapshotTaken>() shouldBe emptyList()
        storage.loadSnapshot() shouldBe null

        node.propose(3, "c")
        val taken = rec.events.filterIsInstance<SnapshotTaken>()
        taken.size shouldBe 1
        taken.single().lastIndex shouldBe 4
        taken.single().lastTerm shouldBe 1
        taken.single().node shouldBe n1
        node.snapshotIndex shouldBe 4
        storage.loadSnapshot()!!.meta shouldBe SnapshotMeta(4, 1, setOf(n1))
        storage.loadSnapshot()!!.data shouldBe "2,3,4".encodeToByteArray()
        storage.lastIndex() shouldBe 4
        storage.termAt(3) shouldBe null
        node.logSnapshotForChecks() shouldBe emptyList()
        storage.ops shouldBe listOf("saveSnapshot(4)", "compactTo(4)")
    }

    @Test
    fun nextSnapshotAfterAnotherThresholdAndRetainedLogStartsAfterIt() {
        val rec = recorder()
        val storage = RecordingStorage()
        val node =
            node(
                members = setOf(n1),
                config = RaftConfig(snapshotThreshold = 3),
                storage = storage,
                stateMachine = SnapshotStateMachine(),
                trace = rec,
            )
        node.tick(10)
        node.propose(1, "a")
        node.propose(2, "b")
        node.propose(3, "c")
        node.propose(4, "d")
        node.propose(5, "e")
        node.propose(6, "f")
        rec.events.filterIsInstance<SnapshotTaken>().map { it.lastIndex } shouldBe listOf(4L)
        node.propose(7, "g")
        rec.events.filterIsInstance<SnapshotTaken>().map { it.lastIndex } shouldBe listOf(4L, 8L)
        node.propose(8, "h")
        node.logSnapshotForChecks().map { it.index } shouldBe listOf(9L)
    }

    @Test
    fun uncommittedEntriesAreNeverSnapshotted() {
        val rec = recorder()
        val storage = RecordingStorage()
        val leader =
            node(
                config = RaftConfig(snapshotThreshold = 1),
                storage = storage,
                stateMachine = SnapshotStateMachine(),
                trace = rec,
            )
        leader.winElection(n2)
        for (i in 1..5) {
            leader.propose(i.toLong(), "x$i")
        }
        rec.events.filterIsInstance<SnapshotTaken>() shouldBe emptyList()
        storage.loadSnapshot() shouldBe null
    }

    @Test
    fun restartRestoresFromSnapshotAndReplaysOnlyTail() {
        val s = InMemoryStorage()
        val leader =
            node(config = RaftConfig(snapshotThreshold = 2), storage = s, stateMachine = SnapshotStateMachine())
        leader.tick(10)
        leader.receive(rvr(1, n2))
        leader.propose(1, "a")
        leader.propose(2, "b")
        leader.propose(3, "c")
        leader.receive(aer(1, n2, matchIndex = 4))
        s.loadSnapshot()!!.data shouldBe "2,3,4".encodeToByteArray()
        leader.propose(4, "d")

        val sm = SnapshotStateMachine()
        val restarted = node(config = RaftConfig(snapshotThreshold = 2), storage = s, stateMachine = sm)
        sm.restores shouldBe listOf("2,3,4")
        restarted.commitIndex shouldBe 4
        restarted.lastApplied shouldBe 4
        restarted.snapshotIndex shouldBe 4
        restarted.members shouldBe three
        restarted.logSnapshotForChecks().map { it.index } shouldBe listOf(5L)

        restarted.receive(aeWith(2, n2, prevIndex = 5, prevTerm = 1, leaderCommit = 5))
        sm.applied shouldBe listOf(2L, 3L, 4L, 5L)
    }

    @Test
    fun restartTakesMembersFromSnapshotOrLaterConfig() {
        val fromSnapshot = compactedStorage(3, 1, 1, 1, members = three)
        node(members = five, storage = fromSnapshot).members shouldBe three

        val withConfig = compactedStorage(3, 1, 1, 1, members = three)
        withConfig.append(listOf(LogEntry(4, 1, EntryPayload.Config(setOf(n1, n2)))))
        node(members = five, storage = withConfig).members shouldBe setOf(n1, n2)
    }

    @Test
    fun defaultThresholdTakesNoSnapshot() {
        val rec = recorder()
        val storage = InMemoryStorage()
        val node = node(members = setOf(n1), storage = storage, trace = rec)
        node.tick(10)
        for (i in 1..50) {
            node.propose(i.toLong(), "p$i")
        }
        rec.events.filterIsInstance<SnapshotTaken>() shouldBe emptyList()
        storage.loadSnapshot() shouldBe null
    }
}
