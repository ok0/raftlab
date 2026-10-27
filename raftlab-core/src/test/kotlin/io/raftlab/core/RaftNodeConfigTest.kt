package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.ConfigChanged
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.SnapshotInstalled
import org.junit.jupiter.api.Test

internal class RaftNodeConfigTest {
    private val four = setOf(n1, n2, n3, n4)

    @Test
    fun followerTakesConfigOnAppendAndRevertsOnTruncate() {
        val rec = recorder()
        val f = node(trace = rec)
        f.receive(aeWith(1, n2, entries = listOf(noop(1, 1), LogEntry(2, 1, EntryPayload.Config(four)))))
        f.members shouldBe four
        val appendedAt = rec.events.indexOfFirst { it is LogAppended && it.entryKind == EntryKind.CONFIG }
        (rec.events[appendedAt] as LogAppended).summary shouldBe "config members=n1,n2,n3,n4"
        (rec.events[appendedAt + 1] as ConfigChanged).members shouldBe listOf(n1, n2, n3, n4)

        f.receive(aeWith(2, n3, prevIndex = 1, prevTerm = 1, entries = listOf(noop(2, 2))))
        f.members shouldBe three
        val truncatedAt = rec.events.indexOfFirst { it is LogTruncated }
        (rec.events[truncatedAt + 1] as ConfigChanged).members shouldBe listOf(n1, n2, n3)
        rec.events.count { it is ConfigChanged } shouldBe 2
    }

    @Test
    fun noConfigChangedAtConstructionOrForUnchangedSet() {
        val rec = recorder()
        val f = node(trace = rec)
        rec.events.none { it is ConfigChanged } shouldBe true
        f.receive(aeWith(1, n2, entries = listOf(LogEntry(1, 1, EntryPayload.Config(three)))))
        rec.events.any { it is LogAppended && it.entryKind == EntryKind.CONFIG } shouldBe true
        rec.events.none { it is ConfigChanged } shouldBe true
    }

    @Test
    fun restartRestoresMembersFromLogSnapshotOrBoth() {
        val rec = recorder()
        val fromLog =
            node(
                members = three,
                storage = storageWith(1, listOf(noop(1, 1), LogEntry(2, 1, EntryPayload.Config(setOf(n1, n2))))),
                trace = rec,
            )
        fromLog.members shouldBe setOf(n1, n2)
        rec.events.filterIsInstance<ConfigChanged>().map { it.members } shouldBe listOf(listOf(n1, n2))

        node(storage = compactedStorage(3, 1, 1, 1, members = setOf(n1, n2))).members shouldBe setOf(n1, n2)

        val both = compactedStorage(3, 1, 1, 1, members = three)
        both.append(listOf(LogEntry(4, 1, EntryPayload.Config(setOf(n1, n2, n4)))))
        node(storage = both).members shouldBe setOf(n1, n2, n4)
    }

    @Test
    fun snapshotWhileLaterConfigUncommittedRecordsEarlierMembers() {
        val storage = InMemoryStorage()
        val leader =
            node(config = RaftConfig(snapshotThreshold = 2), storage = storage, stateMachine = SnapshotStateMachine())
        leader.winElection(n2)
        leader.receive(aer(1, n2, matchIndex = 1))
        leader.propose(2, "a")
        leader.propose(3, "b")
        leader.step(Input.ChangeMembership(1, null, n3))
        leader.receive(aer(1, n2, matchIndex = 3))
        leader.snapshotIndex shouldBe 3L
        storage.loadSnapshot()!!.meta.members shouldBe three
        leader.members shouldBe setOf(n1, n2)
    }

    @Test
    fun installSnapshotSetsMembersOnceAndTraces() {
        val rec = recorder()
        val f = node(trace = rec)
        f.receive(isnap(1, n2, lastIndex = 3, lastTerm = 1, members = setOf(n1, n2, n4)))
        f.members shouldBe setOf(n1, n2, n4)
        rec.events.count { it is ConfigChanged } shouldBe 1
        val configAt = rec.events.indexOfFirst { it is ConfigChanged }
        val installedAt = rec.events.indexOfFirst { it is SnapshotInstalled }
        (configAt < installedAt) shouldBe true
    }

    @Test
    fun installSnapshotKeepsLaterConfigFromRetainedLog() {
        val storage = storageWith(1, logWithTerms(1, 1, 1) + LogEntry(4, 1, EntryPayload.Config(setOf(n1, n2))))
        val rec = recorder()
        val f = node(storage = storage, trace = rec)
        f.members shouldBe setOf(n1, n2)
        val before = rec.events.size
        f.receive(isnap(1, n2, lastIndex = 3, lastTerm = 1, members = three))
        f.members shouldBe setOf(n1, n2)
        rec.events.drop(before).none { it is ConfigChanged } shouldBe true
    }
}
