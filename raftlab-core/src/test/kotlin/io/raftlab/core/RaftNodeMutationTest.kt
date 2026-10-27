package io.raftlab.core

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

internal class RaftNodeMutationTest {
    @Test
    fun skipLogUpToDateCheckGrantsStaleCandidate() {
        val unmutated = RaftConfig()
        val mutated = RaftConfig(mutations = setOf(Mutation.SKIP_LOG_UP_TO_DATE_CHECK))
        val storage = storageWithLog(1, 1, 2)
        storage.saveState(PersistentState(2, null))
        node(config = unmutated, storage = storage).receive(rv(3, n2, lastIndex = 10, lastTerm = 1)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(3, n1, n2, false, false)))
        node(config = mutated, storage = storage).receive(rv(3, n2, lastIndex = 10, lastTerm = 1)) shouldBe
            listOf(Effect.Send(RequestVoteResponse(3, n1, n2, true, false)))
    }

    @Test
    fun commitWithoutMajorityCommitsOnMinorityAck() {
        val unmutated = node(members = five, config = RaftConfig())
        unmutated.winElection(n2, n3)
        unmutated.receive(aer(1, n2, success = true, matchIndex = 1))
        unmutated.commitIndex shouldBe 0L

        val mutated = node(members = five, config = RaftConfig(mutations = setOf(Mutation.COMMIT_WITHOUT_MAJORITY)))
        mutated.winElection(n2, n3)
        mutated.receive(aer(1, n2, success = true, matchIndex = 1))
        mutated.commitIndex shouldBe 1L
    }

    @Test
    fun commitPreviousTermEntriesCommitsOldEntryByCount() {
        val log = storageWith(3, listOf(cmd(1, 1, "a"), cmd(2, 2, "b")))
        val unmutated = node(members = five, storage = log, config = RaftConfig())
        unmutated.winElection(n2, n3)
        unmutated.receive(aer(4, n2, matchIndex = 2))
        unmutated.receive(aer(4, n3, matchIndex = 2))
        unmutated.commitIndex shouldBe 0L

        val mutatedLog = storageWith(3, listOf(cmd(1, 1, "a"), cmd(2, 2, "b")))
        val mutated =
            node(
                members = five,
                storage = mutatedLog,
                config = RaftConfig(mutations = setOf(Mutation.COMMIT_PREVIOUS_TERM_ENTRIES)),
            )
        mutated.winElection(n2, n3)
        mutated.receive(aer(4, n2, matchIndex = 2))
        mutated.receive(aer(4, n3, matchIndex = 2))
        mutated.commitIndex shouldBe 2L
    }
}
