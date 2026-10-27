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
    fun rejectsIdOutsideMembers() {
        shouldThrow<IllegalArgumentException> { node(id = NodeId("x"), members = three) }
    }

    @Test
    fun ignoresNonMemberAndMisaddressedMessages() {
        val node = node()
        node.receive(rv(5, NodeId("x9"))) shouldBe emptyList<Effect>()
        node.receive(ae(5, NodeId("x9"))) shouldBe emptyList<Effect>()
        node.receive(rv(5, n2, to = n3)) shouldBe emptyList<Effect>()
        node.receive(rv(5, n1)) shouldBe emptyList<Effect>()
        node.currentTerm shouldBe 0
    }

    @Test
    fun ignoresUnexpectedMessageTypes() {
        val node = node()
        node.receive(InstallSnapshot(0, n2, n1, 0, 0, three, ByteArray(0))) shouldBe emptyList<Effect>()
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
