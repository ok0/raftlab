package io.raftlab.docs

import io.kotest.matchers.shouldBe
import io.raftlab.sim.ScenarioLibrary
import io.raftlab.trace.NodeCrashed
import io.raftlab.trace.PartitionChanged
import io.raftlab.trace.Role
import io.raftlab.trace.RoleChanged
import io.raftlab.trace.TraceEvent
import io.raftlab.viz.MermaidLint
import io.raftlab.viz.ModuleGraphRenderer
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CommittedDocsTest {
    private val dir: Path by lazy { Path.of(System.getProperty("raftlab.docs.dir")) }

    private fun read(name: String): String = Files.readString(dir.resolve(name))

    private fun mermaidBlocks(text: String): List<String> =
        Regex("```mermaid\n(.*?)\n```\n", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1] }.toList()

    private fun traceOf(
        name: String,
        seed: Long,
    ): List<TraceEvent> {
        val scenario = ScenarioLibrary.all.getValue(name)
        val result = scenario.run(seed)
        return result.trace
    }

    private fun tableCells(section: String): List<List<String>> {
        val rows = section.lines().filter { isDataRow(it) }
        return rows.map { cells(it) }
    }

    private fun isDataRow(line: String): Boolean =
        line.startsWith("| ") && !line.startsWith("| Node ") && !line.startsWith("| ---")

    private fun cells(row: String): List<String> {
        val parts = row.split("|")
        val inner = parts.subList(1, parts.size - 1)
        val values = inner.map { it.trim().replace("*", "") }
        return values.subList(2, values.size)
    }

    @Test
    fun committedDocsMatchTheGeneratorAndLintClean() {
        DocsGenerator.check(dir, DocsFixtures.committedGraph(), DocsFixtures.committedReadme()) shouldBe emptyList()
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
        for (block in mermaidBlocks(Files.readString(DocsFixtures.committedReadme()))) {
            MermaidLint.lint(block) shouldBe emptyList()
        }
    }

    @Test
    fun moduleGraphMatchesTheBuild() {
        val root = DocsFixtures.committedReadme().parent
        val settings = Files.readString(root.resolve("settings.gradle.kts"))
        val modules = Regex("\":([A-Za-z0-9_-]+)\"").findAll(settings).map { it.groupValues[1] }.toSet()
        val configurations = "api|implementation|compileOnly|compileOnlyApi|runtimeOnly"
        val edgeRegex = Regex("(?<![A-Za-z])($configurations)\\(project\\(\":([A-Za-z0-9_-]+)\"\\)\\)")
        val parsedEdges = mutableSetOf<Pair<String, String>>()
        for (m in modules) {
            val build = Files.readString(root.resolve(m).resolve("build.gradle.kts"))
            for (match in edgeRegex.findAll(build)) {
                parsedEdges += Pair(m, match.groupValues[2])
            }
        }
        val graph = DocsFixtures.committedGraph()
        graph.modules.map { it.name }.toSet() shouldBe modules
        graph.edges.map { Pair(it.from, it.to) }.toSet() shouldBe parsedEdges
        val modulesMd = read("modules.md")
        for (module in graph.modules) {
            modulesMd.contains("[\"${module.name}\"]") shouldBe true
        }
        for (layer in graph.modules.map { it.layer }.distinct()) {
            modulesMd.contains("Layer $layer") shouldBe true
        }
        modulesMd.lines().count { it.contains(" --> ") } shouldBe graph.edges.size
    }

    @Test
    fun committedGalleryContent() {
        replicationContent()
        leaderCrashContent()
        partitionContent()
        architectureContent()
        indexContent()
        readmeContent()
    }

    private fun replicationContent() {
        val text = read("replication.md")
        val lines = text.lines()
        val firstInstall = lines.indexOfFirst { it.contains(": InstallSnapshot ") }
        (firstInstall >= 0) shouldBe true
        val response = lines.indexOfFirst { it.contains("InstallSnapshotResponse") }
        (response > firstInstall) shouldBe true
        lines.drop(firstInstall + 1).any { it.contains(": AppendEntries ") } shouldBe true
        val arrows = lines.count { Regex("^    P\\d+(->>|-x)P\\d+: ").containsMatchIn(it) }
        (arrows <= DocEntries.MAX_MESSAGES) shouldBe true
    }

    private fun leaderCrashContent() {
        val text = read("roles-leader-crash.md")
        val aliases = Regex("as (node\\d+)_Leader").findAll(text).map { it.groupValues[1] }.toSet()
        (aliases.size >= 2) shouldBe true
        val transitions = text.lines().count { it.contains(" --> ") && !it.contains("[*]") }
        (transitions <= 40) shouldBe true
        val trace = traceOf("leader-crash", DocEntries.LEADER_CRASH_SEED)
        val roles = trace.filterIsInstance<RoleChanged>()
        val ordered =
            trace.filterIsInstance<NodeCrashed>().any { crash ->
                roles.any { it.node == crash.node && it.to == Role.LEADER && it.seq < crash.seq } &&
                    roles.any { it.node != crash.node && it.to == Role.LEADER && it.seq > crash.seq }
            }
        ordered shouldBe true
    }

    private fun partitionContent() {
        val text = read("partition-heal-log.md")
        val sections = text.split("**t = ").drop(1)
        (sections.size >= 3) shouldBe true
        val tables = sections.map { tableCells(it) }
        (tables[1].distinct().size > 1) shouldBe true
        (tables.last().distinct().size == 1) shouldBe true
        val trace = traceOf("partition-heal", DocEntries.PARTITION_SEED)
        val partitions = trace.filterIsInstance<PartitionChanged>()
        val split = partitions.first { it.groups.size > 1 }
        val heal = partitions.first { it.seq > split.seq }
        (split.time > DocEntries.PARTITION_TIMES[0] && split.time < DocEntries.PARTITION_TIMES[1]) shouldBe true
        (heal.time > DocEntries.PARTITION_TIMES[1] && heal.time < DocEntries.PARTITION_TIMES[2]) shouldBe true
    }

    private fun architectureContent() {
        val blocks = mermaidBlocks(read("architecture.md"))
        blocks.size shouldBe 2
        blocks[0].startsWith("flowchart TB") shouldBe true
        blocks[1].startsWith("flowchart LR") shouldBe true
        blocks[1].contains("|ClientRequest|") shouldBe true
    }

    private fun indexContent() {
        val index = read("index.md")
        val links = Regex("\\]\\(([^)]+)\\)").findAll(index).map { it.groupValues[1] }.toList()
        for (link in links) {
            Files.isRegularFile(dir.resolve(link)) shouldBe true
        }
        val others =
            Files.list(dir).use { s ->
                s.map { it.fileName.toString() }.filter { it.endsWith(".md") && it != "index.md" }.toList()
            }
        links.toSet() shouldBe others.toSet()
    }

    private fun readmeContent() {
        val readme = Files.readString(DocsFixtures.committedReadme())
        readme.contains("```mermaid\n" + ModuleGraphRenderer.overview(DocsFixtures.committedGraph())) shouldBe true
    }
}
