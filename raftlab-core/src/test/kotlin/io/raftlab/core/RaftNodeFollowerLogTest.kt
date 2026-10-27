package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.LogAppended
import io.raftlab.trace.LogTruncated
import org.junit.jupiter.api.Test

internal val leaderLog: List<LogEntry> = logWithTerms(1, 1, 1, 4, 4, 5, 5, 6, 6, 6) + noop(11, 7)

internal class RaftNodeFollowerLogTest {
    @Test
    fun conflictingSuffixIsReportedThenReplaced() {
        val rec = recorder()
        val follower =
            node(id = n2, storage = storageWith(3, logWithTerms(1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3)), trace = rec)
        follower.receive(aeWith(7, n1, n2, 10, 6, listOf(noop(11, 7)))) shouldBe
            listOf(Effect.Send(aer(7, n2, n1, false, 0, 7, 3)))
        follower.currentTerm shouldBe 7L
        follower.logSnapshotForChecks().size shouldBe 11

        follower.receive(aeWith(7, n1, n2, 6, 5, leaderLog.subList(6, 11))) shouldBe
            listOf(Effect.Send(aer(7, n2, n1, false, 0, 4, 2)))

        follower.receive(aeWith(7, n1, n2, 3, 1, leaderLog.subList(3, 11))) shouldBe
            listOf(Effect.Send(aer(7, n2, n1, true, 11, 0, 0)))
        follower.logSnapshotForChecks() shouldBe leaderLog
        rec.events.filterIsInstance<LogTruncated>().map { it.fromIndex } shouldBe listOf(4L)
        rec.events.filterIsInstance<LogAppended>().map { it.index } shouldBe (4L..11L).toList()
    }

    @Test
    fun shortLogReportsItsLength() {
        val follower = node(id = n2, storage = storageWith(1, logWithTerms(1, 1)))
        follower.receive(aeWith(2, n1, n2, 5, 1)) shouldBe
            listOf(Effect.Send(aer(2, n2, n1, false, 0, 3, 0)))
    }

    @Test
    fun staleOrDuplicatedAppendEntriesNeverTruncates() {
        val rec = recorder()
        val follower = node(id = n2, storage = storageWith(7, leaderLog), trace = rec)
        val stale = aeWith(7, n1, n2, 3, 1, leaderLog.subList(3, 5))
        follower.receive(stale) shouldBe listOf(Effect.Send(aer(7, n2, n1, true, 5, 0, 0)))
        follower.receive(stale) shouldBe listOf(Effect.Send(aer(7, n2, n1, true, 5, 0, 0)))
        follower.logSnapshotForChecks() shouldBe leaderLog
        rec.events.filterIsInstance<LogTruncated>() shouldBe emptyList()
    }

    @Test
    fun followerCommitFollowsLeaderCommitAndAppliesOnce() {
        val sm = RecordingStateMachine()
        val f = node(id = n2, stateMachine = sm)
        f.receive(aeWith(1, n1, n2, 0, 0, listOf(noop(1, 1), cmd(2, 1, "a"), cmd(3, 1, "b")), 2)) shouldBe
            listOf(
                Effect.Applied(null, 2, "r2".encodeToByteArray()),
                Effect.Send(aer(1, n2, n1, true, 3)),
            )
        f.commitIndex shouldBe 2L
        f.lastApplied shouldBe 2L
        sm.applied shouldBe listOf(2L to "a")
        assertInvariant(f)

        f.receive(aeWith(1, n1, n2, 0, 0, listOf(noop(1, 1), cmd(2, 1, "a"), cmd(3, 1, "b")), 2)) shouldBe
            listOf(Effect.Send(aer(1, n2, n1, true, 3)))
        sm.applied shouldBe listOf(2L to "a")
        assertInvariant(f)

        f.receive(aeWith(1, n1, n2, 3, 1, leaderCommit = 5)) shouldBe
            listOf(
                Effect.Applied(null, 3, "r3".encodeToByteArray()),
                Effect.Send(aer(1, n2, n1, true, 3)),
            )
        f.commitIndex shouldBe 3L
        assertInvariant(f)

        f.receive(aeWith(1, n1, n2, 1, 1, leaderCommit = 1)) shouldBe
            listOf(Effect.Send(aer(1, n2, n1, true, 1)))
        f.receive(aeWith(1, n1, n2, 1, 1, leaderCommit = 3)) shouldBe
            listOf(Effect.Send(aer(1, n2, n1, true, 1)))
        f.commitIndex shouldBe 3L
        assertInvariant(f)
        sm.applied shouldBe listOf(2L to "a", 3L to "b")
    }
}

internal fun assertInvariant(node: RaftNode) {
    (node.lastApplied <= node.commitIndex) shouldBe true
}
