package io.raftlab.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Path

abstract class CountLocTask : DefaultTask() {
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    init {
        doNotTrackState("Prints line counts to the console")
    }

    @TaskAction
    fun count() {
        val root: Path = rootDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val files = sources.files
        if (files.isEmpty()) {
            throw GradleException("countLoc: no Kotlin sources found")
        }
        val columns = listOf("main", "test", "other")
        val perModule = sortedMapOf<String, MutableMap<String, IntArray>>()
        for (file in files) {
            val rel = root.relativize(file.toPath().toAbsolutePath().normalize()).joinToString("/")
            val parts = rel.split('/')
            val module = parts[0]
            val bucket =
                when (parts[2]) {
                    "main" -> "main"
                    "test" -> "test"
                    else -> "other"
                }
            val lines = file.readLines(Charsets.UTF_8)
            val counts = perModule.getOrPut(module) { mutableMapOf() }.getOrPut(bucket) { IntArray(2) }
            counts[0] += lines.size
            counts[1] += lines.count { it.isNotBlank() }
        }
        val totals = columns.associateWith { IntArray(2) }
        perModule.values.forEach { buckets ->
            columns.forEach { column ->
                val counts = buckets[column] ?: return@forEach
                totals.getValue(column)[0] += counts[0]
                totals.getValue(column)[1] += counts[1]
            }
        }
        fun cell(counts: IntArray?): String = if (counts == null) "0 (0)" else "${counts[0]} (${counts[1]})"
        fun row(label: String, cells: List<String>): String = "%-22s%18s%18s%18s".format(label, cells[0], cells[1], cells[2])
        logger.lifecycle(
            "countLoc: lines of *.kt under <module>/src/<sourceSet>/kotlin " +
                "(all physical lines incl. blank and comment lines; non-blank in parentheses)",
        )
        logger.lifecycle(row("module", columns))
        for ((module, buckets) in perModule) {
            logger.lifecycle(row(module, columns.map { cell(buckets[it]) }))
        }
        logger.lifecycle(row("TOTAL", columns.map { cell(totals.getValue(it)) }))
        val allLines = totals.values.sumOf { it[0] }
        val nonBlankLines = totals.values.sumOf { it[1] }
        logger.lifecycle("total (main + test + other): $allLines lines ($nonBlankLines non-blank)")
    }
}
