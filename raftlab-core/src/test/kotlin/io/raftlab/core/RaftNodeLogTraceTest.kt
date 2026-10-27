package io.raftlab.core

import io.kotest.matchers.shouldBe
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.EntryApplied
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.LogTruncated
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import org.junit.jupiter.api.Test

internal class RaftNodeLogTraceTest {
    @Test
    fun leaderReplicateAndCommitIsTraced() {
        val rec = recorder()
        val l = node(trace = rec, config = RaftConfig(preVote = false))
        l.winElection(n2)
        l.propose(1, "a")
        l.receive(aer(1, n2, success = true, matchIndex = 2))
        rec.events shouldBe
            listOf(
                RoleChanged(0, 0, n1, Role.FOLLOWER, Role.CANDIDATE, 1, "election timeout"),
                RoleChanged(1, 0, n1, Role.CANDIDATE, Role.LEADER, 1, "won election"),
                LogAppended(2, 0, n1, 1, 1, EntryKind.NOOP, "noop"),
                LogAppended(3, 0, n1, 2, 1, EntryKind.COMMAND, "command bytes=1"),
                CommitAdvanced(4, 0, n1, 2),
                EntryApplied(5, 0, n1, 1),
                EntryApplied(6, 0, n1, 2),
            )
    }

    @Test
    fun followerTruncateAppendCommitIsTraced() {
        val rec = recorder()
        val f = node(id = n2, storage = storageWith(1, listOf(noop(1, 1), cmd(2, 1, "x"))), trace = rec)
        f.receive(aeWith(2, n1, n2, 1, 1, listOf(cmd(2, 2, "y")), 2))
        rec.events shouldBe
            listOf(
                LogTruncated(0, 0, n2, 2),
                LogAppended(1, 0, n2, 2, 2, EntryKind.COMMAND, "command bytes=1"),
                CommitAdvanced(2, 0, n2, 2),
                EntryApplied(3, 0, n2, 1),
                EntryApplied(4, 0, n2, 2),
            )
    }
}
