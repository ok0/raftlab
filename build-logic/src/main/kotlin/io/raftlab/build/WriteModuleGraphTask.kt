package io.raftlab.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets

abstract class WriteModuleGraphTask : DefaultTask() {
    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val edges: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val moduleEntries = modules.get().map { encoded ->
            val path = encoded.substringBefore('|')
            val layer = encoded.substringAfter('|').toInt()
            if (layer == 0) {
                throw GradleException("Module $path is not listed in ModuleRules.layers")
            }
            ModuleEntry(nameOf(path), layer)
        }.sortedBy { it.name }
        val edgeEntries = edges.get().map { encoded ->
            EdgeEntry(nameOf(encoded.substringBefore('|')), nameOf(encoded.substringAfter('|')))
        }.distinct().sortedWith(compareBy({ it.from }, { it.to }))
        val text = buildString {
            append("{\n")
            append("  \"modules\": [\n")
            append(moduleEntries.joinToString(",\n") { "    {\"name\": \"${it.name}\", \"layer\": ${it.layer}}" })
            append("\n  ],\n")
            append("  \"edges\": [\n")
            append(edgeEntries.joinToString(",\n") { "    {\"from\": \"${it.from}\", \"to\": \"${it.to}\"}" })
            append("\n  ]\n")
            append("}\n")
        }
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(text, StandardCharsets.UTF_8)
        logger.lifecycle(
            "Wrote module graph (${moduleEntries.size} modules, ${edgeEntries.size} edges) to ${file.path}",
        )
    }

    private fun nameOf(path: String): String {
        val name = path.removePrefix(":")
        if (!NAME_PATTERN.matches(name)) {
            throw GradleException("Unsupported module name for module-graph.json: $path")
        }
        return name
    }

    private data class ModuleEntry(val name: String, val layer: Int)

    private data class EdgeEntry(val from: String, val to: String)

    private companion object {
        val NAME_PATTERN = Regex("[A-Za-z0-9._-]+")
    }
}
