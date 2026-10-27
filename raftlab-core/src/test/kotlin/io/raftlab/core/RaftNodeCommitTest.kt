package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

internal class RaftNodeCommitTest {
    @Test
    fun figure8OldTermEntryCommitsOnlyWithCurrentTermEntry() {
        val rec = recorder()
        val leader = node(members = five, storage = storageWith(3, listOf(cmd(1, 1, "a"), cmd(2, 2, "b"))), trace = rec)
        leader.winElection(n2, n3)
        leader.receive(aer(4, n2, success = true, matchIndex = 2))
        leader.receive(aer(4, n3, success = true, matchIndex = 2))
        leader.commitIndex shouldBe 0L
        rec.events.filterIsInstance<CommitAdvanced>() shouldBe emptyList()
        leader.receive(aer(4, n2, success = true, matchIndex = 3))
        leader.commitIndex shouldBe 0L
        leader.receive(aer(4, n3, success = true, matchIndex = 3))
        leader.commitIndex shouldBe 3L
        leader.lastApplied shouldBe 3L
        rec.events.filterIsInstance<CommitAdvanced>().map { it.commitIndex } shouldBe listOf(3L)
    }

    @Test
    fun singleMemberCommitsOnAppend() {
        val sm = RecordingStateMachine()
        val solo = node(members = setOf(n1), stateMachine = sm)
        solo.tick(10) shouldBe emptyList<Effect>()
        solo.commitIndex shouldBe 1L
        solo.lastApplied shouldBe 1L
        solo.propose(5, "x") shouldBe listOf(Effect.Applied(5, 2, "r2".encodeToByteArray()))
        sm.applied shouldBe listOf(2L to "x")
    }

    @Test
    fun leaderReportsRequestIdOnlyForOwnProposals() {
        val sm = RecordingStateMachine()
        val leader = node(storage = storageWith(1, listOf(cmd(1, 1, "old"))), stateMachine = sm)
        leader.winElection(n2)
        leader.propose(9, "new")
        leader.receive(aer(2, n2, success = true, matchIndex = 3)) shouldBe
            listOf(
                Effect.Applied(null, 1, "r1".encodeToByteArray()),
                Effect.Applied(9, 3, "r3".encodeToByteArray()),
            )
        sm.applied shouldBe listOf(1L to "old", 3L to "new")
    }

    @Test
    fun deposedLeaderRejectsPendingAndNeverReportsThemApplied() {
        val sm = RecordingStateMachine()
        val l = node(stateMachine = sm)
        val effects = mutableListOf<Effect>()
        l.winElection(n2)
        effects += l.propose(1, "a")
        effects += l.propose(2, "b")
        val stepDown = l.receive(ae(2, n2))
        effects += stepDown
        stepDown shouldBe
            listOf(
                Effect.Rejected(1, Rejection.Unknown),
                Effect.Rejected(2, Rejection.Unknown),
                Effect.Send(aer(2, n1, n2, true, 0)),
            )
        l.role shouldBe Role.FOLLOWER
        val after = l.receive(aeWith(2, n2, n1, 1, 1, listOf(cmd(2, 2, "z")), 2))
        effects += after
        after shouldBe
            listOf(
                Effect.Applied(null, 2, "r2".encodeToByteArray()),
                Effect.Send(aer(2, n1, n2, true, 2)),
            )
        l.logSnapshotForChecks() shouldBe listOf(noop(1, 1), cmd(2, 2, "z"))
        sm.applied shouldBe listOf(2L to "z")
        effects.filterIsInstance<Effect.Applied>().none { it.requestId == 1L || it.requestId == 2L } shouldBe true
    }

    @Test
    fun restartRestoresLogAndReappliesFromScratch() {
        val storage = InMemoryStorage()
        val sm1 = RecordingStateMachine()
        val l = node(storage = storage, stateMachine = sm1)
        l.winElection(n2)
        l.propose(1, "a")
        l.receive(aer(1, n2, success = true, matchIndex = 2))
        sm1.applied shouldBe listOf(2L to "a")

        val sm2 = RecordingStateMachine()
        val r = node(storage = storage, stateMachine = sm2)
        r.role shouldBe Role.FOLLOWER
        r.currentTerm shouldBe 1L
        r.commitIndex shouldBe 0L
        r.lastApplied shouldBe 0L
        r.logSnapshotForChecks() shouldBe listOf(noop(1, 1), cmd(2, 1, "a"))
        r.receive(rv(1, n3, lastIndex = 2, lastTerm = 1)) shouldBe listOf(Effect.Send(rvr(1, n1, n3, false)))
        r.receive(aeWith(2, n2, n1, 2, 1, leaderCommit = 2)) shouldBe
            listOf(
                Effect.Applied(null, 2, "r2".encodeToByteArray()),
                Effect.Send(aer(2, n1, n2, true, 2)),
            )
        sm2.applied shouldBe listOf(2L to "a")
        r.receive(rv(3, n3, lastIndex = 1, lastTerm = 1)) shouldBe listOf(Effect.Send(rvr(3, n1, n3, false)))
        r.receive(rv(3, n3, lastIndex = 5, lastTerm = 0)) shouldBe listOf(Effect.Send(rvr(3, n1, n3, false)))
        r.receive(rv(3, n3, lastIndex = 2, lastTerm = 1)) shouldBe listOf(Effect.Send(rvr(3, n1, n3, true)))
    }
}
