package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.TraceRecorder
import org.junit.jupiter.api.Test

private val e4 = cmd(4, 2, "e4")
private val e5 = cmd(5, 2, "e5")
private val e6 = cmd(6, 3, "e6")
private val e7 = cmd(7, 3, "e7")
private val e8 = cmd(8, 3, "e8")

internal class RaftNodeCompactedLogTest {
    private fun follower(rec: TraceRecorder = recorder()): RaftNode =
        node(storage = compactedStorage(5, 1, 1, 2, 2, 2, 3, 3, currentTerm = 3), trace = rec)

    @Test
    fun restoredFollowerStartsAtSnapshot() {
        val f = follower()
        f.commitIndex shouldBe 5
        f.lastApplied shouldBe 5
        f.logSnapshotForChecks() shouldBe listOf(e6, e7)
    }

    @Test
    fun consistencyCheckAtSnapshotIndexUsesSnapshotTerm() {
        val ok = follower()
        ok.receive(aeWith(3, n2, prevIndex = 5, prevTerm = 2, entries = listOf(e6, e7, e8))) shouldBe
            listOf(Effect.Send(aer(3, n1, n2, true, 8)))
        ok.logSnapshotForChecks().map { it.index } shouldBe listOf(6L, 7L, 8L)

        val bad = follower()
        bad.receive(aeWith(3, n2, prevIndex = 5, prevTerm = 1, entries = listOf(e6, e7, e8))) shouldBe
            listOf(Effect.Send(aer(3, n1, n2, false, 0, 5, 2)))
        bad.logSnapshotForChecks() shouldBe listOf(e6, e7)
    }

    @Test
    fun prevBelowSnapshotSkipsCoveredEntries() {
        val rec = recorder()
        val f = follower(rec)
        val entries = listOf(e4, e5, e6, e7, e8)
        f.receive(aeWith(3, n2, prevIndex = 3, prevTerm = 2, entries = entries, leaderCommit = 8)).sent() shouldBe
            listOf(aer(3, n1, n2, true, 8))
        rec.events.filterIsInstance<LogTruncated>() shouldBe emptyList()
        f.logSnapshotForChecks().map { it.index } shouldBe listOf(6L, 7L, 8L)
        f.commitIndex shouldBe 8
        f.lastApplied shouldBe 8
    }

    @Test
    fun prevBelowSnapshotWithOnlyCoveredEntries() {
        val rec = recorder()
        val f = follower(rec)
        f.receive(aeWith(3, n2, prevIndex = 2, prevTerm = 1, entries = listOf(cmd(3, 2, "e3"), e4))) shouldBe
            listOf(Effect.Send(aer(3, n1, n2, true, 5)))
        f.logSnapshotForChecks() shouldBe listOf(e6, e7)
        rec.events.filterIsInstance<LogTruncated>() shouldBe emptyList()
    }

    @Test
    fun prevAboveSnapshotAndBeyondLog() {
        val extended = follower()
        extended.receive(aeWith(3, n2, prevIndex = 7, prevTerm = 3, entries = listOf(e8))) shouldBe
            listOf(Effect.Send(aer(3, n1, n2, true, 8)))

        val fresh = follower()
        fresh.receive(aeWith(3, n2, prevIndex = 9, prevTerm = 3)) shouldBe
            listOf(Effect.Send(aer(3, n1, n2, false, 0, 8, 0)))
    }

    @Test
    fun staleDuplicateDoesNotTruncateOrShrink() {
        val rec = recorder()
        val f = follower(rec)
        f.receive(aeWith(3, n2, prevIndex = 7, prevTerm = 3, entries = listOf(e8)))
        val commitBefore = f.commitIndex
        f.receive(aeWith(3, n2, prevIndex = 5, prevTerm = 2, entries = listOf(e6, e7))) shouldBe
            listOf(Effect.Send(aer(3, n1, n2, true, 7)))
        f.logSnapshotForChecks().size shouldBe 3
        rec.events.filterIsInstance<LogTruncated>() shouldBe emptyList()
        f.commitIndex shouldBe commitBefore
    }

    @Test
    fun conflictAboveSnapshotTruncatesOnlyAboveIt() {
        val rec = recorder()
        val f = follower(rec)
        f.receive(aeWith(4, n2, prevIndex = 5, prevTerm = 2, entries = listOf(cmd(6, 4, "z"))))
        f.logSnapshotForChecks() shouldBe listOf(cmd(6, 4, "z"))
        f.snapshotIndex shouldBe 5
        rec.events.filterIsInstance<LogTruncated>().map { it.fromIndex } shouldBe listOf(6L)
    }

    @Test
    fun upToDateCheckOnFullyCompactedLog() {
        fun fresh() = node(storage = compactedStorage(5, 1, 1, 2, 2, 2, currentTerm = 2))

        fresh().logSnapshotForChecks() shouldBe emptyList()
        fresh().receive(rv(3, n2, lastIndex = 4, lastTerm = 2)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(3, n1, n2, false, false)))
        fresh().receive(rv(3, n2, lastIndex = 5, lastTerm = 2)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(3, n1, n2, true, false)))
        fresh().receive(rv(3, n2, lastIndex = 1, lastTerm = 3)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(3, n1, n2, true, false)))
    }
}
