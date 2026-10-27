package io.raftlab.docs

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocsCheckTest {
    @TempDir
    lateinit var dir: Path

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
        DocsGenerator.write(dir)
        DocsGenerator.check(dir) shouldBe emptyList()
    }

    @Test
    fun editedFileIsReportedWithADiff() {
        DocsGenerator.write(dir)
        editElection()
        val before = snapshot(dir)
        val problems = DocsGenerator.check(dir)
        problems.size shouldBe 1
        problems[0].startsWith("differs: election.md\n") shouldBe true
        problems[0] shouldContain "\n-# Leader electioN\n"
        problems[0] shouldContain "\n+# Leader election\n"
        snapshot(dir) shouldBe before
    }

    @Test
    fun missingFileIsReported() {
        DocsGenerator.write(dir)
        Files.delete(dir.resolve("roles-election.md"))
        val before = snapshot(dir)
        DocsGenerator.check(dir) shouldBe
            listOf("missing: roles-election.md (expected generated file does not exist)")
        snapshot(dir) shouldBe before
    }

    @Test
    fun extraMdFileIsReported() {
        DocsGenerator.write(dir)
        Files.writeString(dir.resolve("extra.md"), "x\n")
        val before = snapshot(dir)
        val problems = DocsGenerator.check(dir)
        problems.size shouldBe 1
        problems[0].startsWith("unexpected: extra.md") shouldBe true
        snapshot(dir) shouldBe before
        Files.exists(dir.resolve("extra.md")) shouldBe true
    }

    @Test
    fun nonMdExtraFileIsIgnored() {
        DocsGenerator.write(dir)
        Files.writeString(dir.resolve("notes.txt"), "notes\n")
        DocsGenerator.check(dir) shouldBe emptyList()
    }

    @Test
    fun afterRegenerateCheckPassesAgain() {
        DocsGenerator.write(dir)
        editElection()
        DocsGenerator.check(dir).isEmpty() shouldBe false
        DocsGenerator.write(dir)
        DocsGenerator.check(dir) shouldBe emptyList()
    }
}
