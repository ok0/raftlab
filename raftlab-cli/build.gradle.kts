import java.time.Duration

plugins {
    id("raftlab.kotlin-app")
}

dependencies {
    implementation(project(":raftlab-docs"))
    implementation(project(":raftlab-sim"))
    implementation(project(":raftlab-viz"))
    implementation(project(":raftlab-net"))
    implementation(project(":raftlab-storage-file"))
    implementation(libs.clikt.core)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("io.raftlab.cli.MainKt")
    applicationName = "raftlab"
}

val docsDir = rootProject.layout.projectDirectory.dir("docs/generated")
val moduleGraphFile = rootProject.layout.buildDirectory.file("raftlab/module-graph.json")
val readmeFile = rootProject.layout.projectDirectory.file("README.md")
val testTmp = layout.buildDirectory.dir("test-tmp")

tasks.named<Test>("test") {
    dependsOn(":writeModuleGraph")
    inputs.dir(docsDir).withPropertyName("generatedDocs").optional()
    inputs.file(moduleGraphFile).withPropertyName("moduleGraph")
    inputs.file(readmeFile).withPropertyName("readme")
    systemProperty("raftlab.docs.dir", docsDir.asFile.path)
    systemProperty("raftlab.moduleGraph", moduleGraphFile.get().asFile.path)
    systemProperty("raftlab.readme", readmeFile.asFile.path)
    systemProperty("java.io.tmpdir", testTmp.get().asFile.path)
    doFirst { testTmp.get().asFile.mkdirs() }
    timeout.set(Duration.ofMinutes(3))
    systemProperty("junit.jupiter.execution.timeout.default", "45 s")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
}
