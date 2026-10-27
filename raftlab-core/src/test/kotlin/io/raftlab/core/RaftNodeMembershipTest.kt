package io.raftlab.core

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.ConfigChanged
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceRecorder
import org.junit.jupiter.api.Test

internal class RaftNodeMembershipTest {
    private val four = setOf(n1, n2, n3, n4)

    private fun committedLeader(
        rec: TraceRecorder = recorder(),
        config: RaftConfig = RaftConfig(),
        storage: RaftStorage = InMemoryStorage(),
        sm: StateMachine = NoopStateMachine,
    ): RaftNode {
        val leader = node(config = config, storage = storage, stateMachine = sm, trace = rec)
        leader.winElection(n2)
        leader.receive(aer(1, n2, matchIndex = 1))
        return leader
    }

    private fun RaftNode.cm(
        id: Long,
        add: NodeId?,
        remove: NodeId?,
    ): List<Effect> = step(Input.ChangeMembership(id, add, remove))

    @Test
    fun nonLeaderGetsNotLeader() {
        val follower = node()
        follower.cm(1, null, n3) shouldBe listOf(Effect.Rejected(1, Rejection.NotLeader(null)))
        follower.receive(ae(1, n2))
        follower.cm(1, null, n3) shouldBe listOf(Effect.Rejected(1, Rejection.NotLeader(n2)))
    }

    @Test
    fun invalidRequestsGetUnknown() {
        val leader = committedLeader()
        leader.cm(1, n4, n2) shouldBe listOf(Effect.Rejected(1, Rejection.Unknown))
        leader.cm(2, null, null) shouldBe listOf(Effect.Rejected(2, Rejection.Unknown))
        leader.cm(3, n2, null) shouldBe listOf(Effect.Rejected(3, Rejection.Unknown))
        leader.cm(4, null, n4) shouldBe listOf(Effect.Rejected(4, Rejection.Unknown))
        leader.logSnapshotForChecks().size shouldBe 1
        leader.members shouldBe three

        val solo = node(members = setOf(n1))
        solo.tick(10)
        solo.cm(9, null, n1) shouldBe listOf(Effect.Rejected(9, Rejection.Unknown))
    }

    @Test
    fun busyBeforeFirstCurrentTermCommit() {
        val leader = leaderAtTerm1()
        leader.cm(1, null, n3) shouldBe listOf(Effect.Rejected(1, Rejection.Busy))
        leader.logSnapshotForChecks().size shouldBe 1
        leader.members shouldBe three
    }

    @Test
    fun removeAppendsConfigImmediately() {
        val rec = recorder()
        val leader = committedLeader(rec)
        val effects = leader.cm(5, null, n3)
        leader.members shouldBe setOf(n1, n2)
        leader.logSnapshotForChecks().last() shouldBe LogEntry(2, 1, EntryPayload.Config(setOf(n1, n2)))
        effects.sent() shouldBe
            listOf(AppendEntries(1, n1, n2, 1, 1, listOf(LogEntry(2, 1, EntryPayload.Config(setOf(n1, n2)))), 1, 0))
        val tail = rec.events.takeLast(2)
        (tail[0] as LogAppended).let {
            it.index shouldBe 2L
            it.entryKind shouldBe EntryKind.CONFIG
            it.summary shouldBe "config members=n1,n2"
        }
        (tail[1] as ConfigChanged).let {
            it.node shouldBe n1
            it.members shouldBe listOf(n1, n2)
        }
    }

    @Test
    fun secondChangeWhileConfigUncommittedIsBusy() {
        val leader = committedLeader()
        leader.cm(5, null, n3)
        leader.cm(6, n4, null) shouldBe listOf(Effect.Rejected(6, Rejection.Busy))
        leader.logSnapshotForChecks().size shouldBe 2
        leader.members shouldBe setOf(n1, n2)
        leader.catchUpTarget shouldBe null
    }

    @Test
    fun removeCommitsWithMajorityOfNewConfigAndEmitsApplied() {
        val leader = node(members = four)
        leader.winElection(n2, n3)
        leader.receive(aer(1, n2, matchIndex = 1))
        leader.commitIndex shouldBe 0L
        leader.receive(aer(1, n3, matchIndex = 1))
        leader.commitIndex shouldBe 1L
        leader.cm(7, null, n4)
        val effects = leader.receive(aer(1, n2, matchIndex = 2))
        effects shouldContain Effect.Applied(7, 2, ByteArray(0))
        leader.commitIndex shouldBe 2L
    }

