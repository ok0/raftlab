package io.raftlab.docs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.raftlab.viz.ModuleGraphRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocsCheckTest {
    @TempDir
    lateinit var dir: Path

    private val readme: Path by lazy { DocsFixtures.readme(dir) }
    private val graph = DocsFixtures.graph

    private fun snapshot(d: Path): Map<String, String> {
        val files = Files.list(d).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        return files.associate { it.fileName.toString() to String(Files.readAllBytes(it), Charsets.UTF_8) }
    }

    private fun editElection() {
        val p = dir.resolve("election.md")
        val text = Files.readString(p)
        Files.writeString(p, text.replaceFirst("# Leader election\n", "# Leader electioN\n"))
    }

    @Test
    fun matchingDirectoryHasNoProblems() {
        DocsGenerator.write(dir, graph, readme)
        DocsGenerator.check(dir, graph, readme) shouldBe emptyList()
    }

    @Test
    fun editedFileIsReportedWithADiff() {
        DocsGenerator.write(dir, graph, readme)
        editElection()
        val before = snapshot(dir)
        val problems = DocsGenerator.check(dir, graph, readme)
        problems.size shouldBe 1
        problems[0].startsWith("differs: election.md\n") shouldBe true
        problems[0] shouldContain "\n-# Leader electioN\n"
        problems[0] shouldContain "\n+# Leader election\n"
        snapshot(dir) shouldBe before
    }

    @Test
    fun missingFileIsReported() {
        DocsGenerator.write(dir, graph, readme)
        Files.delete(dir.resolve("roles-election.md"))
        val before = snapshot(dir)
        DocsGenerator.check(dir, graph, readme) shouldBe
            listOf("missing: roles-election.md (expected generated file does not exist)")
        snapshot(dir) shouldBe before
    }

    @Test
    fun extraMdFileIsReported() {
        DocsGenerator.write(dir, graph, readme)
        Files.writeString(dir.resolve("extra.md"), "x\n")
        val before = snapshot(dir)
        val problems = DocsGenerator.check(dir, graph, readme)
        problems.size shouldBe 1
        problems[0].startsWith("unexpected: extra.md") shouldBe true
        snapshot(dir) shouldBe before
        Files.exists(dir.resolve("extra.md")) shouldBe true
    }

    @Test
    fun nonMdExtraFileIsIgnored() {
        DocsGenerator.write(dir, graph, readme)
        Files.writeString(dir.resolve("notes.txt"), "notes\n")
        DocsGenerator.check(dir, graph, readme) shouldBe emptyList()
    }

    @Test
    fun afterRegenerateCheckPassesAgain() {
        DocsGenerator.write(dir, graph, readme)
        editElection()
        DocsGenerator.check(dir, graph, readme).isEmpty() shouldBe false
        DocsGenerator.write(dir, graph, readme)
        DocsGenerator.check(dir, graph, readme) shouldBe emptyList()
    }

    @Test
    fun editedIndexIsReported() {
        DocsGenerator.write(dir, graph, readme)
        val p = dir.resolve("index.md")
        Files.writeString(p, Files.readString(p).replaceFirst("# Generated diagrams\n", "# Generated diagramS\n"))
        val problems = DocsGenerator.check(dir, graph, readme)
        problems.size shouldBe 1
        problems[0].startsWith("differs: index.md\n") shouldBe true
    }

    @Test
    fun editedReadmeRegionIsReported() {
        DocsGenerator.write(dir, graph, readme)
        Files.writeString(readme, Files.readString(readme).replace("flowchart TB", "flowchart LR"))
        val problems = DocsGenerator.check(dir, graph, readme)
        problems.size shouldBe 1
        problems[0].startsWith("differs: README.md\n") shouldBe true
        problems[0] shouldContain "-flowchart LR"
        problems[0] shouldContain "+flowchart TB"
    }

    @Test
    fun missingReadmeMarkerFails() {
        Files.writeString(readme, "# Test\n\n${DocsGenerator.README_BEGIN}\n")
        val writeMessage =
            shouldThrow<IllegalStateException> {
                DocsGenerator.write(dir.resolve("out"), graph, readme)
            }.message.orEmpty()
        writeMessage shouldContain DocsGenerator.README_END
        Files.exists(dir.resolve("out")) shouldBe false
        shouldThrow<IllegalStateException> {
            DocsGenerator.check(dir, graph, readme)
        }
        Files.writeString(readme, "# Test\n\n")
        val beginMessage =
            shouldThrow<IllegalStateException> {
                DocsGenerator.write(dir.resolve("out"), graph, readme)
            }.message.orEmpty()
        beginMessage shouldContain DocsGenerator.README_BEGIN
    }

    @Test
    fun regeneratedReadmeIsStable() {
        DocsGenerator.write(dir, graph, readme)
        val first = Files.readAllBytes(readme)
        DocsGenerator.write(dir, graph, readme)
        Files.readAllBytes(readme) contentEquals first shouldBe true
        Files.readString(readme) shouldContain "```mermaid\n" + ModuleGraphRenderer.overview(graph)
    }
}
