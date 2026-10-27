package io.raftlab.viz

import io.kotest.matchers.shouldBe
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.Marker
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.NodeId
import io.raftlab.trace.NodeRestarted
import io.raftlab.trace.NodeStarted
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceEvent
import io.raftlab.trace.VoteCast
import org.junit.jupiter.api.Test

class StateDiagramExporterTest {
    private val n1 = NodeId("n1")
    private val n2 = NodeId("n2")
    private val n3 = NodeId("n3")
    private val n9 = NodeId("n9")

    private val hostile = "a\"b: c; d # e\n--> f [g] {h} <i> |j| `k`"

    private val trace: List<TraceEvent> =
        listOf(
            NodeStarted(0, 0, n1),
            NodeStarted(1, 0, n2),
            NodeStarted(2, 0, n3),
            RoleChanged(3, 150, n1, Role.FOLLOWER, Role.PRE_CANDIDATE, 0, "election timeout"),
            RoleChanged(4, 152, n1, Role.PRE_CANDIDATE, Role.CANDIDATE, 1, "pre-vote granted"),
            VoteCast(5, 155, n2, 1, n1, true),
            RoleChanged(6, 160, n1, Role.CANDIDATE, Role.LEADER, 1, "won election"),
            Marker(7, 200, null, "crash n1"),
            NodeCrashed(8, 200, n1),
            RoleChanged(9, 430, n3, Role.FOLLOWER, Role.PRE_CANDIDATE, 1, "election timeout"),
            RoleChanged(10, 432, n3, Role.PRE_CANDIDATE, Role.CANDIDATE, 2, "pre-vote granted"),
            RoleChanged(11, 440, n3, Role.CANDIDATE, Role.LEADER, 2, "won election"),
            NodeRestarted(12, 600, n1),
            RoleChanged(13, 610, n1, Role.LEADER, Role.FOLLOWER, 2, "higher term"),
        )

    private val goldenElectionAndLeaderChange =
        """
        stateDiagram-v2
            state "n1" as node0 {
                state "Follower" as node0_Follower
                state "PreCandidate" as node0_PreCandidate
                state "Candidate" as node0_Candidate
                state "Leader" as node0_Leader
                [*] --> node0_Follower
                node0_Follower --> node0_PreCandidate : #1 T0 @150ms election timeout
                node0_PreCandidate --> node0_Candidate : #2 T1 @152ms pre-vote granted
                node0_Candidate --> node0_Leader : #3 T1 @160ms won election
                node0_Leader --> node0_Follower : #4 T2 @610ms higher term
            }
            state "n2" as node1 {
                state "no role changes" as node1_none
            }
            state "n3" as node2 {
                state "Follower" as node2_Follower
                state "PreCandidate" as node2_PreCandidate
                state "Candidate" as node2_Candidate
                state "Leader" as node2_Leader
                [*] --> node2_Follower
                node2_Follower --> node2_PreCandidate : #1 T1 @430ms election timeout
                node2_PreCandidate --> node2_Candidate : #2 T2 @432ms pre-vote granted
                node2_Candidate --> node2_Leader : #3 T2 @440ms won election
            }
        """.trimIndent() + "\n"

    @Test
    fun goldenElectionAndLeaderChange() {
        val out = StateDiagramExporter().export(trace)
        out shouldBe goldenElectionAndLeaderChange
        StateDiagramExporter().export(trace) shouldBe out
        MermaidLint.lint(out) shouldBe emptyList()
    }

    @Test
    fun nodesFilterAndListedNodeWithoutEvents() {
        val expected =
            """
            stateDiagram-v2
                state "n3" as node0 {
                    state "Follower" as node0_Follower
                    state "PreCandidate" as node0_PreCandidate
                    state "Candidate" as node0_Candidate
                    state "Leader" as node0_Leader
                    [*] --> node0_Follower
                    node0_Follower --> node0_PreCandidate : #1 T1 @430ms election timeout
                    node0_PreCandidate --> node0_Candidate : #2 T2 @432ms pre-vote granted
                    node0_Candidate --> node0_Leader : #3 T2 @440ms won election
                }
                state "n9" as node1 {
                    state "no role changes" as node1_none
                }
            """.trimIndent() + "\n"
        val out = StateDiagramExporter().export(trace, setOf(n3, n9))
        out shouldBe expected
        MermaidLint.lint(out) shouldBe emptyList()
    }

    @Test
    fun emptyAndUnrenderedTraces() {
        val empty = StateDiagramExporter().export(emptyList())
        empty shouldBe "stateDiagram-v2\n"
        MermaidLint.lint(empty) shouldBe emptyList()

        val unrendered =
            listOf(
                VoteCast(0, 0, n1, 1, n2, true),
                LogAppended(1, 0, n1, 1, 1, EntryKind.COMMAND, "x"),
                Marker(2, 0, null, "m"),
                RoleChanged(3, 0, null, Role.FOLLOWER, Role.LEADER, 1, "r"),
            )
        val out = StateDiagramExporter().export(unrendered)
        out shouldBe "stateDiagram-v2\n"
        MermaidLint.lint(out) shouldBe emptyList()
    }

    @Test
    fun hostileTextIsSanitized() {
        val a = NodeId("n:1;\"x")
        val b = NodeId("n 2#")
        val out =
            StateDiagramExporter().export(
                listOf(
                    RoleChanged(0, 10, a, Role.FOLLOWER, Role.LEADER, 3, hostile),
                    NodeStarted(1, 10, b),
                ),
            )
        val expected =
            """
            stateDiagram-v2
                state "n_2_" as node0 {
                    state "no role changes" as node0_none
                }
                state "n_1__x" as node1 {
                    state "Follower" as node1_Follower
                    state "Leader" as node1_Leader
                    [*] --> node1_Follower
                    node1_Follower --> node1_Leader : #1 T3 @10ms a_b_ c_ d _ e --_ f _g_ _h_ _i_ _j_ _k_
                }
            """.trimIndent() + "\n"
        out shouldBe expected
        MermaidLint.lint(out) shouldBe emptyList()
    }

    @Test
    fun doesNotMutateInputAndIgnoresOrder() {
        val exporter = StateDiagramExporter()
        val input = trace.toMutableList()
        val before = input.toList()
        exporter.export(input)
        input shouldBe before
        exporter.export(trace.reversed()) shouldBe exporter.export(trace)
        exporter.export(trace, linkedSetOf(n3, n1)) shouldBe exporter.export(trace, linkedSetOf(n1, n3))
    }
}
