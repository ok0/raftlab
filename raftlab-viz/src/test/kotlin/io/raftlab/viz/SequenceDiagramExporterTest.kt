package io.raftlab.viz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.DropReason
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.Marker
import io.raftlab.trace.MessageDelivered
import io.raftlab.trace.MessageDropped
import io.raftlab.trace.MessageDuplicated
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceEvent
import io.raftlab.trace.VoteCast
import org.junit.jupiter.api.Test

class SequenceDiagramExporterTest {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")
    private val n3 = NodeId("n3")
    private val hostile = "a\"b: c; d # e\n--> f [g] {h} <i> |j| `k`"

    private val trace: List<TraceEvent> =
        listOf(
            RoleChanged(0, 100, n1, Role.FOLLOWER, Role.CANDIDATE, 1, "election timeout"),
            MessageSent(1, 100, n1, 1, n1, n2, MessageKind.REQUEST_VOTE, 1, "lastLog=0/0"),
            MessageSent(2, 100, n1, 2, n1, n3, MessageKind.REQUEST_VOTE, 1, "lastLog=0/0"),
            MessageDropped(3, 100, n1, 2, DropReason.PARTITION),
            MessageDelivered(4, 105, n2, 1),
            MessageSent(5, 105, n2, 3, n2, n1, MessageKind.REQUEST_VOTE_RESPONSE, 1, "granted"),
            MessageDuplicated(6, 105, n2, 3, 4),
            MessageDelivered(7, 110, n1, 3),
            RoleChanged(8, 110, n1, Role.CANDIDATE, Role.LEADER, 1, "won election"),
            MessageDelivered(9, 112, n1, 4),
            MessageSent(10, 120, n1, 5, n1, n2, MessageKind.APPEND_ENTRIES, 1, "prev=0/0 entries=1"),
            MessageDelivered(11, 125, n2, 5),
            MessageSent(12, 125, n2, 6, n2, n1, MessageKind.APPEND_ENTRIES_RESPONSE, 1, "ok match=1"),
            MessageDelivered(13, 130, n1, 6),
            CommitAdvanced(14, 130, n1, 1),
            MessageSent(15, 130, n1, 7, n1, n3, MessageKind.APPEND_ENTRIES, 1, "prev=0/0 entries=1"),
            NodeCrashed(16, 140, n3),
            NodeRestarted(17, 150, n3),
            VoteCast(18, 150, n2, 1, n1, true),
            Marker(19, 150, null, "end"),
        )

    @Test
    fun goldenFullTrace() {
        val expected =
            """
            sequenceDiagram
                participant P0 as n1
                participant P1 as n2
                participant P2 as n3
                Note over P0: Follower to Candidate T1 (election timeout)
                P0->>P1: RequestVote T1 lastLog=0/0
                P0-xP2: RequestVote T1 lastLog=0/0 (dropped partition)
                P1->>P0: RequestVoteResponse T1 granted
                P1->>P0: RequestVoteResponse T1 granted (duplicate)
                Note over P0: Candidate to Leader T1 (won election)
                P0->>P1: AppendEntries T1 prev=0/0 entries=1
                P1->>P0: AppendEntriesResponse T1 ok match=1
                Note over P0: commit index 1
                P0->>P2: AppendEntries T1 prev=0/0 entries=1 (in flight)
                Note over P2: crashed
                Note over P2: restarted
            """.trimIndent() + "\n"
        val out = SequenceDiagramExporter().export(trace)
        out shouldBe expected
        MermaidLint.lint(out) shouldBe emptyList()
        SequenceDiagramExporter().export(trace) shouldBe expected
        SequenceDiagramExporter().export(trace) shouldBe out
    }

