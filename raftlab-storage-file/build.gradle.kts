plugins {
    id("raftlab.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":raftlab-core"))
    testImplementation(testFixtures(project(":raftlab-core")))
}
