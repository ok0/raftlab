package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.NodeId
import org.junit.jupiter.api.Test

internal class RaftNodeLeaderReplicationTest {
    @Test
    fun electionAppendsNoOpAndFirstAppendEntriesCarriesIt() {
        val storage = RecordingStorage()
        val leader = node(storage = storage)
        leader.tick(10)
        leader.receive(rvr(1, n2)).sent() shouldBe
            listOf(
                AppendEntries(1, n1, n2, 0, 0, listOf(noop(1, 1)), 0, 0),
                AppendEntries(1, n1, n3, 0, 0, listOf(noop(1, 1)), 0, 0),
            )
        leader.logSnapshotForChecks() shouldBe listOf(noop(1, 1))
        storage.lastIndex() shouldBe 1L
    }

    @Test
    fun noOpFollowsExistingLog() {
        val leader = node(storage = storageWith(2, logWithTerms(1, 2)))
        leader.winElection(n2).sent() shouldBe
            listOf(
                AppendEntries(3, n1, n2, 2, 2, listOf(noop(3, 3)), 0, 0),
                AppendEntries(3, n1, n3, 2, 2, listOf(noop(3, 3)), 0, 0),
            )
        leader.nextIndexOf(n2) shouldBe 3L
        leader.matchIndexOf(n2) shouldBe 0L
    }

    @Test
    fun proposeOnLeaderAppendsCommandAndReplicates() {
        val rec = recorder()
        val storage = RecordingStorage()
        val leader = leaderAtTerm1(storage, rec)
        leader.propose(7, "x") shouldBe
            listOf(
                Effect.Send(AppendEntries(1, n1, n2, 0, 0, listOf(noop(1, 1), cmd(2, 1, "x")), 0, 0)),
                Effect.Send(AppendEntries(1, n1, n3, 0, 0, listOf(noop(1, 1), cmd(2, 1, "x")), 0, 0)),
            )
        storage.entries(2, 3) shouldBe listOf(cmd(2, 1, "x"))
        rec.events.last() shouldBe LogAppended(3, 0, NodeId("n1"), 2, 1, EntryKind.COMMAND, "command bytes=1")
    }

    @Test
    fun proposeOnNonLeaderIsRejected() {
        val follower = node()
        follower.propose(1, "x") shouldBe listOf(Effect.Rejected(1, Rejection.NotLeader(null)))
        follower.receive(ae(1, n2))
        follower.propose(2, "x") shouldBe listOf(Effect.Rejected(2, Rejection.NotLeader(n2)))
        val candidate = node()
        candidate.tick(10)
        candidate.propose(3, "x") shouldBe listOf(Effect.Rejected(3, Rejection.NotLeader(null)))
        follower.logSnapshotForChecks() shouldBe emptyList()
        candidate.logSnapshotForChecks() shouldBe emptyList()
    }

    @Test
    fun backtracksWithConflictHints() {
        val leader = node(storage = storageWith(6, logWithTerms(1, 1, 1, 4, 4, 5, 5, 6, 6, 6)))
        leader.winElection(n2)
        val log = leader.logSnapshotForChecks()
        leader.nextIndexOf(n2) shouldBe 11L

        leader.receive(aer(7, n2, success = false, conflictIndex = 8)).sent() shouldBe
            listOf(AppendEntries(7, n1, n2, 7, 5, log.subList(7, 11), 0, 0))
        leader.nextIndexOf(n2) shouldBe 8L

        leader.receive(aer(7, n2, success = false, conflictIndex = 4, conflictTerm = 4)).sent() shouldBe
            listOf(AppendEntries(7, n1, n2, 5, 4, log.subList(5, 11), 0, 0))
        leader.nextIndexOf(n2) shouldBe 6L

        leader.receive(aer(7, n2, success = true, matchIndex = 11)).sent() shouldBe emptyList()
        leader.matchIndexOf(n2) shouldBe 11L
        leader.nextIndexOf(n2) shouldBe 12L
        leader.commitIndex shouldBe 11L

        leader.receive(aer(7, n2, success = true, matchIndex = 6)).sent() shouldBe emptyList()
        leader.receive(aer(7, n2, success = true, matchIndex = 11)).sent() shouldBe emptyList()
        leader.matchIndexOf(n2) shouldBe 11L
        leader.nextIndexOf(n2) shouldBe 12L

        leader.receive(aer(7, n2, success = false, conflictIndex = 4, conflictTerm = 4)).sent() shouldBe
            listOf(AppendEntries(7, n1, n2, 11, 7, emptyList(), 11, 0))
        leader.nextIndexOf(n2) shouldBe 12L

        leader.receive(aer(7, n3, success = false, conflictIndex = 7, conflictTerm = 3)).sent() shouldBe
            listOf(AppendEntries(7, n1, n3, 6, 5, log.subList(6, 11), 11, 0))
        leader.nextIndexOf(n3) shouldBe 7L

        leader.receive(aer(7, n3, success = false)).sent() shouldBe
            listOf(AppendEntries(7, n1, n3, 0, 0, log, 11, 0))
        leader.nextIndexOf(n3) shouldBe 1L
        leader.receive(aer(7, n3, success = false)).sent() shouldBe
            listOf(AppendEntries(7, n1, n3, 0, 0, log, 11, 0))
        leader.nextIndexOf(n3) shouldBe 1L

        leader.receive(aer(6, n3, success = true, matchIndex = 11)) shouldBe emptyList<Effect>()
        leader.receive(aer(7, NodeId("x9"), success = true, matchIndex = 11)) shouldBe emptyList<Effect>()
        leader.matchIndexOf(n3) shouldBe 0L
        node().receive(aer(0, n2, success = true, matchIndex = 5)) shouldBe emptyList<Effect>()
    }

    @Test
    fun maxEntriesPerAppendBoundsCatchUp() {
        val cfg = RaftConfig(maxEntriesPerAppend = 2)
        val leader = node(config = cfg, storage = storageWith(1, logWithTerms(1, 1, 1, 1, 1, 1, 1, 1, 1)))
        val follower = node(id = n2, config = cfg)
        val queue = ArrayDeque(leader.winElection(n3).sent().filter { it.to == n2 })
        val sentToFollower = mutableListOf<AppendEntries>()
        for (step in 0 until 1000) {
            if (queue.isEmpty()) break
            val msg = queue.removeFirst()
            if (msg is AppendEntries && msg.to == n2) sentToFollower += msg
            val replies = if (msg.to == n2) follower.receive(msg) else leader.receive(msg)
            queue.addAll(replies.sent().filter { it.to == n1 || it.to == n2 })
        }
        queue.isEmpty() shouldBe true
        sentToFollower.all { it.entries.size <= 2 } shouldBe true
        sentToFollower.count { it.entries.size == 2 } shouldBe 5
        follower.logSnapshotForChecks() shouldBe leader.logSnapshotForChecks()
        leader.matchIndexOf(n2) shouldBe 10L
        leader.commitIndex shouldBe 10L
    }
}