    @Test
    fun nodesFilter() {
        val withPair =
            """
            sequenceDiagram
                participant P0 as n1
                participant P1 as n2
                Note over P0: Follower to Candidate T1 (election timeout)
                P0->>P1: RequestVote T1 lastLog=0/0
                P1->>P0: RequestVoteResponse T1 granted
                P1->>P0: RequestVoteResponse T1 granted (duplicate)
                Note over P0: Candidate to Leader T1 (won election)
                P0->>P1: AppendEntries T1 prev=0/0 entries=1
                P1->>P0: AppendEntriesResponse T1 ok match=1
                Note over P0: commit index 1
            """.trimIndent() + "\n"
        val out = SequenceDiagramExporter().export(trace, nodes = setOf(n1, n2))
        out shouldBe withPair
        MermaidLint.lint(out) shouldBe emptyList()

        val crashOnly =
            """
            sequenceDiagram
                participant P0 as n2
                participant P1 as n3
                Note over P1: crashed
                Note over P1: restarted
            """.trimIndent() + "\n"
        val out2 = SequenceDiagramExporter().export(trace, nodes = setOf(n2, n3))
        out2 shouldBe crashOnly
        MermaidLint.lint(out2) shouldBe emptyList()
    }

    @Test
    fun kindsFilter() {
        val expected =
            """
            sequenceDiagram
                participant P0 as n1
                participant P1 as n2
                participant P2 as n3
                Note over P0: Follower to Candidate T1 (election timeout)
                Note over P0: Candidate to Leader T1 (won election)
                P0->>P1: AppendEntries T1 prev=0/0 entries=1
                Note over P0: commit index 1
                P0->>P2: AppendEntries T1 prev=0/0 entries=1 (in flight)
                Note over P2: crashed
                Note over P2: restarted
            """.trimIndent() + "\n"
        val out = SequenceDiagramExporter().export(trace, kinds = setOf(MessageKind.APPEND_ENTRIES))
        out shouldBe expected
        MermaidLint.lint(out) shouldBe emptyList()
    }

    @Test
    fun windowFilterIsInclusive() {
        val inclusive =
            """
            sequenceDiagram
                participant P0 as n1
                participant P1 as n2
                participant P2 as n3
                P1->>P0: RequestVoteResponse T1 granted
                P1->>P0: RequestVoteResponse T1 granted (duplicate)
                Note over P0: Candidate to Leader T1 (won election)
                P0->>P1: AppendEntries T1 prev=0/0 entries=1
                P1->>P0: AppendEntriesResponse T1 ok match=1
                Note over P0: commit index 1
                P0->>P2: AppendEntries T1 prev=0/0 entries=1 (in flight)
            """.trimIndent() + "\n"
        val out = SequenceDiagramExporter().export(trace, window = 105L..130L)
        out shouldBe inclusive
        MermaidLint.lint(out) shouldBe emptyList()

        val narrow =
            """
            sequenceDiagram
                participant P0 as n1
                participant P1 as n2
                Note over P0: Candidate to Leader T1 (won election)
                P0->>P1: AppendEntries T1 prev=0/0 entries=1
                P1->>P0: AppendEntriesResponse T1 ok match=1
            """.trimIndent() + "\n"
        val out2 = SequenceDiagramExporter().export(trace, window = 106L..129L)
        out2 shouldBe narrow
        MermaidLint.lint(out2) shouldBe emptyList()
    }

    @Test
    fun combinedFiltersAnd() {
        val expected =
            """
            sequenceDiagram
                participant P0 as n1
                participant P1 as n2
                Note over P0: Follower to Candidate T1 (election timeout)
                P0->>P1: RequestVote T1 lastLog=0/0
                Note over P0: Candidate to Leader T1 (won election)
                P0->>P1: AppendEntries T1 prev=0/0 entries=1
            """.trimIndent() + "\n"
        val out =
            SequenceDiagramExporter().export(
                trace,
                window = 100L..120L,
                nodes = setOf(n1, n2),
                kinds = setOf(MessageKind.REQUEST_VOTE, MessageKind.APPEND_ENTRIES),
            )
        out shouldBe expected
        MermaidLint.lint(out) shouldBe emptyList()
    }

