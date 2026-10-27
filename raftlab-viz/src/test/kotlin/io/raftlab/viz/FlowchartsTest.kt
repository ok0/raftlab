package io.raftlab.viz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.raftlab.trace.ClientInvoked
import io.raftlab.trace.CommitAdvanced
import io.raftlab.trace.EntryKind
import io.raftlab.trace.LogAppended
import io.raftlab.trace.MessageKind
import io.raftlab.trace.MessageSent
import io.raftlab.trace.NodeId
import io.raftlab.trace.TraceEvent
import org.junit.jupiter.api.Test

internal class FlowchartsTest {
    private val graph =
        ModuleGraph(
            modules =
                listOf(
                    ModuleGraph.Module("raftlab-docs", 2),
                    ModuleGraph.Module("raftlab-viz", 2),
                    ModuleGraph.Module("raftlab-sim", 3),
                    ModuleGraph.Module("raftlab-trace", 6),
                ),
            edges =
                listOf(
                    ModuleGraph.Edge("raftlab-docs", "raftlab-sim"),
                    ModuleGraph.Edge("raftlab-docs", "raftlab-viz"),
                    ModuleGraph.Edge("raftlab-sim", "raftlab-trace"),
                    ModuleGraph.Edge("raftlab-viz", "raftlab-trace"),
                ),
        )

    private val graphJson =
        """
        {
          "modules": [
            {"name": "raftlab-docs", "layer": 2},
            {"name": "raftlab-viz", "layer": 2},
            {"name": "raftlab-sim", "layer": 3},
            {"name": "raftlab-trace", "layer": 6}
          ],
          "edges": [
            {"from": "raftlab-docs", "to": "raftlab-sim"},
            {"from": "raftlab-docs", "to": "raftlab-viz"},
            {"from": "raftlab-sim", "to": "raftlab-trace"},
            {"from": "raftlab-viz", "to": "raftlab-trace"}
          ]
        }
        """.trimIndent()

    private fun msg(
        seq: Long,
        time: Long,
        from: String,
        to: String,
        kind: MessageKind,
    ): TraceEvent = MessageSent(seq, time, NodeId(from), seq, NodeId(from), NodeId(to), kind, 1L, "")

    private val clientTrace: List<TraceEvent> =
        listOf(
            LogAppended(0, 100, NodeId("n1"), 1, 1, EntryKind.NOOP, "noop"),
            ClientInvoked(1, 200, null, "c1", 1, "Put(a,1)"),
            LogAppended(2, 201, NodeId("n1"), 2, 1, EntryKind.COMMAND, "Put(a,1)"),
            msg(3, 201, "n1", "n2", MessageKind.APPEND_ENTRIES),
            msg(4, 201, "n1", "n3", MessageKind.APPEND_ENTRIES),
            msg(5, 202, "n1", "n2", MessageKind.APPEND_ENTRIES),
            msg(6, 205, "n2", "n1", MessageKind.APPEND_ENTRIES_RESPONSE),
            CommitAdvanced(7, 206, NodeId("n1"), 2),
            msg(8, 207, "n3", "n1", MessageKind.APPEND_ENTRIES_RESPONSE),
        )

    private fun assertLintClean(text: String) {
        MermaidLint.lint(text) shouldBe emptyList()
    }

    @Test
    fun builderGoldenWithHostileLabels() {
        val out =
            FlowchartBuilder(FlowchartBuilder.Direction.LR)
                .subgraph("c", "Cluster")
                .node("a", "Node A", "c")
                .node("b", "x\"y[z]|w:-v q")
                .edge("a", "b")
                .edge("b", "a", "Append:Entries|x")
                .render()
        out shouldBe
            "flowchart LR\n" +
            "    subgraph g0[\"Cluster\"]\n" +
            "        n0[\"Node A\"]\n" +
            "    end\n" +
            "    n1[\"x_y_z__w_-v q\"]\n" +
            "    n0 --> n1\n" +
            "    n1 -->|Append_Entries_x| n0\n"
        assertLintClean(out)
    }

    @Test
    fun blankLabelsBecomeUnderscore() {
        val out =
            FlowchartBuilder()
                .node("a", "   ")
                .node("b", "b")
                .edge("a", "b", "")
                .render()
        out shouldBe "flowchart TB\n    n0[\"_\"]\n    n1[\"b\"]\n    n0 -->|_| n1\n"
        assertLintClean(out)
    }

