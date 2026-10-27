package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.VoteCast
import org.junit.jupiter.api.Test

class RaftNodeTraceTest {
    @Test
    fun electionSequenceEmitsRoleChanges() {
        val rec = recorder()
        val node = node(trace = rec, config = RaftConfig(preVote = false))
        node.tick(10)
        node.receive(rvr(1, n2))
        node.receive(ae(2, n2))
        rec.events shouldBe
            listOf(
                RoleChanged(0, 0, n1, Role.FOLLOWER, Role.CANDIDATE, 1, "election timeout"),
                RoleChanged(1, 0, n1, Role.CANDIDATE, Role.LEADER, 1, "won election"),
                LogAppended(2, 0, n1, 1, 1, EntryKind.NOOP, "noop"),
                RoleChanged(3, 0, n1, Role.LEADER, Role.FOLLOWER, 2, "higher term"),
            )
    }

    @Test
    fun reElectionEmitsCandidateToCandidate() {
        val rec = recorder()
        val node = node(trace = rec, config = RaftConfig(preVote = false))
        node.tick(20)
        rec.events[1] shouldBe RoleChanged(1, 0, n1, Role.CANDIDATE, Role.CANDIDATE, 2, "election timeout")
    }

    @Test
    fun candidateDiscoveringLeaderIsTraced() {
        val rec = recorder()
        val node = node(trace = rec, config = RaftConfig(preVote = false))
        node.tick(10)
        node.receive(ae(1, n2))
        rec.events.last() shouldBe RoleChanged(1, 0, n1, Role.CANDIDATE, Role.FOLLOWER, 1, "leader discovered")
    }

    @Test
    fun votesAreTraced() {
        val rec = recorder()
        val node = node(trace = rec)
        node.receive(rv(1, n2))
        node.receive(rv(1, n3))
        node.receive(rv(0, n3))
        rec.events shouldBe
            listOf(
                VoteCast(0, 0, n1, 1, n2, true),
                VoteCast(1, 0, n1, 1, n3, false),
                VoteCast(2, 0, n1, 1, n3, false),
            )
    }
}
