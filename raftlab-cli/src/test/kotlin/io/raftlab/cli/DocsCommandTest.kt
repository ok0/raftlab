package io.raftlab.cli

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.streams.toList

internal class DocsCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val committedDocs: Path get() = CliTest.prop("raftlab.docs.dir")
    private val graph: Path get() = CliTest.prop("raftlab.moduleGraph")
    private val committedReadme: Path get() = CliTest.prop("raftlab.readme")

    private fun copyDocs(into: Path): Pair<Path, Path> {
        val docs = into.resolve("docs")
        Files.walk(committedDocs).use { stream ->
            stream.toList().filter { it.isRegularFile() }.forEach { file ->
                val target = docs.resolve(file.relativeTo(committedDocs).toString())
                Files.createDirectories(target.parent)
                Files.copy(file, target)
            }
        }
        val readme = into.resolve("README.md")
        Files.copy(committedReadme, readme)
        return docs to readme
    }

    @Test
    fun checkPassesOnCommittedDocs() {
        val r =
            CliTest.run(
                "docs",
                "--check",
                "--out",
                committedDocs.toString(),
                "--module-graph",
                graph.toString(),
                "--readme",
                committedReadme.toString(),
            )
        r.exitCode shouldBe 0
        r.stdout shouldContain "Generated docs are up to date."
    }

    @Test
    fun checkFailsAfterHandEdit() {
        val (docs, readme) = copyDocs(tmp)
        val modules = docs.resolve("modules.md")
        Files.writeString(modules, modules.readText() + "edited\n")
        val r =
            CliTest.run(
                "docs",
                "--check",
                "--out",
                docs.toString(),
                "--module-graph",
                graph.toString(),
                "--readme",
                readme.toString(),
            )
        r.exitCode shouldBe 1
        r.stderr shouldContain "modules.md"
        r.stderr shouldContain "Generated docs are stale"
    }

    @Test
    fun writeTwiceMatchesCommitted() {
        val (docs, readme) = copyDocs(tmp)
        val modules = docs.resolve("modules.md")
        Files.writeString(modules, modules.readText() + "edited\n")
        val args =
            arrayOf(
                "docs",
                "--out",
                docs.toString(),
                "--module-graph",
                graph.toString(),
                "--readme",
                readme.toString(),
            )
        CliTest.run(*args).exitCode shouldBe 0
        CliTest.run(*args).exitCode shouldBe 0

        Files.walk(committedDocs).use { stream ->
            stream.toList().filter { it.isRegularFile() }.forEach { file ->
                val written = docs.resolve(file.relativeTo(committedDocs).toString())
                Files.readString(written) shouldBe Files.readString(file)
            }
        }
        Files.readString(readme) shouldBe Files.readString(committedReadme)
    }
}
