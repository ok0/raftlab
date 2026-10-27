package io.raftlab.viz

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.trace.LeadershipTransfer
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceEvent
import org.junit.jupiter.api.Test

internal class PreVoteTransferExportTest {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")

    private val trace: List<TraceEvent> =
        listOf(
            RoleChanged(0, 100, n1, Role.FOLLOWER, Role.PRE_CANDIDATE, 0, "election timeout"),
            MessageSent(
                1,
                100,
                n1,
                1,
                n1,
                n2,
                MessageKind.REQUEST_VOTE,
                1,
                "lastLogIndex=0 lastLogTerm=0 preVote=true",
            ),
            MessageDelivered(2, 102, n2, 1),
            MessageSent(3, 102, n2, 2, n2, n1, MessageKind.REQUEST_VOTE_RESPONSE, 1, "granted=true preVote=true"),
            MessageDelivered(4, 104, n1, 2),
            RoleChanged(5, 104, n1, Role.PRE_CANDIDATE, Role.CANDIDATE, 1, "pre-vote granted"),
            RoleChanged(6, 110, n1, Role.CANDIDATE, Role.LEADER, 1, "won election"),
            LeadershipTransfer(7, 200, n1, n2),
            MessageSent(8, 200, n1, 3, n1, n2, MessageKind.TIMEOUT_NOW, 1, ""),
            MessageDelivered(9, 201, n2, 3),
            RoleChanged(10, 201, n2, Role.FOLLOWER, Role.CANDIDATE, 2, "leadership transfer"),
        )

    @Test
    fun sequenceDiagramIsLintCleanAndShowsPreVoteAndTransfer() {
        val out = SequenceDiagramExporter().export(trace)
        MermaidLint.lint(out) shouldBe emptyList()
        out shouldContain "Note over P0: Follower to PreCandidate T0 (election timeout)"
        out shouldContain "P0->>P1: RequestVote T1 lastLogIndex=0 lastLogTerm=0 preVote=true"
        out shouldContain "P1->>P0: RequestVoteResponse T1 granted=true preVote=true"
        out shouldContain "Note over P0: leadership transfer to n2"
        out shouldContain "P0->>P1: TimeoutNow T1"
    }

    @Test
    fun stateDiagramIsLintCleanWithPreCandidate() {
        val out = StateDiagramExporter().export(trace)
        MermaidLint.lint(out) shouldBe emptyList()
        out shouldContain "state \"PreCandidate\" as node0_PreCandidate"
        out shouldContain "node0_PreCandidate --> node0_Candidate"
    }
}
