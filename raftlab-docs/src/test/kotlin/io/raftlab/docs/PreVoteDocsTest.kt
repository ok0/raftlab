package io.raftlab.docs

import io.kotest.matchers.shouldBe
import io.raftlab.core.RaftConfig
import io.raftlab.sim.SimOptions
import io.raftlab.viz.MermaidLint
import io.raftlab.viz.SequenceDiagramExporter
import io.raftlab.viz.StateDiagramExporter
import org.junit.jupiter.api.Test

internal class PreVoteDocsTest {
    private fun legacy(name: String): String =
        checkNotNull(PreVoteDocsTest::class.java.getResource("/pre-m16/$name")) { "missing pre-m16/$name" }.readText()

    private fun mermaidBlock(text: String): String {
        val start = text.indexOf("```mermaid\n") + "```mermaid\n".length
        val end = text.indexOf("```\n", start)
        return text.substring(start, end)
    }

    @Test
    fun preVoteOffRendersThePreM16Documents() {
        val run =
            ElectionDocRun.normalElection(
                DocEntries.SEED,
                options = SimOptions(raftConfig = RaftConfig(preVote = false)),
            )
        legacy("election.md")
            .contains(
                SequenceDiagramExporter()
                    .export(run.events, window = run.window, maxMessages = DocEntries.MAX_MESSAGES)
                    .trimEnd(),
            ) shouldBe true
        legacy("election.md").contains("at ${run.window.last} ms") shouldBe true
        legacy("roles-election.md").contains(StateDiagramExporter().export(run.events).trimEnd()) shouldBe true
    }

    @Test
    fun defaultDocsShowPreVoteRound() {
        val files = DocsGenerator.generate()
        val election = files.single { it.path == "election.md" }.content
        election.contains("preVote=true") shouldBe true
        MermaidLint.lint(mermaidBlock(election)) shouldBe emptyList()
        val roles = files.single { it.path == "roles-election.md" }.content
        roles.contains("PreCandidate") shouldBe true
    }
}
