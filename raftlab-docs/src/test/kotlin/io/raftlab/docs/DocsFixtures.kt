package io.raftlab.docs

import io.raftlab.viz.ModuleGraph
import java.nio.file.Files
import java.nio.file.Path

internal object DocsFixtures {
    val graph: ModuleGraph =
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

    fun readme(dir: Path): Path {
        val file = dir.resolve("readme").resolve("README.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, "# Test\n\n${DocsGenerator.README_BEGIN}\n${DocsGenerator.README_END}\n")
        return file
    }

    fun committedGraph(): ModuleGraph {
        val path = Path.of(System.getProperty("raftlab.moduleGraph"))
        return ModuleGraph.fromJson(Files.readString(path))
    }

    fun committedReadme(): Path = Path.of(System.getProperty("raftlab.readme"))

    fun graphJson(): String =
        "{\n" +
            "  \"modules\": [\n" +
            "    {\"name\": \"raftlab-docs\", \"layer\": 2},\n" +
            "    {\"name\": \"raftlab-viz\", \"layer\": 2},\n" +
            "    {\"name\": \"raftlab-sim\", \"layer\": 3},\n" +
            "    {\"name\": \"raftlab-trace\", \"layer\": 6}\n" +
            "  ],\n" +
            "  \"edges\": [\n" +
            "    {\"from\": \"raftlab-docs\", \"to\": \"raftlab-sim\"},\n" +
            "    {\"from\": \"raftlab-docs\", \"to\": \"raftlab-viz\"},\n" +
            "    {\"from\": \"raftlab-sim\", \"to\": \"raftlab-trace\"},\n" +
            "    {\"from\": \"raftlab-viz\", \"to\": \"raftlab-trace\"}\n" +
            "  ]\n" +
            "}\n"
}
