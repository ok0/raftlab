package io.raftlab.core

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.raftlab.trace.LeadershipTransfer
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceRecorder
import org.junit.jupiter.api.Test

internal class RaftNodeTransferTest {
    private fun RaftNode.transfer(
        requestId: Long,
        target: NodeId,
    ): List<Effect> = step(Input.TransferLeadership(requestId, target))

    private fun committedLeader(
        rec: TraceRecorder = recorder(),
        storage: RaftStorage = InMemoryStorage(),
    ): RaftNode {
        val leader = node(storage = storage, trace = rec)
        leader.winElection(n2)
        leader.receive(aer(1, n2, matchIndex = 1))
        return leader
    }

    private fun List<Effect>.rejected() = filterIsInstance<Effect.Rejected>()

    private fun List<Effect>.timeoutNows() = sent().filterIsInstance<TimeoutNow>()

    @Test
    fun nonLeaderGetsNotLeader() {
        node().transfer(1, n2) shouldBe listOf(Effect.Rejected(1, Rejection.NotLeader(null)))
        val follower = node()
        follower.receive(ae(1, n2))
        follower.transfer(2, n2) shouldBe listOf(Effect.Rejected(2, Rejection.NotLeader(n2)))
    }

    @Test
    fun invalidTargetsGetUnknown() {
        val leader = committedLeader()
        leader.transfer(1, n1) shouldBe listOf(Effect.Rejected(1, Rejection.Unknown))
        leader.transfer(2, n4) shouldBe listOf(Effect.Rejected(2, Rejection.Unknown))
        leader.step(Input.ChangeMembership(5, n4, null))
        leader.transfer(3, n4) shouldBe listOf(Effect.Rejected(3, Rejection.Unknown))
    }

    @Test
    fun busyForSecondTransferAndMembershipChange() {
        val leader = committedLeader()
        leader.transfer(1, n3).rejected() shouldBe emptyList()
        leader.transfer(2, n2) shouldBe listOf(Effect.Rejected(2, Rejection.Busy))

        val uncommitted = committedLeader()
        uncommitted.step(Input.ChangeMembership(5, null, n3))
        uncommitted.transfer(3, n2) shouldBe listOf(Effect.Rejected(3, Rejection.Busy))

        val catchingUp = committedLeader()
        catchingUp.step(Input.ChangeMembership(5, n4, null))
        catchingUp.transfer(4, n2) shouldBe listOf(Effect.Rejected(4, Rejection.Busy))
    }

    @Test
    fun proposalsAndMembershipBusyWhileReadsServed() {
        val rec = recorder()
        val leader = committedLeader(rec = rec)
        val effects = leader.transfer(1, n3)
        effects.rejected() shouldBe emptyList()
        effects.timeoutNows() shouldBe emptyList()
        effects.sent().filterIsInstance<AppendEntries>().map { it.to } shouldContain n3
        rec.events.filterIsInstance<LeadershipTransfer>().single().let {
            it.node shouldBe n1
            it.target shouldBe n3
        }
        leader.propose(2, "x") shouldBe listOf(Effect.Rejected(2, Rejection.Busy))
        leader.step(Input.ChangeMembership(3, n4, null)) shouldBe listOf(Effect.Rejected(3, Rejection.Busy))
        leader.step(Input.ReadIndex(4)).rejected() shouldBe emptyList()
        leader.receive(AppendEntriesResponse(1, n2, n1, true, 1, 0, 0, 1)) shouldContain Effect.ReadReady(4, 1)
    }

    @Test
    fun timeoutNowOnlyAfterCatchUpAndOnce() {
        val leader = committedLeader()
        leader.transfer(1, n3).timeoutNows() shouldBe emptyList()
        leader.receive(aer(1, n3, matchIndex = 1)).timeoutNows() shouldBe listOf(TimeoutNow(1, n1, n3))
        leader.receive(aer(1, n3, matchIndex = 1))
        leader.tick(3)
        leader.receive(aer(1, n2, matchIndex = 1)).timeoutNows() shouldBe emptyList()

        val immediate = committedLeader()
        immediate.transfer(1, n2).timeoutNows() shouldBe listOf(TimeoutNow(1, n1, n2))
    }

