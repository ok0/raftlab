package io.raftlab.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyModuleBoundariesTask : DefaultTask() {
    @get:Input
    abstract val edges: ListProperty<String>

    @TaskAction
    fun verify() {
        val decoded = edges.get().map { encoded ->
            val (from, to, configuration) = encoded.split('|')
            ModuleEdge(from, to, configuration)
        }
        val v = ModuleRules.violations(decoded)
        if (v.isNotEmpty()) {
            throw GradleException("Module boundary check failed:\n" + v.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("Module boundaries OK (${decoded.size} project dependencies checked)")
    }
}
