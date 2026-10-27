plugins {
    id("raftlab.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":raftlab-core"))
    api(project(":raftlab-trace"))
    api(libs.kotlinx.serialization.json)
}