    @Test
    fun maxMessagesTruncates() {
        val truncated =
            """
            sequenceDiagram
                participant P0 as n1
                participant P1 as n2
                participant P2 as n3
                Note over P0: Follower to Candidate T1 (election timeout)
                P0->>P1: RequestVote T1 lastLog=0/0
                P0-xP2: RequestVote T1 lastLog=0/0 (dropped partition)
                P1->>P0: RequestVoteResponse T1 granted
                Note over P0,P2: 4 more messages omitted (limit 3)
            """.trimIndent() + "\n"
        val out = SequenceDiagramExporter().export(trace, maxMessages = 3)
        out shouldBe truncated
        MermaidLint.lint(out) shouldBe emptyList()
        SequenceDiagramExporter().export(trace, maxMessages = 7) shouldBe SequenceDiagramExporter().export(trace)
        shouldThrow<IllegalArgumentException> { SequenceDiagramExporter().export(trace, maxMessages = 0) }
        shouldThrow<IllegalArgumentException> { SequenceDiagramExporter().export(trace, maxMessages = -1) }
    }

    @Test
    fun emptyAndUnrenderedTraces() {
        SequenceDiagramExporter().export(emptyList()) shouldBe "sequenceDiagram\n"
        val unrendered =
            listOf(
                VoteCast(0, 0, n1, 1, n2, true),
                LogAppended(1, 0, n1, 1, 1, EntryKind.COMMAND, "x"),
                Marker(2, 0, null, "m"),
                RoleChanged(3, 0, null, Role.FOLLOWER, Role.LEADER, 1, "r"),
                MessageDelivered(4, 0, n1, 99),
            )
        val out = SequenceDiagramExporter().export(unrendered)
        out shouldBe "sequenceDiagram\n"
        MermaidLint.lint(out) shouldBe emptyList()
    }

    @Test
    fun hostileTextIsSanitized() {
        val a = NodeId("n:1;\"x")
        val b = NodeId("n 2#")
        val events =
            listOf(
                RoleChanged(0, 10, a, Role.FOLLOWER, Role.CANDIDATE, 1, hostile),
                MessageSent(1, 10, a, 1, a, b, MessageKind.REQUEST_VOTE, 1, hostile),
                MessageDropped(2, 11, a, 1, DropReason.RANDOM),
            )
        val expected =
            """
            sequenceDiagram
                participant P0 as n_2_
                participant P1 as n_1__x
                Note over P1: Follower to Candidate T1 (a_b_ c_ d _ e --_ f _g_ _h_ _i_ _j_ _k_)
                P1-xP0: RequestVote T1 a_b_ c_ d _ e --_ f _g_ _h_ _i_ _j_ _k_ (dropped random)
            """.trimIndent() + "\n"
        val out = SequenceDiagramExporter().export(events)
        out shouldBe expected
        MermaidLint.lint(out) shouldBe emptyList()
    }

    @Test
    fun doesNotMutateInputAndIgnoresOrder() {
        val input = trace.toMutableList()
        val before = input.toList()
        SequenceDiagramExporter().export(input)
        input shouldBe before
        SequenceDiagramExporter().export(trace.reversed()) shouldBe SequenceDiagramExporter().export(trace)
        val unordered =
            SequenceDiagramExporter().export(
                trace,
                nodes = linkedSetOf(n2, n1),
                kinds = linkedSetOf(MessageKind.APPEND_ENTRIES, MessageKind.REQUEST_VOTE),
            )
        val ordered =
            SequenceDiagramExporter().export(
                trace,
                nodes = linkedSetOf(n1, n2),
                kinds = linkedSetOf(MessageKind.REQUEST_VOTE, MessageKind.APPEND_ENTRIES),
            )
        unordered shouldBe ordered
    }
}
