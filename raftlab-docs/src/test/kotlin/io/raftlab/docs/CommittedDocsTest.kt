package io.raftlab.docs

import io.kotest.matchers.shouldBe
import io.raftlab.viz.MermaidLint
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CommittedDocsTest {
    @Test
    fun committedDocsMatchTheGeneratorAndLintClean() {
        val dir = Path.of(System.getProperty("raftlab.docs.dir"))
        DocsGenerator.check(dir) shouldBe emptyList()
        val mdFiles =
            Files.list(dir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }.toList()
            }
        for (file in mdFiles) {
            val text = Files.readString(file)
            var idx = text.indexOf("```mermaid\n")
            while (idx >= 0) {
                val start = idx + "```mermaid\n".length
                val end = text.indexOf("```\n", start)
                val block = text.substring(start, end)
                MermaidLint.lint(block) shouldBe emptyList()
                idx = text.indexOf("```mermaid\n", end)
            }
        }
    }
}
