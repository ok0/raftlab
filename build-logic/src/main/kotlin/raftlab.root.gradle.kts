import io.raftlab.build.ModuleRules
import io.raftlab.build.VerifyModuleBoundariesTask
import io.raftlab.build.WriteModuleGraphTask
import org.gradle.api.artifacts.ProjectDependency

plugins {
    base
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.dokka")
    id("raftlab.ktlint")
}

val root = project

subprojects {
    val sub = this
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        root.dependencies.add("kover", root.project(sub.path))
    }
    pluginManager.withPlugin("org.jetbrains.dokka") {
        root.dependencies.add("dokka", root.project(sub.path))
    }
}

val verifyModuleBoundaries =
    tasks.register<VerifyModuleBoundariesTask>("verifyModuleBoundaries") {
        group = "verification"
        description = "Checks project dependencies against ModuleRules"
    }

val writeModuleGraph =
    tasks.register<WriteModuleGraphTask>("writeModuleGraph") {
        group = "documentation"
        description = "Writes the module dependency graph to build/raftlab/module-graph.json"
        outputFile.set(layout.buildDirectory.file("raftlab/module-graph.json"))
    }

gradle.projectsEvaluated {
    val encoded =
        root.subprojects
            .flatMap { p ->
                ModuleRules.mainConfigurations.mapNotNull { p.configurations.findByName(it) }.flatMap { c ->
                    c.dependencies.withType(ProjectDependency::class.java).map { d -> "${p.path}|${d.path}|${c.name}" }
                }
            }.sorted()
    verifyModuleBoundaries.configure { edges.set(encoded) }
    val moduleList = root.subprojects.map { "${it.path}|${ModuleRules.layers[it.path] ?: 0}" }.sorted()
    writeModuleGraph.configure {
        modules.set(moduleList)
        edges.set(encoded.map { it.substringBeforeLast('|') }.distinct().sorted())
    }
}

val checkDocs =
    tasks.register("checkDocs") {
        group = "verification"
        description = "Fails if docs/generated differs from freshly generated docs"
        dependsOn(":raftlab-docs:checkDocs")
    }

tasks.register("generateDocs") {
    group = "documentation"
    description = "Regenerates docs/generated (not part of build)"
    dependsOn(":raftlab-docs:generateDocs")
}

tasks.named("check") {
    dependsOn(verifyModuleBoundaries, checkDocs)
}
