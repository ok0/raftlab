plugins {
    id("raftlab.kotlin-library")
}

dependencies {
    implementation(project(":raftlab-sim"))
    implementation(project(":raftlab-viz"))
}

val docsDir = rootProject.layout.projectDirectory.dir("docs/generated")
val docsMain = "io.raftlab.docs.DocsMainKt"
val moduleGraphFile = rootProject.layout.buildDirectory.file("raftlab/module-graph.json")
val readmeFile = rootProject.layout.projectDirectory.file("README.md")

tasks.register<JavaExec>("generateDocs") {
    group = "documentation"
    description = "Writes the generated Mermaid docs into docs/generated and the README architecture region"
    dependsOn(":writeModuleGraph")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(docsMain)
    args(
        "--out",
        docsDir.asFile.path,
        "--module-graph",
        moduleGraphFile.get().asFile.path,
        "--readme",
        readmeFile.asFile.path,
    )
}

tasks.register<JavaExec>("checkDocs") {
    group = "verification"
    description = "Fails if docs/generated or the README architecture region differs from freshly generated docs"
    dependsOn(":writeModuleGraph")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(docsMain)
    args(
        "--out",
        docsDir.asFile.path,
        "--module-graph",
        moduleGraphFile.get().asFile.path,
        "--readme",
        readmeFile.asFile.path,
        "--check",
    )
}

tasks.named<Test>("test") {
    dependsOn(":writeModuleGraph")
    inputs.dir(docsDir).withPropertyName("generatedDocs").optional()
    inputs.file(moduleGraphFile).withPropertyName("moduleGraph")
    inputs.file(readmeFile).withPropertyName("readme")
    systemProperty("raftlab.docs.dir", docsDir.asFile.path)
    systemProperty("raftlab.moduleGraph", moduleGraphFile.get().asFile.path)
    systemProperty("raftlab.readme", readmeFile.asFile.path)
}
