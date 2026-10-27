package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

class RaftNodeElectionTest {
    @Test
    fun followerTimesOutAndWinsWithOnePeerVote() {
        val storage = RecordingStorage()
        val candidate = node(storage = storage)
        candidate.tick(9) shouldBe emptyList<Effect>()
        candidate.role shouldBe Role.FOLLOWER
        candidate.currentTerm shouldBe 0L

        candidate.tick().sent() shouldBe
            listOf<RaftMessage>(
                RequestVote(1, n1, n2, 0, 0, false),
                RequestVote(1, n1, n3, 0, 0, false),
            )
        candidate.role shouldBe Role.CANDIDATE
        candidate.currentTerm shouldBe 1L
        storage.saves shouldBe listOf(PersistentState(1, n1))

        candidate.receive(rvr(1, n2)).sent() shouldBe
            listOf<RaftMessage>(
                AppendEntries(1, n1, n2, 0, 0, emptyList(), 0, 0),
                AppendEntries(1, n1, n3, 0, 0, emptyList(), 0, 0),
            )
        candidate.role shouldBe Role.LEADER
        candidate.leaderHint shouldBe n1
    }

    @Test
    fun heartbeatsKeepFollowerFromElecting() {
        val follower = node()
        repeat(50) {
            follower.tick(5) shouldBe emptyList<Effect>()
            follower.receive(ae(1, n2)).sent() shouldBe
                listOf<RaftMessage>(
                    AppendEntriesResponse(1, n1, n2, true, 0, 0, 0, 0),
                )
        }
        follower.role shouldBe Role.FOLLOWER
        follower.currentTerm shouldBe 1L
        follower.leaderHint shouldBe n2
    }

    @Test
    fun voteGrantResetsElectionTimer() {
        val follower = node()
        follower.tick(9) shouldBe emptyList<Effect>()
        follower.receive(rv(1, n2)).sent() shouldBe
            listOf<RaftMessage>(
                RequestVoteResponse(1, n1, n2, true, false),
            )
        follower.tick(9) shouldBe emptyList<Effect>()
        follower.tick().sent() shouldBe
            listOf<RaftMessage>(
                RequestVote(2, n1, n2, 0, 0, false),
                RequestVote(2, n1, n3, 0, 0, false),
            )
    }

    @Test
    fun candidateWithoutMajorityReElectsAtHigherTerm() {
        val storage = RecordingStorage()
        val candidate = node(storage = storage)
        candidate.tick(10)
        candidate.currentTerm shouldBe 1L
        candidate.tick(9) shouldBe emptyList<Effect>()
        candidate.tick().sent() shouldBe
            listOf<RaftMessage>(
                RequestVote(2, n1, n2, 0, 0, false),
                RequestVote(2, n1, n3, 0, 0, false),
            )
        candidate.role shouldBe Role.CANDIDATE
        candidate.currentTerm shouldBe 2L
        storage.loadState() shouldBe PersistentState(2, n1)
    }

    @Test
    fun candidateStepsDownOnSameTermAppendEntries() {
        val candidate = node()
        candidate.tick(10)
        candidate.receive(ae(1, n2)) shouldBe
            listOf<Effect>(
                Effect.Send(AppendEntriesResponse(1, n1, n2, true, 0, 0, 0, 0)),
            )
        candidate.role shouldBe Role.FOLLOWER
        candidate.currentTerm shouldBe 1L
        candidate.leaderHint shouldBe n2
    }

    @Test
    fun leaderNeverStartsElection() {
        val leader = leaderAtTerm1()
        leader.tick(1000).sent().all { it is AppendEntries } shouldBe true
        leader.role shouldBe Role.LEADER
        leader.currentTerm shouldBe 1L
    }

    @Test
    fun singleMemberClusterWinsOnFirstTimeout() {
        val solo = node(members = setOf(n1))
        solo.tick(9) shouldBe emptyList<Effect>()
        solo.tick() shouldBe emptyList<Effect>()
        solo.role shouldBe Role.LEADER
        solo.currentTerm shouldBe 1L
        solo.leaderHint shouldBe n1
    }

    @Test
    fun duplicateAndStaleVotesCountOnce() {
        val candidate = node(members = five)
        candidate.tick(10)
        candidate.receive(rvr(1, n2))
        candidate.receive(rvr(1, n2))
        candidate.role shouldBe Role.CANDIDATE
        candidate.receive(rvr(0, n3))
        candidate.role shouldBe Role.CANDIDATE
        candidate.receive(rvr(1, n3, granted = false))
        candidate.role shouldBe Role.CANDIDATE
        val effects = candidate.receive(rvr(1, n3))
        candidate.role shouldBe Role.LEADER
        effects.sent().size shouldBe 4
        effects.sent().all { it is AppendEntries } shouldBe true
    }

    @Test
    fun heartbeatsEveryHeartbeatTicks() {
        listOf(3, 5).forEach { heartbeat ->
            val leader = node(config = RaftConfig(10..20, heartbeat))
            leader.tick(10)
            leader.receive(rvr(1, n2))
            leader.role shouldBe Role.LEADER
            val beats = (1..30).filter { leader.tick().isNotEmpty() }
            beats shouldBe (heartbeat..30 step heartbeat).toList()
        }
    }

    @Test
    fun leaderIgnoresSameTermAppendEntries() {
        val leader = leaderAtTerm1()
        leader.receive(ae(1, n2)) shouldBe emptyList<Effect>()
        leader.role shouldBe Role.LEADER
        leader.leaderHint shouldBe n1
    }
}
