plugins {
    id("raftlab.kotlin-library")
}

dependencies {
    api(project(":raftlab-core"))
    api(project(":raftlab-trace"))
    api(project(":raftlab-kv"))
}

val fuzzSeeds = providers.gradleProperty("raftlab.fuzz.seeds").orElse("1000")
val mutationsDoc = rootProject.layout.projectDirectory.file("docs/design/mutations.md")

tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("fuzz") }
}

val fuzzTest =
    tasks.register<Test>("fuzzTest") {
        group = "verification"
        description = "Runs fuzz-tagged tests over -Praftlab.fuzz.seeds seeds (default 1000)"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("fuzz") }
        inputs.property("raftlab.fuzz.seeds", fuzzSeeds)
        inputs.file(mutationsDoc).withPropertyName("mutationsDoc").optional()
        systemProperty("raftlab.fuzz.seeds", fuzzSeeds.get())
        systemProperty("raftlab.mutations.doc", mutationsDoc.asFile.path)
        shouldRunAfter(tasks.named("test"))
    }

tasks.named("check") { dependsOn(fuzzTest) }

kover {
    currentProject {
        instrumentation {
            disabledForTestTasks.add("fuzzTest")
        }
    }
}
