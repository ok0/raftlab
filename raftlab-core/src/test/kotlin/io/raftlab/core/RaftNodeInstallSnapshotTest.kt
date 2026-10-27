package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.SnapshotInstalled
import io.raftlab.trace.TraceRecorder
import org.junit.jupiter.api.Test

internal class RaftNodeInstallSnapshotTest {
    private fun leader(): RaftNode {
        val l =
            node(
                storage = compactedStorage(5, 1, 1, 2, 2, 2, 3, 3, currentTerm = 3, data = "d".encodeToByteArray()),
            )
        l.campaign()
        l.receive(rvr(4, n2))
        return l
    }

    private fun List<Effect>.sentTo(to: NodeId): List<RaftMessage> = sent().filter { it.to == to }

    private fun installWholeLog(
        rec: TraceRecorder,
        sm: SnapshotStateMachine,
        s: InMemoryStorage,
    ): RaftNode {
        val f = node(stateMachine = sm, storage = s, trace = rec)
        f.receive(isnap(2, n2, lastIndex = 5, lastTerm = 2, data = "2,3,4".encodeToByteArray()))
        return f
    }

    @Test
    fun backtrackingBelowSnapshotSendsInstallSnapshot() {
        val l = leader()
        l.receive(aer(4, n3, success = false, conflictIndex = 2, conflictTerm = 1)).sent() shouldBe
            listOf(isnap(4, n1, n3, lastIndex = 5, lastTerm = 2, data = "d".encodeToByteArray()))
    }

    @Test
    fun installIsResentAtMostOncePerHeartbeat() {
        val l = leader()
        l.receive(aer(4, n3, success = false, conflictIndex = 2, conflictTerm = 1))
        l.propose(1, "p").sentTo(n3) shouldBe emptyList()
        l.receive(aer(4, n3, success = false, conflictIndex = 2, conflictTerm = 1)).sentTo(n3) shouldBe emptyList()
        l.tick(2).sentTo(n3) shouldBe emptyList()
        l.tick(1).sentTo(n3) shouldBe
            listOf(isnap(4, n1, n3, lastIndex = 5, lastTerm = 2, data = "d".encodeToByteArray()))
        l.tick(9).sentTo(n3).size shouldBe 3
    }

    @Test
    fun responseAdvancesMatchAndNext() {
        val l = leader()
        l.receive(aer(4, n3, success = false, conflictIndex = 2, conflictTerm = 1))
        val effects = l.receive(isr(4, n3, n1, 5))
        l.matchIndexOf(n3) shouldBe 5L
        l.nextIndexOf(n3) shouldBe 6L
        val append = effects.sent().filterIsInstance<AppendEntries>().single { it.to == n3 }
        append.prevLogIndex shouldBe 5L
        append.prevLogTerm shouldBe 2L
    }

    @Test
    fun staleForeignLowerTermAndNonLeaderResponsesIgnored() {
        val l = leader()
        l.receive(aer(4, n3, success = false, conflictIndex = 2, conflictTerm = 1))
        l.receive(isr(4, n3, n1, 5))
        l.receive(isr(4, n3, n1, 3)) shouldBe emptyList()
        l.receive(isr(4, n4, n1, 9)) shouldBe emptyList()
        l.receive(isr(3, n3, n1, 9)) shouldBe emptyList()
        l.matchIndexOf(n3) shouldBe 5L
        node().receive(isr(1, n2, n1, 4)) shouldBe emptyList()
    }

    @Test
    fun higherTermResponseForcesFollower() {
        val l = leader()
        l.receive(isr(9, n3, n1, 5))
        l.role shouldBe Role.FOLLOWER
        l.currentTerm shouldBe 9L
    }