    @Test
    fun builderRejectsUnknownAndDuplicateKeys() {
        shouldThrow<IllegalArgumentException> {
            FlowchartBuilder().node("a", "A").node("a", "A again")
        }
        shouldThrow<IllegalArgumentException> {
            FlowchartBuilder().node("a", "A").edge("a", "missing")
        }
        shouldThrow<IllegalArgumentException> {
            FlowchartBuilder().node("a", "A", "missing")
        }
    }

    @Test
    fun dependenciesGolden() {
        val out = ModuleGraphRenderer.dependencies(graph)
        out shouldBe
            "flowchart TB\n" +
            "    subgraph g0[\"Layer 2\"]\n" +
            "        n0[\"raftlab-docs\"]\n" +
            "        n1[\"raftlab-viz\"]\n" +
            "    end\n" +
            "    subgraph g1[\"Layer 3\"]\n" +
            "        n2[\"raftlab-sim\"]\n" +
            "    end\n" +
            "    subgraph g2[\"Layer 6\"]\n" +
            "        n3[\"raftlab-trace\"]\n" +
            "    end\n" +
            "    n0 --> n2\n" +
            "    n0 --> n1\n" +
            "    n2 --> n3\n" +
            "    n1 --> n3\n"
        assertLintClean(out)
    }

    @Test
    fun overviewGolden() {
        val out = ModuleGraphRenderer.overview(graph)
        out shouldBe
            "flowchart TB\n" +
            "    n0[\"Layer 2 (raftlab-docs, raftlab-viz)\"]\n" +
            "    n1[\"Layer 3 (raftlab-sim)\"]\n" +
            "    n2[\"Layer 6 (raftlab-trace)\"]\n" +
            "    n0 --> n1\n" +
            "    n0 --> n2\n" +
            "    n1 --> n2\n"
        assertLintClean(out)
    }

    @Test
    fun changedGraphChangesOutput() {
        val changed =
            ModuleGraph(
                graph.modules + ModuleGraph.Module("raftlab-extra", 4),
                graph.edges + ModuleGraph.Edge("raftlab-docs", "raftlab-extra"),
            )
        ModuleGraphRenderer.dependencies(changed) shouldNotBe ModuleGraphRenderer.dependencies(graph)
        ModuleGraphRenderer.overview(changed) shouldNotBe ModuleGraphRenderer.overview(graph)
    }

    @Test
    fun moduleGraphFromJson() {
        ModuleGraph.fromJson(graphJson) shouldBe graph
        val unknownEdge =
            graphJson.replace(
                "{\"from\": \"raftlab-viz\", \"to\": \"raftlab-trace\"}",
                "{\"from\": \"raftlab-viz\", \"to\": \"raftlab-nope\"}",
            )
        shouldThrow<IllegalArgumentException> { ModuleGraph.fromJson(unknownEdge) }
    }

    @Test
    fun clientWriteGolden() {
        val out = MessageFlowRenderer.clientWrite(clientTrace)
        out shouldBe
            "flowchart LR\n" +
            "    subgraph g0[\"Cluster\"]\n" +
            "        n1[\"n1\"]\n" +
            "        n2[\"n2\"]\n" +
            "        n3[\"n3\"]\n" +
            "    end\n" +
            "    n0[\"Client\"]\n" +
            "    n0 -->|ClientRequest| n1\n" +
            "    n1 -->|AppendEntries| n2\n" +
            "    n1 -->|AppendEntries| n3\n" +
            "    n2 -->|AppendEntriesResponse| n1\n" +
            "    n1 -->|ClientReply| n0\n"
        assertLintClean(out)
        MessageFlowRenderer.clientWrite(clientTrace.reversed()) shouldBe out
    }

    @Test
    fun clientWriteWithoutCommandThrows() {
        val noCommand: List<TraceEvent> =
            listOf(LogAppended(0, 100, NodeId("n1"), 1, 1, EntryKind.NOOP, "noop"))
        shouldThrow<IllegalArgumentException> { MessageFlowRenderer.clientWrite(noCommand) }
    }
}