    @Test
    fun addCatchesUpThenAppendsConfigAndNeedsThreeOfFour() {
        val leader = committedLeader()
        leader.propose(1, "a")
        leader.receive(aer(1, n2, matchIndex = 2))
        leader.commitIndex shouldBe 2L

        leader.cm(7, n4, null).sent() shouldBe listOf(AppendEntries(1, n1, n4, 2, 1, emptyList(), 2, 0))
        leader.logSnapshotForChecks().size shouldBe 2
        leader.catchUpTarget shouldBe n4
        leader.members shouldBe three

        leader.cm(8, null, n2) shouldBe listOf(Effect.Rejected(8, Rejection.Busy))

        leader.receive(aer(1, n4, matchIndex = 1))
        leader.logSnapshotForChecks().size shouldBe 2

        leader.receive(aer(1, n4, matchIndex = 2))
        leader.logSnapshotForChecks().last() shouldBe LogEntry(3, 1, EntryPayload.Config(four))
        leader.members shouldBe four
        leader.catchUpTarget shouldBe null

        leader.receive(aer(1, n2, matchIndex = 3))
        leader.commitIndex shouldBe 2L

        val effects = leader.receive(aer(1, n3, matchIndex = 3))
        leader.commitIndex shouldBe 3L
        effects shouldContain Effect.Applied(7, 3, ByteArray(0))
    }

    @Test
    fun catchUpVoteDoesNotCount() {
        val candidate = node()
        candidate.tick(10)
        candidate.receive(rvr(1, n4))
        candidate.role shouldBe Role.CANDIDATE
    }

    @Test
    fun catchUpBehindCompactedPrefixGetsInstallSnapshot() {
        val leader = node(storage = compactedStorage(3, 1, 1, 1, currentTerm = 1))
        leader.winElection(n2)
        leader.receive(aer(2, n2, matchIndex = 4))
        leader.commitIndex shouldBe 4L

        leader.cm(1, n4, null)
        leader.receive(aer(2, n4, success = false, conflictIndex = 1)).sent() shouldBe
            listOf(isnap(2, n1, n4, lastIndex = 3, lastTerm = 1))

        leader.receive(isr(2, n4, lastIndex = 3))
        leader.logSnapshotForChecks().none { it.payload is EntryPayload.Config } shouldBe true

        leader.receive(aer(2, n4, matchIndex = 4))
        leader.logSnapshotForChecks().last() shouldBe LogEntry(5, 2, EntryPayload.Config(four))
    }

    @Test
    fun catchUpStallAbandonsWithUnknown() {
        val leader = committedLeader()
        leader.cm(9, n4, null)
        leader.tick(199).filterIsInstance<Effect.Rejected>() shouldBe emptyList()
        leader.tick(1).filterIsInstance<Effect.Rejected>() shouldBe listOf(Effect.Rejected(9, Rejection.Unknown))
        leader.catchUpTarget shouldBe null
        leader.members shouldBe three
        leader.tick(3).sent().none { it.to == n4 } shouldBe true
    }

    @Test
    fun leaderLossMidCatchUpGivesNotLeader() {
        val leader = committedLeader()
        leader.cm(10, n4, null)
        leader.receive(ae(2, n2)) shouldContain Effect.Rejected(10, Rejection.NotLeader(null))
        leader.role shouldBe Role.FOLLOWER
    }

    @Test
    fun removedLeaderStepsDownAfterCommit() {
        val rec = recorder()
        val leader = committedLeader(rec)
        leader.cm(11, null, n1)
        leader.members shouldBe setOf(n2, n3)
        leader.receive(aer(1, n2, matchIndex = 2))
        leader.commitIndex shouldBe 1L
        val effects = leader.receive(aer(1, n3, matchIndex = 2))
        effects shouldContain Effect.Applied(11, 2, ByteArray(0))
        leader.role shouldBe Role.FOLLOWER

        val roleChanges = rec.events.filterIsInstance<RoleChanged>()
        val last = roleChanges.last()
        last.from shouldBe Role.LEADER
        last.to shouldBe Role.FOLLOWER
        last.reason shouldBe "removed from configuration"
        val commitPos = rec.events.indexOfFirst { it is CommitAdvanced && it.commitIndex == 2L }
        (commitPos in 0 until rec.events.indexOf(last)) shouldBe true

        leader.tick(300) shouldBe emptyList<Effect>()
        leader.currentTerm shouldBe 1
        leader.role shouldBe Role.FOLLOWER
    }

    @Test
    fun joiningNodeOutsideConfigNeverCampaigns() {
        val joiner = node(id = n4, members = three)
        joiner.tick(300) shouldBe emptyList<Effect>()
        joiner.currentTerm shouldBe 0
        joiner.role shouldBe Role.FOLLOWER

        joiner.receive(ae(1, n1, to = n4)).sent() shouldBe
            listOf(AppendEntriesResponse(1, n4, n1, true, 0, 0, 0, 0))
        joiner.receive(rv(2, n1, to = n4)).sent() shouldBe listOf(RequestVoteResponse(2, n4, n1, true, false))
    }
}
