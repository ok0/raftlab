package io.raftlab.docs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.raftlab.viz.MermaidLint
import io.raftlab.viz.StateDiagramExporter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocsGeneratorTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun entriesAreElectionAndRolesInOrder() {
        DocsGenerator.generate().map { it.path } shouldBe listOf("election.md", "roles-election.md")
    }

    @Test
    fun filesHaveHeaderTitleDescriptionAndOneMermaidBlock() {
        val files = DocsGenerator.generate()
        for (f in files) {
            val content = f.content
            content.startsWith(DocsGenerator.HEADER + "\n# ") shouldBe true
            val lines = content.split("\n")
            lines[2] shouldBe ""
            lines[3] shouldContain "seed 42"
            Regex("```mermaid\n").findAll(content).count() shouldBe 1
            ('\r' in content) shouldBe false
            content.endsWith("\n```\n") shouldBe true
            content.endsWith("\n\n") shouldBe false
        }
        files.first { it.path == "election.md" }.content shouldContain "```mermaid\nsequenceDiagram\n"
        files.first { it.path == "roles-election.md" }.content shouldContain "```mermaid\nstateDiagram-v2\n"
    }

    @Test
    fun generationIsByteIdenticalAcrossRuns() {
        DocsGenerator.write(dir.resolve("a"))
        DocsGenerator.write(dir.resolve("b"))
        val namesA = Files.list(dir.resolve("a")).use { s -> s.map { it.fileName.toString() }.toList().sorted() }
        val namesB = Files.list(dir.resolve("b")).use { s -> s.map { it.fileName.toString() }.toList().sorted() }
        namesA shouldBe namesB
        for (name in namesA) {
            Files.readAllBytes(dir.resolve("a").resolve(name)) contentEquals
                Files.readAllBytes(dir.resolve("b").resolve(name)) shouldBe true
        }
    }

    @Test
    fun lintFailureNamesFileAndWritesNothing() {
        val bad = DocEntry("bad.md", "Bad", "Bad entry.", { "" })
        val good = DocEntry("good.md", "Good", "Good entry.", { StateDiagramExporter().export(emptyList()) })
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
        for (entry in DocEntries.standard()) {
            MermaidLint.lint(entry.diagram()) shouldBe emptyList()
        }
    }
}
