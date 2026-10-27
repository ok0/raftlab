plugins {
    id("raftlab.kotlin-library")
}

dependencies {
    implementation(project(":raftlab-sim"))
    implementation(project(":raftlab-viz"))
}

val docsDir = rootProject.layout.projectDirectory.dir("docs/generated")
val docsMain = "io.raftlab.docs.DocsMainKt"

tasks.register<JavaExec>("generateDocs") {
    group = "documentation"
    description = "Writes the generated Mermaid docs into docs/generated"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(docsMain)
    args("--out", docsDir.asFile.path)
}

tasks.register<JavaExec>("checkDocs") {
    group = "verification"
    description = "Fails if docs/generated differs from freshly generated docs"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(docsMain)
    args("--out", docsDir.asFile.path, "--check")
}

tasks.named<Test>("test") {
    inputs.dir(docsDir).withPropertyName("generatedDocs").optional()
    systemProperty("raftlab.docs.dir", docsDir.asFile.path)
}
