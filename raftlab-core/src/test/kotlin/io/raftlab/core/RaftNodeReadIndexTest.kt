package io.raftlab.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.EntryApplied
import io.raftlab.trace.LogAppended
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.TraceSink
import org.junit.jupiter.api.Test

internal class RaftNodeReadIndexTest {
    private fun committedLeader(
        storage: RaftStorage = InMemoryStorage(),
        trace: TraceSink = TraceSink { },
    ): RaftNode {
        val leader = leaderAtTerm1(storage, trace)
        leader.receive(aer(1, n2, matchIndex = 1))
        check(leader.commitIndex == 1L)
        return leader
    }

    private fun ack(
        from: NodeId,
        readSeq: Long,
        term: Long = 1,
        matchIndex: Long = 0,
    ) = AppendEntriesResponse(term, from, n1, true, matchIndex, 0, 0, readSeq)

    private fun List<Effect>.readies() = filterIsInstance<Effect.ReadReady>()

    @Test
    fun nonLeaderRejectsReadWithHint() {
        val follower = node()
        follower.step(Input.ReadIndex(7)) shouldContainExactly listOf(Effect.Rejected(7, Rejection.NotLeader(null)))
        follower.receive(ae(1, n2))
        follower.step(Input.ReadIndex(8)) shouldContainExactly listOf(Effect.Rejected(8, Rejection.NotLeader(n2)))
    }

    @Test
    fun freshLeaderWithoutCurrentTermCommitIsBusy() {
        val storage = RecordingStorage()
        val rec = recorder()
        val leader = leaderAtTerm1(storage, rec)
        val log = leader.logSnapshotForChecks()
        val commit = leader.commitIndex
        val term = leader.currentTerm
        val events = rec.events.size
        val saves = storage.saves.size
        leader.step(Input.ReadIndex(7)) shouldContainExactly listOf(Effect.Rejected(7, Rejection.Busy))
        leader.logSnapshotForChecks() shouldBe log
        leader.commitIndex shouldBe commit
        leader.currentTerm shouldBe term
        rec.events.size shouldBe events
        storage.saves.size shouldBe saves
    }

    @Test
    fun readConfirmsOnlyAfterMajorityAcksRoundSentAfterRead() {
        val leader = committedLeader()
        val e = leader.step(Input.ReadIndex(7))
        e.readies().shouldBeEmpty()
        val sends = e.sent().filterIsInstance<AppendEntries>()
        sends.map { it.to } shouldContainExactly listOf(n2, n3)
        sends.forEach { it.readSeq shouldBe 1L }
        leader.receive(ack(n2, 0)).readies().shouldBeEmpty()
        leader.receive(ack(n3, 0)).readies().shouldBeEmpty()
        leader.receive(ack(n2, 1)).readies() shouldContainExactly listOf(Effect.ReadReady(7, 1))
        leader.receive(ack(n3, 1)).readies().shouldBeEmpty()
    }

    @Test
    fun laterReadNeedsItsOwnRound() {
        val leader = committedLeader()
        leader.step(Input.ReadIndex(7))
        val e = leader.step(Input.ReadIndex(8))
        e.sent().filterIsInstance<AppendEntries>().forEach { it.readSeq shouldBe 2L }
        leader.receive(ack(n2, 1)).readies() shouldContainExactly listOf(Effect.ReadReady(7, 1))
        leader.receive(ack(n3, 1)).readies().shouldBeEmpty()
        leader.receive(ack(n3, 2)).readies() shouldContainExactly listOf(Effect.ReadReady(8, 1))
    }

    @Test
    fun heartbeatsAfterReadCarryTheRound() {
        val leader = committedLeader()
        leader.step(Input.ReadIndex(7))
        val beats = leader.tick(3).sent().filterIsInstance<AppendEntries>()
        beats.size shouldBe 2
        beats.forEach { it.readSeq shouldBe 1L }
        leader.receive(ack(n3, 1)).readies() shouldContainExactly listOf(Effect.ReadReady(7, 1))
    }

    @Test
    fun singleNodeLeaderCompletesImmediately() {
        val leader = node(members = setOf(n1))
        leader.tick(10)
        leader.role shouldBe Role.LEADER
        leader.commitIndex shouldBe 1L
        leader.step(Input.ReadIndex(7)) shouldContainExactly listOf(Effect.ReadReady(7, 1))
    }

