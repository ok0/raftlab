package io.raftlab.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.trace.Role
import org.junit.jupiter.api.Test

class RaftNodeVotingTest {
    private fun voter(storage: InMemoryStorage = storageWithLog(1, 1, 2)): RaftNode {
        storage.saveState(PersistentState(2, null))
        return node(storage = storage)
    }

    @Test
    fun deniesCandidateWithLowerLastTerm() {
        voter().receive(rv(3, n2, lastIndex = 10, lastTerm = 1)) shouldBe
            listOf<Effect>(
                Effect.Send(RequestVoteResponse(3, n1, n2, false, false)),
            )
    }

    @Test
    fun deniesCandidateWithShorterLogInSameTerm() {
        voter().receive(rv(3, n2, lastIndex = 2, lastTerm = 2)) shouldBe
            listOf<Effect>(
                Effect.Send(RequestVoteResponse(3, n1, n2, false, false)),
            )
    }

    @Test
    fun grantsCandidateWithEqualLog() {
        val storage = storageWithLog(1, 1, 2)
        val target = voter(storage)
        target.receive(rv(3, n2, lastIndex = 3, lastTerm = 2)) shouldBe
            listOf<Effect>(
                Effect.Send(RequestVoteResponse(3, n1, n2, true, false)),
            )
        storage.loadState() shouldBe PersistentState(3, n2)
    }

    @Test
    fun grantsCandidateWithLongerOrNewerLog() {
        voter().receive(rv(3, n2, lastIndex = 5, lastTerm = 2)) shouldBe
            listOf<Effect>(
                Effect.Send(RequestVoteResponse(3, n1, n2, true, false)),
            )
        voter().receive(rv(3, n2, lastIndex = 1, lastTerm = 3)) shouldBe
            listOf<Effect>(
                Effect.Send(RequestVoteResponse(3, n1, n2, true, false)),
            )
    }

    @Test
    fun usesSnapshotCoveredTail() {
        fun compactedStorage(): InMemoryStorage {
            val storage = storageWithLog(1, 1, 2)
            storage.saveSnapshot(SnapshotMeta(3, 2, three), ByteArray(0))
            storage.compactTo(3)
            return storage
        }

        val probe = compactedStorage()
        probe.lastIndex() shouldBe 3L
        probe.termAt(3) shouldBe 2L

        node(storage = compactedStorage()).receive(rv(3, n2, lastIndex = 2, lastTerm = 2)) shouldBe
            listOf<Effect>(
                Effect.Send(RequestVoteResponse(3, n1, n2, false, false)),
            )
        node(storage = compactedStorage()).receive(rv(3, n2, lastIndex = 3, lastTerm = 2)) shouldBe
            listOf<Effect>(
                Effect.Send(RequestVoteResponse(3, n1, n2, true, false)),
            )
    }

    @Test
    fun oneVotePerTermSurvivesRestart() {
        val storage = InMemoryStorage()
        val first = node(storage = storage)
        first.receive(rv(1, n2)).sent() shouldBe
            listOf<RaftMessage>(
                RequestVoteResponse(1, n1, n2, true, false),
            )
        storage.loadState() shouldBe PersistentState(1, n2)
        first.receive(rv(1, n3)).sent() shouldBe
            listOf<RaftMessage>(
                RequestVoteResponse(1, n1, n3, false, false),
            )
        first.receive(rv(1, n2)).sent() shouldBe
            listOf<RaftMessage>(
                RequestVoteResponse(1, n1, n2, true, false),
            )

        val restarted = node(storage = storage)
        restarted.currentTerm shouldBe 1L
        restarted.role shouldBe Role.FOLLOWER
        restarted.receive(rv(1, n3)).sent() shouldBe
            listOf<RaftMessage>(
                RequestVoteResponse(1, n1, n3, false, false),
            )
        restarted.receive(rv(1, n2)).sent() shouldBe
            listOf<RaftMessage>(
                RequestVoteResponse(1, n1, n2, true, false),
            )
    }

    @Test
    fun voteIsPersistedBeforeResponse() {
        val storage = RecordingStorage()
        node(storage = storage).receive(rv(1, n2)).sent() shouldBe
            listOf<RaftMessage>(
                RequestVoteResponse(1, n1, n2, true, false),
            )
        storage.saves shouldBe listOf(PersistentState(1, null), PersistentState(1, n2))

        val failing = RecordingStorage().apply { failSaves = true }
        val blocked = node(storage = failing)
        shouldThrow<IllegalStateException> { blocked.receive(rv(1, n2)) }
    }
}
