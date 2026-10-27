package io.raftlab.viz

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MermaidLintTest {
    private val pool = "abcXYZ019 _-<>[]{}()|:;#\"'`%\n\t\r=.*@!?/\\"

    private fun assertLines(
        text: String,
        expected: List<Int>,
    ) {
        val errors = MermaidLint.lint(text)
        errors.map { it.line } shouldBe expected
        errors.all { it.message.isNotBlank() } shouldBe true
    }

    @Test
    fun acceptsHeaderOnlyDiagrams() {
        MermaidLint.lint("stateDiagram-v2\n") shouldBe emptyList()
        MermaidLint.lint("sequenceDiagram\n") shouldBe emptyList()
        MermaidLint.lint("flowchart LR\n") shouldBe emptyList()
        MermaidLint.lint("\n  sequenceDiagram\r\n    participant P0\r\n") shouldBe emptyList()
    }

    @Test
    fun acceptsStateSubset() {
        val s =
            "stateDiagram-v2\n" +
                "    state \"n1\" as node0 {\n" +
                "        state \"Follower\" as node0_Follower\n" +
                "        [*] --> node0_Follower\n" +
                "        node0_Follower --> node0_Follower : #1 T1 @5ms ok\n" +
                "    }\n" +
                "    state node1 {\n" +
                "    }\n"
        MermaidLint.lint(s) shouldBe emptyList()
    }

    @Test
    fun acceptsSequenceSubset() {
        val s =
            "sequenceDiagram\n" +
                "    participant P0 as n1\n" +
                "    participant P1\n" +
                "    P0->>P1: AppendEntries T1 (in flight)\n" +
                "    P1-xP0: x (dropped random)\n" +
                "    P0-->>P1: y\n" +
                "    P0--xP1: z\n" +
                "    Note over P0,P1: 2 more messages omitted (limit 3)\n" +
                "    Note left of P0: hi\n" +
                "    %% comment\n\n"
        MermaidLint.lint(s) shouldBe emptyList()
    }

    @Test
    fun acceptsFlowchartSubset() {
        val s =
            "flowchart TB\n" +
                "    subgraph g1[\"Group 1\"]\n" +
                "        direction LR\n" +
                "        a[\"Node A\"]\n" +
                "        b[\"Node B\"]\n" +
                "    end\n" +
                "    a --> b\n" +
                "    a -->|calls| b\n" +
                "    a -.-> b\n"
        MermaidLint.lint(s) shouldBe emptyList()
    }

    @Test
    fun rejectsEmptyText() {
        assertLines("", listOf(1))
        assertLines("  \n \n", listOf(1))
    }

    @Test
    fun rejectsUnknownHeader() {
        assertLines("graph TD\nA-->B\n", listOf(1))
        assertLines("\n\nflowchart XY\n", listOf(3))
    }

    @Test
    fun rejectsUnbalancedStateBraces() {
        assertLines("stateDiagram-v2\n    state \"n1\" as node0 {\n        [*] --> node0_a\n", listOf(2))
        assertLines("stateDiagram-v2\n}\n", listOf(2))
    }

    @Test
    fun rejectsUnbalancedSubgraph() {
        assertLines("flowchart LR\n    subgraph g1\n    a --> b\n", listOf(2))
        assertLines("flowchart LR\n    end\n", listOf(2))
    }

    @Test
    fun rejectsInvalidArrow() {
        assertLines("sequenceDiagram\n    participant P0\n    participant P1\n    P0-->>>P1: hi\n", listOf(4))
        assertLines("stateDiagram-v2\n    a -> b\n", listOf(2))
    }

    @Test
    fun rejectsBreakingCharacterInLabel() {
        assertLines("sequenceDiagram\n    participant P0\n    P0->>P0: a;b\n", listOf(3))
        assertLines("sequenceDiagram\n    participant P0\n    Note over P0: x # y\n", listOf(3))
        assertLines("stateDiagram-v2\n    a --> b : x: y\n", listOf(2))
    }

    @Test
    fun rejectsInvalidIdentifierAndEmptyText() {
        assertLines("sequenceDiagram\n    participant 9lives\n", listOf(2))
        assertLines("stateDiagram-v2\n    state \"x\" as a-b\n", listOf(2))
        assertLines("sequenceDiagram\n    participant P0\n    P0->>P0:\n", listOf(3))
    }

    @Test
    fun neverThrowsOnGarbage() {
        val rnd = kotlin.random.Random(7)
        repeat(500) {
            val s =
                buildString {
                    repeat(rnd.nextInt(0, 200)) {
                        if (rnd.nextInt(10) == 0) {
                            append(Char(rnd.nextInt(0, 0xD800)))
                        } else {
                            append(pool[rnd.nextInt(pool.length)])
                        }
                    }
                }
            MermaidLint.lint(s)
            MermaidLint.lint("sequenceDiagram\n" + s)
            MermaidLint.lint("stateDiagram-v2\n" + s)
            MermaidLint.lint("flowchart LR\n" + s)
        }
    }
}
