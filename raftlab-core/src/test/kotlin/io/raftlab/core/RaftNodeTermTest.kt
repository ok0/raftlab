package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

private val allRoles = listOf(Role.FOLLOWER, Role.CANDIDATE, Role.LEADER)

fun inRole(
    role: Role,
    storage: RecordingStorage,
): RaftNode {
    val node =
        when (role) {
            Role.FOLLOWER -> {
                node(storage = storage)
            }

            Role.CANDIDATE -> {
                node(storage = storage).also { it.tick(10) }
            }

            Role.LEADER -> {
                node(storage = storage).also {
                    it.tick(10)
                    it.receive(rvr(1, n2))
                }
            }

            else -> {
                error("unsupported role $role")
            }
        }
    storage.saves.clear()
    return node
}

class RaftNodeTermTest {
    @Test
    fun higherTermRequestVoteForcesFollower() {
        for (role in allRoles) {
            val storage = RecordingStorage()
            val node = inRole(role, storage)
            val effects = node.receive(rv(5, n2, lastIndex = 1, lastTerm = 5))
            node.role shouldBe Role.FOLLOWER
            node.currentTerm shouldBe 5
            storage.saves.first() shouldBe PersistentState(5, null)
            effects shouldBe listOf(Effect.Send(RequestVoteResponse(5, n1, n2, true, false)))
            storage.loadState() shouldBe PersistentState(5, n2)
        }
    }

    @Test
    fun higherTermVoteResponseForcesFollower() {
        for (role in allRoles) {
            val storage = RecordingStorage()
            val node = inRole(role, storage)
            val effects = node.receive(rvr(5, n2, granted = false))
            effects shouldBe emptyList<Effect>()
            node.role shouldBe Role.FOLLOWER
            node.currentTerm shouldBe 5
            storage.saves shouldBe listOf(PersistentState(5, null))
            node.leaderHint shouldBe null
        }
    }

    @Test
    fun higherTermAppendEntriesForcesFollower() {
        for (role in allRoles) {
            val storage = RecordingStorage()
            val node = inRole(role, storage)
            val effects = node.receive(ae(5, n2))
            effects shouldBe listOf(Effect.Send(AppendEntriesResponse(5, n1, n2, true, 0, 0, 0, 0)))
            node.role shouldBe Role.FOLLOWER
            node.currentTerm shouldBe 5
            storage.saves shouldBe listOf(PersistentState(5, null))
            node.leaderHint shouldBe n2
        }
    }

    @Test
    fun lowerTermMessagesChangeNothing() {
        val followerStorage = RecordingStorage()
        followerStorage.saveState(PersistentState(3, null))
        followerStorage.saves.clear()
        val follower = node(storage = followerStorage)
        follower.receive(rv(2, n2)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(3, n1, n2, false, false)))
        follower.receive(ae(2, n2)) shouldBe
            listOf(Effect.Send(AppendEntriesResponse(3, n1, n2, false, 0, 0, 0, 0)))
        follower.currentTerm shouldBe 3
        follower.leaderHint shouldBe null
        followerStorage.saves.isEmpty() shouldBe true

        val candidateStorage = RecordingStorage()
        candidateStorage.saveState(PersistentState(2, null))
        val candidate = node(storage = candidateStorage)
        candidate.tick(10)
        candidateStorage.saves.clear()
        candidate.receive(rvr(2, n2)) shouldBe emptyList<Effect>()
        candidate.role shouldBe Role.CANDIDATE
        candidate.receive(ae(2, n2)) shouldBe
            listOf(Effect.Send(AppendEntriesResponse(3, n1, n2, false, 0, 0, 0, 0)))
        candidate.role shouldBe Role.CANDIDATE
        candidate.currentTerm shouldBe 3
        candidateStorage.saves.isEmpty() shouldBe true
    }
}