    @Test
    fun readIndexIsCommitAtReceiptAndAppliedAtCompletion() {
        val sm = RecordingStateMachine()
        val l = node(stateMachine = sm)
        l.campaign()
        l.receive(rvr(1, n2))
        l.receive(aer(1, n2, matchIndex = 1))
        l.step(Input.ReadIndex(7))
        l.propose(1, "x")
        l.receive(aer(1, n2, matchIndex = 2)).readies().shouldBeEmpty()
        val r = l.receive(ack(n3, 1)).readies().single()
        r shouldBe Effect.ReadReady(7, 1)
        l.lastApplied shouldBe 2L
        (r.readIndex <= l.lastApplied) shouldBe true
        sm.applied.map { it.first } shouldContainExactly listOf(2L)
    }

    @Test
    fun readsLeaveLogCommitTermAndStorageUnchanged() {
        val storage = RecordingStorage()
        val rec = recorder()
        val leader = committedLeader(storage, rec)
        val log = leader.logSnapshotForChecks()
        val commit = leader.commitIndex
        val term = leader.currentTerm
        val saves = storage.saves.size
        val ops = storage.ops.size
        val before = rec.events.size
        leader.step(Input.ReadIndex(7))
        leader.receive(ack(n2, 1)).readies() shouldContainExactly listOf(Effect.ReadReady(7, 1))
        leader.logSnapshotForChecks() shouldBe log
        leader.commitIndex shouldBe commit
        leader.currentTerm shouldBe term
        storage.saves.size shouldBe saves
        storage.ops.size shouldBe ops
        rec.events.drop(before).none { it is LogAppended || it is CommitAdvanced || it is EntryApplied } shouldBe true
    }

    @Test
    fun deposedLeaderRejectsPendingReadsAndNeverCompletesThem() {
        val leader = committedLeader()
        leader.step(Input.ReadIndex(7))
        leader.step(Input.ReadIndex(8))
        val e = leader.receive(ae(2, n2))
        e.filterIsInstance<Effect.Rejected>() shouldContainExactly
            listOf(Effect.Rejected(7, Rejection.NotLeader(null)), Effect.Rejected(8, Rejection.NotLeader(null)))
        leader.role shouldBe Role.FOLLOWER
        leader.receive(ack(n3, 1)).readies().shouldBeEmpty()
        leader.receive(ack(n3, 2, term = 2)).readies().shouldBeEmpty()
    }

    @Test
    fun isolatedLeaderNeverAnswersRead() {
        val leader = committedLeader()
        leader.step(Input.ReadIndex(7))
        val effects = leader.tick(30)
        effects.filterIsInstance<Effect.ReadReady>().shouldBeEmpty()
        effects.filterIsInstance<Effect.Rejected>().shouldBeEmpty()
    }

    @Test
    fun leaderRemovedByCommittedConfigDoesNotCompletePendingRead() {
        val leader = committedLeader()
        leader.step(Input.ChangeMembership(50, null, n1))
        leader.members shouldBe setOf(n2, n3)
        leader.step(Input.ReadIndex(7))
        leader.receive(ack(n2, 1, matchIndex = 2)).readies().shouldBeEmpty()
        val e = leader.receive(ack(n3, 1, matchIndex = 2))
        e.readies().shouldBeEmpty()
        e shouldContain Effect.Rejected(7, Rejection.NotLeader(null))
        leader.role shouldBe Role.FOLLOWER
        leader.receive(ack(n2, 1)).readies().shouldBeEmpty()
    }

    @Test
    fun catchUpTargetAndOutsidersDoNotCountTowardsReadQuorum() {
        val leader = committedLeader()
        leader.step(Input.ChangeMembership(50, n4, null))
        val e = leader.step(Input.ReadIndex(7))
        e.sent().filterIsInstance<AppendEntries>().map { it.to } shouldContainExactly listOf(n2, n3, n4)
        leader.receive(AppendEntriesResponse(1, n4, n1, false, 0, 1, 0, 1)).readies().shouldBeEmpty()
        leader.receive(ack(n5, 1)).readies().shouldBeEmpty()
        leader.receive(ack(n2, 1)).readies() shouldContainExactly listOf(Effect.ReadReady(7, 1))
    }

    @Test
    fun quorumAfterAddingFourthServerNeedsThreeAcks() {
        val leader = committedLeader()
        leader.step(Input.ChangeMembership(50, n4, null))
        leader.receive(ack(n4, 0, matchIndex = 1))
        leader.members shouldBe setOf(n1, n2, n3, n4)
        leader.step(Input.ReadIndex(7))
        leader.receive(ack(n2, 1)).readies().shouldBeEmpty()
        leader.receive(ack(n3, 1)).readies() shouldContainExactly listOf(Effect.ReadReady(7, 1))
    }
}
