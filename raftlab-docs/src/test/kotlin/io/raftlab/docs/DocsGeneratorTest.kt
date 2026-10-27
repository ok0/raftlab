package io.raftlab.docs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.viz.MermaidLint
import io.raftlab.viz.ModuleGraph
import io.raftlab.viz.StateDiagramExporter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocsGeneratorTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun entriesAreInOrder() {
        DocsGenerator.generate(DocsFixtures.graph).map { it.path } shouldBe
            listOf(
                "election.md",
                "roles-election.md",
                "replication.md",
                "roles-leader-crash.md",
                "partition-heal-log.md",
                "architecture.md",
                "modules.md",
                "index.md",
            )
    }

    @Test
    fun filesHaveHeaderTitleDescriptionAndExpectedBlocks() {
        val files = DocsGenerator.generate(DocsFixtures.graph)
        val expectedBlocks =
            mapOf(
                "election.md" to 1,
                "roles-election.md" to 1,
                "replication.md" to 1,
                "roles-leader-crash.md" to 1,
                "partition-heal-log.md" to 0,
                "architecture.md" to 2,
                "modules.md" to 1,
                "index.md" to 0,
            )
        val unseeded = setOf("modules.md", "index.md")
        for (f in files) {
            val content = f.content
            content.startsWith(DocsGenerator.HEADER + "\n# ") shouldBe true
            val lines = content.split("\n")
            lines[2] shouldBe ""
            lines[3].isNotBlank() shouldBe true
            lines[4] shouldBe ""
            Regex("```mermaid\n").findAll(content).count() shouldBe expectedBlocks.getValue(f.path)
            ('\r' in content) shouldBe false
            content.endsWith("\n") shouldBe true
            content.endsWith("\n\n") shouldBe false
            if (f.path !in unseeded) {
                Regex("seed \\d+").containsMatchIn(lines[3]) shouldBe true
            }
        }
        files.first { it.path == "election.md" }.content shouldContain "seed 42"
        files.first { it.path == "election.md" }.content shouldContain "```mermaid\nsequenceDiagram\n"
        files.first { it.path == "roles-election.md" }.content shouldContain "```mermaid\nstateDiagram-v2\n"
    }

    @Test
    fun generationIsByteIdenticalAcrossRuns() {
        DocsGenerator.write(dir.resolve("a"), DocsFixtures.graph, DocsFixtures.readme(dir.resolve("ra")))
        DocsGenerator.write(dir.resolve("b"), DocsFixtures.graph, DocsFixtures.readme(dir.resolve("rb")))
        val namesA = Files.list(dir.resolve("a")).use { s -> s.map { it.fileName.toString() }.toList().sorted() }
        val namesB = Files.list(dir.resolve("b")).use { s -> s.map { it.fileName.toString() }.toList().sorted() }
        namesA shouldBe namesB
        for (name in namesA) {
            Files.readAllBytes(dir.resolve("a").resolve(name)) contentEquals
                Files.readAllBytes(dir.resolve("b").resolve(name)) shouldBe true
        }
        Files.readAllBytes(dir.resolve("ra").resolve("readme").resolve("README.md")) contentEquals
            Files.readAllBytes(dir.resolve("rb").resolve("readme").resolve("README.md")) shouldBe true
    }

    @Test
    fun lintFailureNamesFileAndWritesNothing() {
        val bad = DocEntry("bad.md", "Bad", "Bad entry.", { listOf(DocEntry.Mermaid("")) })
        val good =
            DocEntry(
                "good.md",
                "Good",
                "Good entry.",
                { listOf(DocEntry.Mermaid(StateDiagramExporter().export(emptyList()))) },
            )
        val message =
            shouldThrow<IllegalStateException> {
                DocsGenerator.write(dir.resolve("out"), listOf(good, bad))
            }.message.orEmpty()
        message shouldContain "bad.md"
        message shouldContain "diagram text is empty"
        Files.exists(dir.resolve("out")) shouldBe false
    }

    @Test
    fun allGeneratedDiagramsLintClean() {
        for (entry in DocEntries.standard(DocsFixtures.graph)) {
            for (block in entry.blocks().filterIsInstance<DocEntry.Mermaid>()) {
                MermaidLint.lint(block.text) shouldBe emptyList()
            }
        }
    }

    @Test
    fun changedModuleGraphChangesOnlyGraphDocs() {
        val g1 = DocsFixtures.graph
        val g2 =
            ModuleGraph(
                modules = g1.modules + ModuleGraph.Module("raftlab-extra", 4),
                edges = g1.edges + ModuleGraph.Edge("raftlab-docs", "raftlab-extra"),
            )
        val before = DocsGenerator.generate(g1).associate { it.path to it.content }
        val after = DocsGenerator.generate(g2).associate { it.path to it.content }
        after.keys shouldBe before.keys
        val changed = before.keys.filter { before.getValue(it) != after.getValue(it) }.toSet()
        changed shouldBe setOf("modules.md", "architecture.md")
    }

    @Test
    fun indexListsEveryOtherFile() {
        val files = DocsGenerator.generate(DocsFixtures.graph)
        val index = files.first { it.path == "index.md" }.content
        val links = Regex("\\]\\(([^)]+)\\)").findAll(index).map { it.groupValues[1] }.toList()
        links shouldBe files.map { it.path }.filter { it != "index.md" }
    }
}
