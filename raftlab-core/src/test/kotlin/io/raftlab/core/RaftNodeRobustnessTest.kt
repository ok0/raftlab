package io.raftlab.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

class RaftNodeRobustnessTest {
    @Test
    fun freshStorageStartsAsFollowerAtTermZero() {
        val node = node()
        node.role shouldBe Role.FOLLOWER
        node.currentTerm shouldBe 0
        node.leaderHint shouldBe null
        node.members shouldBe three
    }

    @Test
    fun restartRestoresTermAndVoteAsFollower() {
        val storage = InMemoryStorage()
        leaderAtTerm1(storage)
        val restarted = node(storage = storage)
        restarted.role shouldBe Role.FOLLOWER
        restarted.currentTerm shouldBe 1
        restarted.leaderHint shouldBe null
        restarted.receive(rv(1, n3)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(1, n1, n3, false, false)))
    }

    @Test
    fun acceptsIdOutsideMembersButNotEmptyMembers() {
        node(id = NodeId("x"), members = three).members shouldBe three
        shouldThrow<IllegalArgumentException> { node(members = emptySet()) }
    }

    @Test
    fun ignoresNonMemberAndMisaddressedMessages() {
        val node = node()
        node.receive(rv(5, NodeId("x9"))) shouldBe emptyList<Effect>()
        node.receive(aer(5, NodeId("x9"))) shouldBe emptyList<Effect>()
        node.receive(rvr(5, NodeId("x9"))) shouldBe emptyList<Effect>()
        node.receive(rv(5, n2, to = n3)) shouldBe emptyList<Effect>()
        node.receive(rv(5, n1)) shouldBe emptyList<Effect>()
        node.currentTerm shouldBe 0
    }

    @Test
    fun acceptsAppendEntriesFromNonMember() {
        val node = node()
        node.receive(ae(1, NodeId("x9"))) shouldBe
            listOf(Effect.Send(AppendEntriesResponse(1, n1, NodeId("x9"), true, 0, 0, 0, 0)))
        node.currentTerm shouldBe 1
        node.leaderHint shouldBe NodeId("x9")
    }

    @Test
    fun ignoresUnexpectedMessageTypes() {
        val node = node()
        node.receive(InstallSnapshot(0, n2, n1, 0, 0, three, ByteArray(0))) shouldBe
            listOf<Effect>(Effect.Send(InstallSnapshotResponse(0, n1, n2, 0)))
        node.receive(InstallSnapshotResponse(0, n2, n1, 0)) shouldBe emptyList<Effect>()
        node.receive(TimeoutNow(0, n2, n1)) shouldBe emptyList<Effect>()
        node.receive(AppendEntriesResponse(0, n2, n1, true, 0, 0, 0, 0)) shouldBe emptyList<Effect>()
        node.receive(RequestVote(5, n2, n1, 0, 0, true)) shouldBe emptyList<Effect>()
        node.receive(RequestVoteResponse(5, n2, n1, true, true)) shouldBe emptyList<Effect>()
        node.role shouldBe Role.FOLLOWER
        node.currentTerm shouldBe 0
        node.tick(9) shouldBe emptyList<Effect>()
    }

    @Test
    fun duplicatedMessagesAreHarmless() {
        val follower = node()
        val first = follower.receive(ae(1, n2))
        val second = follower.receive(ae(1, n2))
        val expected = listOf(Effect.Send(AppendEntriesResponse(1, n1, n2, true, 0, 0, 0, 0)))
        first shouldBe expected
        second shouldBe expected

        val leader = leaderAtTerm1()
        leader.receive(rvr(1, n2)) shouldBe emptyList<Effect>()
        leader.receive(rvr(1, n2)) shouldBe emptyList<Effect>()
    }
}