    @Test
    fun installDiscardsWholeLogWhenNoMatch() {
        val rec = recorder()
        val sm = SnapshotStateMachine()
        val s = storageWith(1, logWithTerms(1, 1, 1))
        val f = node(stateMachine = sm, storage = s, trace = rec)
        f.receive(isnap(2, n2, lastIndex = 5, lastTerm = 2, data = "2,3,4".encodeToByteArray())) shouldBe
            listOf(Effect.Send(isr(2, n1, n2, 5)))
        rec.events.filter { it is LogTruncated || it is SnapshotInstalled }.map { it::class } shouldBe
            listOf(LogTruncated::class, SnapshotInstalled::class)
        rec.events.filterIsInstance<LogTruncated>().map { it.fromIndex } shouldBe listOf(1L)
        rec.events.filterIsInstance<SnapshotInstalled>().map { it.lastIndex to it.lastTerm } shouldBe
            listOf(5L to 2L)
        s.loadSnapshot() shouldBe Snapshot(SnapshotMeta(5, 2, three), "2,3,4".encodeToByteArray())
        s.lastIndex() shouldBe 5L
        f.logSnapshotForChecks() shouldBe emptyList()
        f.commitIndex shouldBe 5L
        f.lastApplied shouldBe 5L
        f.snapshotIndex shouldBe 5L
        sm.applied shouldBe listOf(2L, 3L, 4L)
        f.leaderHint shouldBe n2
        f.currentTerm shouldBe 2L
    }

    @Test
    fun installKeepsSuffixWhenLogMatches() {
        val rec = recorder()
        val s = storageWith(3, logWithTerms(1, 1, 2, 2, 2, 3, 3))
        val f = node(storage = s, trace = rec)
        f.receive(isnap(3, n2, lastIndex = 5, lastTerm = 2))
        rec.events.filterIsInstance<LogTruncated>() shouldBe emptyList()
        f.logSnapshotForChecks().map { it.index } shouldBe listOf(6L, 7L)
        s.lastIndex() shouldBe 7L
    }

    @Test
    fun installDiscardsWhenTermAtLastIndexDiffers() {
        val rec = recorder()
        val s = storageWith(1, logWithTerms(1, 1, 1, 1, 1, 1))
        val f = node(storage = s, trace = rec)
        f.receive(isnap(3, n2, lastIndex = 5, lastTerm = 2))
        rec.events.filterIsInstance<LogTruncated>().map { it.fromIndex } shouldBe listOf(1L)
        s.lastIndex() shouldBe 5L
    }

    @Test
    fun staleOrDuplicateInstallIsNoOp() {
        val rec = recorder()
        val sm = SnapshotStateMachine()
        val s = InMemoryStorage()
        val f = installWholeLog(rec, sm, s)
        val eventsBefore = rec.events.size
        val snapshotBefore = s.loadSnapshot()
        f.receive(isnap(2, n2, lastIndex = 5, lastTerm = 2)) shouldBe listOf(Effect.Send(isr(2, n1, n2, 5)))
        f.receive(isnap(2, n2, lastIndex = 4, lastTerm = 2)) shouldBe listOf(Effect.Send(isr(2, n1, n2, 5)))
        rec.events.size shouldBe eventsBefore
        s.loadSnapshot() shouldBe snapshotBefore
        f.commitIndex shouldBe 5L
        f.lastApplied shouldBe 5L
    }

    @Test
    fun lowerTermInstallRejected() {
        val s = storageWith(3, logWithTerms(1, 1, 1))
        val f = node(storage = s)
        f.receive(isnap(2, n2, lastIndex = 5, lastTerm = 2)) shouldBe listOf(Effect.Send(isr(3, n1, n2, 0)))
        f.snapshotIndex shouldBe 0L
        s.loadSnapshot() shouldBe null
    }

    @Test
    fun candidateSameTermBecomesFollower() {
        val rec = recorder()
        val c = node(storage = InMemoryStorage(), trace = rec)
        c.campaign()
        c.role shouldBe Role.CANDIDATE
        c.receive(isnap(1, n2, lastIndex = 5, lastTerm = 2)) shouldBe listOf(Effect.Send(isr(1, n1, n2, 5)))
        val change = rec.events.filterIsInstance<RoleChanged>().last()
        change.from shouldBe Role.CANDIDATE
        change.to shouldBe Role.FOLLOWER
        change.reason shouldBe "leader discovered"
        rec.events.filterIsInstance<SnapshotInstalled>().size shouldBe 1
        c.snapshotIndex shouldBe 5L
    }

    @Test
    fun higherTermInstallStepsDownLeaderAndRejectsPending() {
        val rec = recorder()
        val l = leaderAtTerm1(trace = rec)
        l.propose(7, "x")
        val effects = l.receive(isnap(2, n2, lastIndex = 5, lastTerm = 2))
        effects.filterIsInstance<Effect.Rejected>() shouldBe listOf(Effect.Rejected(7, Rejection.Unknown))
        l.role shouldBe Role.FOLLOWER
        rec.events.filterIsInstance<SnapshotInstalled>().size shouldBe 1
    }
}