    @Test
    fun laggingTargetIsCaughtUpViaInstallSnapshot() {
        val storage =
            compactedStorage(
                5,
                1,
                1,
                2,
                2,
                2,
                3,
                3,
                currentTerm = 3,
                data = "d".encodeToByteArray(),
            )
        val l = node(storage = storage)
        l.campaign()
        l.receive(rvr(4, n2))
        l.transfer(1, n3).timeoutNows() shouldBe emptyList()
        val snapshots = l.receive(aer(4, n3, success = false, conflictIndex = 1)).sent()
        snapshots.filterIsInstance<InstallSnapshot>().map { it.to } shouldContain n3
        l.receive(isr(4, n3, lastIndex = 5)).timeoutNows() shouldBe emptyList()
        l.receive(aer(4, n3, matchIndex = 8)).timeoutNows() shouldBe listOf(TimeoutNow(4, n1, n3))
    }

    @Test
    fun targetStartsRealElectionAndWins() {
        val rec = recorder()
        val storage = RecordingStorage()
        val f = node(id = n2, storage = storage, trace = rec)
        f.receive(ae(1, n1, to = n2))
        val requests = f.receive(TimeoutNow(1, n1, n2)).sent()
        requests shouldBe
            listOf(
                RequestVote(2, n2, n1, 0, 0, false),
                RequestVote(2, n2, n3, 0, 0, false),
            )
        f.role shouldBe Role.CANDIDATE
        f.currentTerm shouldBe 2
        storage.saves.last() shouldBe PersistentState(2, n2)
        rec.events.filterIsInstance<RoleChanged>().last().let {
            it.from shouldBe Role.FOLLOWER
            it.to shouldBe Role.CANDIDATE
            it.term shouldBe 2
            it.reason shouldBe "leadership transfer"
        }

        val p = node(id = n3)
        p.receive(ae(1, n1, to = n3))
        p.receive(RequestVote(2, n2, n3, 0, 0, false)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(2, n3, n2, true, false)))

        f.receive(RequestVoteResponse(2, n3, n2, true, false))
        f.role shouldBe Role.LEADER
        f.currentTerm shouldBe 2
    }

    @Test
    fun ignoredTimeoutNowChangesNothing() {
        val staleStorage = RecordingStorage(storageWith(2, emptyList()))
        val staleTerm = node(id = n2, storage = staleStorage)
        staleTerm.receive(ae(2, n1, to = n2))
        assertIgnored(staleTerm, staleStorage, TimeoutNow(1, n1, n2))

        val senderStorage = RecordingStorage(storageWith(2, emptyList()))
        val wrongSender = node(id = n2, storage = senderStorage)
        wrongSender.receive(ae(2, n1, to = n2))
        assertIgnored(wrongSender, senderStorage, TimeoutNow(2, n3, n2))

        val leaderStorage = RecordingStorage()
        val leader = leaderAtTerm1(storage = leaderStorage)
        assertIgnored(leader, leaderStorage, TimeoutNow(1, n2, n1))

        val memberStorage = RecordingStorage()
        val nonMember = node(id = n4, members = three, storage = memberStorage)
        nonMember.receive(ae(1, n1, to = n4))
        assertIgnored(nonMember, memberStorage, TimeoutNow(1, n1, n4))
    }

    private fun assertIgnored(
        n: RaftNode,
        storage: RecordingStorage,
        msg: TimeoutNow,
    ) {
        val role = n.role
        val term = n.currentTerm
        val hint = n.leaderHint
        val saves = storage.saves.toList()
        n.receive(msg) shouldBe emptyList()
        n.role shouldBe role
        n.currentTerm shouldBe term
        n.leaderHint shouldBe hint
        storage.saves shouldBe saves
    }

    @Test
    fun stepDownAnswersNotLeaderOnce() {
        val leader = committedLeader()
        leader.transfer(9, n2)
        leader.receive(rv(2, n2, lastIndex = 1, lastTerm = 1)).rejected() shouldBe
            listOf(Effect.Rejected(9, Rejection.NotLeader(null)))
        leader.role shouldBe Role.FOLLOWER
        leader.tick(100).rejected() shouldBe emptyList()

        val hinted = committedLeader()
        hinted.transfer(9, n3)
        hinted.receive(ae(2, n2)).rejected() shouldBe listOf(Effect.Rejected(9, Rejection.NotLeader(n2)))
    }

    @Test
    fun abandonedAfterElectionTimeoutLastTicks() {
        val leader = committedLeader()
        leader.transfer(9, n3)
        leader.tick(19).rejected() shouldBe emptyList()
        leader.tick(1).rejected() shouldBe listOf(Effect.Rejected(9, Rejection.Unknown))
        leader.tick(60).rejected() shouldBe emptyList()
        val proposed = leader.propose(10, "x")
        proposed.rejected() shouldBe emptyList()
        proposed.sent().any { it is AppendEntries } shouldBe true
        leader.role shouldBe Role.LEADER
        leader.currentTerm shouldBe 1
    }
}
